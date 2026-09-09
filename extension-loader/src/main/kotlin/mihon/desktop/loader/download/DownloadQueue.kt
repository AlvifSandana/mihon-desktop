package mihon.desktop.loader.download

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import mihon.desktop.loader.library.NotificationManager
import mihon.desktop.loader.prefs.AppPreferences
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Lifecycle of a [DownloadJob]. CANCELLED is transient: cancelled jobs are removed from the list. */
enum class DownloadJobState {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/**
 * One chapter download in the [DownloadQueue]. Identity for dedupe purposes is
 * (sourceId, mangaUrl, chapterUrl); `id` is unique per queue entry.
 *
 * `pagesDone`/`pagesTotal` are live while RUNNING (updated per page); `error`
 * is set when FAILED; `attempts` counts tries (max [DownloadQueue.MAX_ATTEMPTS]).
 */
data class DownloadJob(
    val id: Long,
    val sourceId: Long,
    val mangaUrl: String,
    val chapterUrl: String,
    val chapterName: String,
    val mangaTitle: String,
    val thumbnailUrl: String?,
    val state: DownloadJobState = DownloadJobState.QUEUED,
    val attempts: Int = 0,
    val error: String? = null,
    val pagesDone: Int = 0,
    val pagesTotal: Int = 0,
)

/**
 * Executes one chapter download (page fetch + disk write + DB record).
 * Production: [DownloadManagerChapterExecutor] on top of [DownloadManager];
 * tests inject fakes. Implementations must be cancellation-cooperative: they
 * run inside the queue's worker coroutine, and coroutine cancellation is the
 * pause/cancel signal (check it between network operations / use suspending
 * waits).
 */
fun interface ChapterDownloadExecutor {
    /**
     * Downloads [chapter]. Invokes [onProgress] with (pagesDone, pagesTotal)
     * as pages complete; returns the page count; throws on failure.
     */
    suspend fun download(
        source: Source,
        mangaUrl: String,
        chapter: SChapter,
        onProgress: suspend (pagesDone: Int, pagesTotal: Int) -> Unit,
    ): Int
}

/** [ChapterDownloadExecutor] backed by the real [DownloadManager]. */
class DownloadManagerChapterExecutor(
    private val manager: DownloadManager = DownloadManager(),
) : ChapterDownloadExecutor {
    override suspend fun download(
        source: Source,
        mangaUrl: String,
        chapter: SChapter,
        onProgress: suspend (pagesDone: Int, pagesTotal: Int) -> Unit,
    ): Int = manager.downloadChapter(source, mangaUrl, chapter, onProgress)
}

/** Fired once when the queue fully drains (every job reached a terminal state). */
fun interface DownloadDrainNotifier {
    fun onDrained(completed: Int, failed: Int)
}

/**
 * Posts the default drain summary through the app's NotificationManager.
 * Message keys (resolved via NotificationManager's resolver seam — this
 * module has no string table); extra args beyond a template's placeholders
 * are ignored, so all variants share the one call.
 */
class NotificationDrainNotifier : DownloadDrainNotifier {
    override fun onDrained(completed: Int, failed: Int) {
        val messageKey = when {
            failed > 0 && completed == 1 -> "notif_downloads_complete_one_failed"
            failed > 0 -> "notif_downloads_complete_many_failed"
            completed == 1 -> "notif_downloads_complete_one"
            else -> "notif_downloads_complete_many"
        }
        NotificationManager.notify("notif_downloads_complete_title", messageKey, completed, failed)
    }
}

/** Internal per-job execution payload; dropped when the job reaches a terminal state. */
private class JobPayload(val source: Source, val mangaUrl: String, val chapter: SChapter)

/**
 * Queue-based chapter downloader with a small parallel worker pool.
 *
 * - **FIFO, N parallel chapters** (default 2, 1-4 via the
 *   `AppPreferences.KEY_DOWNLOAD_CONCURRENCY` setting, read live at every
 *   worker pickup). Pages stay sequential within a chapter (see
 * [DownloadManager.downloadChapter]).
 * - **Retry**: up to [MAX_ATTEMPTS] attempts per job with exponential backoff
 *   (1s, 4s, 16s...; cancellable sleep). Exhausted attempts -> FAILED with the
 *   error message exposed on the job. [Error]s (e.g. NoClassDefFoundError from
 *   a broken extension jar) are deterministic, so they FAIL the job on the
 *   first attempt -- no retry, no backoff.
 * - **Pause** stops picking new jobs and cancels running workers; the in-flight
 *   OkHttp call is cancelled (suspend-cooperative) and any already-written
 *   pages are kept, so resuming skips them. Paused running jobs go back to
 *   QUEUED. Note their retry attempt counter restarts on resume.
 * - **Cancel** removes queued and failed jobs immediately; running jobs are
 *   cancelled at the next cooperative point and then removed from the list.
 * - **Notification**: exactly one summary per drained batch (queue-empty
 *   moment) via [DownloadDrainNotifier] -- never per chapter. Cancellations
 *   and pauses never fire it.
 * - **Persistence**: the queue itself is in-memory; restarting the app clears
 *   pending/failed jobs. Completed chapters persist as DownloadedChapter DB
 *   rows + files on disk (unchanged [DownloadManager] behavior).
 *
 * Migration ([mihon.desktop.loader.migration.MigrationDownloads]) intentionally
 * stays on the direct `DownloadManager.downloadChapter` path: migration wants
 * deterministic sequential completion and its own failure isolation, not queue
 * semantics.
 *
 * All state transitions are serialized through one mutex; the job list is
 * published as an immutable snapshot via [jobs] for reactive UIs.
 */
class DownloadQueue(
    private val executor: ChapterDownloadExecutor = DownloadManagerChapterExecutor(),
    private val notifier: DownloadDrainNotifier = NotificationDrainNotifier(),
    /** Cancellable backoff sleep; tests inject a recorder so tests never sleep. */
    private val backoffSleeper: suspend (millis: Long) -> Unit = { delay(it) },
    /** Read at every worker pickup; the default observes the preference live. */
    private val concurrencyProvider: () -> Int = {
        AppPreferences.getInt(AppPreferences.KEY_DOWNLOAD_CONCURRENCY, AppPreferences.DEFAULT_DOWNLOAD_CONCURRENCY)
            .coerceIn(1, 4)
    },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val mutex = Mutex()
    private val jobList = mutableListOf<DownloadJob>()
    private val payloads = mutableMapOf<Long, JobPayload>()
    private val workerJobs = mutableMapOf<Long, Job>()
    private val cancelledIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val nextId = AtomicLong()

    /** Completed/failed jobs since the last drain notification (reset on notify). */
    private var batchCompleted = 0
    private var batchFailed = 0

    @Volatile
    private var paused = false

    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val concurrencyListener: (String) -> Unit = { key ->
        if (key == AppPreferences.KEY_DOWNLOAD_CONCURRENCY) {
            scope.launch { mutex.withLock { pumpLocked() } }
        }
    }

    init {
        // Live concurrency changes (Settings slider) pump the queue: raising the
        // limit starts more workers without waiting for the next completion.
        AppPreferences.addListener(concurrencyListener)
    }

    /**
     * Unregisters the [AppPreferences] listener. Tests call this in `@After`
     * so queue instances don't leak between tests; the process-wide [shared]
     * instance intentionally never closes.
     */
    fun close() {
        AppPreferences.removeListener(concurrencyListener)
    }

    // ── Enqueue ─────────────────────────────────────────────────────────

    /**
     * Adds a chapter to the queue. Dedupe: a job with the same
     * (sourceId, mangaUrl, chapterUrl) in a non-terminal state (QUEUED/RUNNING)
     * is skipped (returns false). Enqueueing over a FAILED job requeues that
     * entry (attempts reset) instead of piling up duplicates. Completed
     * chapters may be re-enqueued (files already on disk are skipped).
     */
    suspend fun enqueue(
        source: Source,
        mangaUrl: String,
        chapter: SChapter,
        mangaTitle: String,
        thumbnailUrl: String? = null,
    ): Boolean = mutex.withLock { enqueueLocked(source, mangaUrl, chapter, mangaTitle, thumbnailUrl) }

    /** Bulk [enqueue]; returns how many were actually added/requeued. */
    suspend fun enqueueAll(
        source: Source,
        mangaUrl: String,
        chapters: List<SChapter>,
        mangaTitle: String,
        thumbnailUrl: String? = null,
    ): Int = mutex.withLock {
        var added = 0
        for (chapter in chapters) {
            if (enqueueLocked(source, mangaUrl, chapter, mangaTitle, thumbnailUrl)) added++
        }
        added
    }

    private fun enqueueLocked(
        source: Source,
        mangaUrl: String,
        chapter: SChapter,
        mangaTitle: String,
        thumbnailUrl: String?,
    ): Boolean {
        val existingIdx = jobList.indexOfFirst { job ->
            job.sourceId == source.id && job.mangaUrl == mangaUrl && job.chapterUrl == chapter.url
        }
        if (existingIdx >= 0) {
            val existing = jobList[existingIdx]
            when (existing.state) {
                DownloadJobState.QUEUED, DownloadJobState.RUNNING -> return false
                DownloadJobState.FAILED -> {
                    // Requeue the failed entry rather than duplicating it.
                    jobList[existingIdx] = existing.copy(
                        state = DownloadJobState.QUEUED,
                        attempts = 0,
                        error = null,
                        pagesDone = 0,
                        pagesTotal = 0,
                    )
                    payloads[existing.id] = JobPayload(source, mangaUrl, chapter)
                    pumpLocked()
                    return true
                }
                DownloadJobState.COMPLETED, DownloadJobState.CANCELLED -> Unit // fall through: new entry
            }
        }
        val job = DownloadJob(
            id = nextId.incrementAndGet(),
            sourceId = source.id,
            mangaUrl = mangaUrl,
            chapterUrl = chapter.url,
            chapterName = chapter.name,
            mangaTitle = mangaTitle,
            thumbnailUrl = thumbnailUrl,
        )
        jobList += job
        payloads[job.id] = JobPayload(source, mangaUrl, chapter)
        pumpLocked()
        return true
    }

    // ── Lifecycle operations ────────────────────────────────────────────

    /**
     * Stops picking new jobs and cancels running workers (cooperative: the
     * in-flight page's OkHttp call is cancelled; pages already on disk are
     * kept). Running jobs return to QUEUED; [resume] restarts them.
     */
    suspend fun pause() {
        val running: List<Job> = mutex.withLock {
            if (paused) return
            paused = true
            _isPaused.value = true
            emitLocked()
            workerJobs.values.toList()
        }
        // Cancel outside the lock; workers flip themselves back to QUEUED.
        running.forEach { it.cancel() }
    }

    /** Lifts a [pause]: starts pumping queued jobs again. */
    suspend fun resume() {
        mutex.withLock {
            if (!paused) return
            paused = false
            _isPaused.value = false
            pumpLocked()
        }
    }

    /**
     * Cancels one job. QUEUED and FAILED jobs are removed immediately; RUNNING
     * jobs are cancelled at the next cooperative point and then removed.
     * Returns false when the job is unknown or already terminal.
     */
    suspend fun cancel(id: Long): Boolean = mutex.withLock {
        val idx = jobList.indexOfFirst { it.id == id }
        if (idx < 0) return false
        when (jobList[idx].state) {
            DownloadJobState.QUEUED, DownloadJobState.FAILED -> {
                jobList.removeAt(idx)
                payloads.remove(id)
                emitLocked()
                true
            }
            DownloadJobState.RUNNING -> {
                cancelledIds += id
                workerJobs[id]?.cancel() // worker removes the entry on exit
                true
            }
            else -> false // COMPLETED: use clearCompleted()
        }
    }

    suspend fun cancel(job: DownloadJob): Boolean = cancel(job.id)

    /** Cancels every job (queued removed now, running cancelled cooperatively). */
    suspend fun cancelAll() {
        val running: List<Job> = mutex.withLock {
            val queuedIds = jobList.filter { it.state == DownloadJobState.QUEUED }.map { it.id }
            jobList.removeAll { it.state == DownloadJobState.QUEUED }
            queuedIds.forEach { payloads.remove(it) }
            val toCancel = jobList.filter { it.state == DownloadJobState.RUNNING }
            cancelledIds.addAll(toCancel.map { it.id })
            // The user just threw this batch away: reset its counters so the
            // next drained batch's summary isn't inflated by it.
            batchCompleted = 0
            batchFailed = 0
            emitLocked()
            toCancel.mapNotNull { workerJobs[it.id] }
        }
        running.forEach { it.cancel() }
    }

    /** FAILED -> QUEUED with attempts reset; the job runs again from the top. */
    suspend fun retry(id: Long): Boolean = mutex.withLock {
        val idx = jobList.indexOfFirst { it.id == id }
        if (idx < 0 || jobList[idx].state != DownloadJobState.FAILED) return false
        jobList[idx] = jobList[idx].copy(
            state = DownloadJobState.QUEUED,
            attempts = 0,
            error = null,
            pagesDone = 0,
            pagesTotal = 0,
        )
        pumpLocked()
        true
    }

    suspend fun retry(job: DownloadJob): Boolean = retry(job.id)

    /** Drops COMPLETED entries from the list (disk files + DB rows are kept). */
    suspend fun clearCompleted() {
        mutex.withLock {
            jobList.removeAll { it.state == DownloadJobState.COMPLETED }
            // Same reasoning as cancelAll: the cleared batch must not bleed
            // into the next drained batch's summary.
            batchCompleted = 0
            batchFailed = 0
            emitLocked()
        }
    }

    // ── Worker engine ───────────────────────────────────────────────────

    /**
     * Launches QUEUED jobs while running count is below the concurrency limit. Mutex held.
     *
     * ATOMIC launch is deliberate (delicate API): we rely on its documented
     * guarantee that the coroutine body starts even when cancelled before
     * dispatch, running to the first suspension point -- that's what keeps
     * pause/cancel cleanup reachable. See the launch site comment.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun pumpLocked() {
        // Paused skips worker pickup but still emits: an enqueue while paused
        // must publish its QUEUED row to [jobs] immediately.
        if (!paused) {
            var slots = concurrencyProvider() - jobList.count { it.state == DownloadJobState.RUNNING }
            for (i in jobList.indices) {
                if (slots <= 0) break
                val job = jobList[i]
                if (job.state != DownloadJobState.QUEUED) continue
                jobList[i] = job.copy(state = DownloadJobState.RUNNING, error = null, pagesDone = 0, pagesTotal = 0)
                // ATOMIC: the worker MUST start executing even if pause/cancel
                // lands between this launch and the coroutine's first dispatch.
                // With the default start mode, cancelling a still-New coroutine
                // skips the body entirely -- no catch/finally cleanup, so the
                // job would be stuck RUNNING forever (and a paused queue would
                // never requeue it). ATOMIC runs the body to the first
                // suspension point, where cancellation throws into
                // executeJob's cleanup paths.
                workerJobs[job.id] = scope.launch(start = CoroutineStart.ATOMIC) { executeJob(job.id) }
                slots--
            }
        }
        emitLocked()
    }

    /** Runs one job through the attempt/retry loop. Launched by [pumpLocked]. */
    private suspend fun executeJob(id: Long) {
        // This worker's own coroutine Job. Every workerJobs removal is guarded
        // by identity against it: a pause -> resume (or cancel -> retry) can
        // hand this id to a NEW worker while THIS coroutine is still
        // unwinding, and a stale worker's cleanup must never delete the new
        // worker's registration -- that would leave the job unpausable and
        // uncancellable (stuck RUNNING until it finishes on its own).
        val self = coroutineContext[Job]
        var attempts = 0
        try {
            while (true) {
                attempts++
                mutex.withLock {
                    updateJobLocked(id) { it.copy(attempts = attempts, error = null, pagesDone = 0, pagesTotal = 0) }
                }
                val payload = payloads[id] ?: return // cancelled: entry already removed
                try {
                    val pages = executor.download(payload.source, payload.mangaUrl, payload.chapter) { done, total ->
                        mutex.withLock { updateJobLocked(id) { it.copy(pagesDone = done, pagesTotal = total) } }
                    }
                    finish(id, self) {
                        it.copy(state = DownloadJobState.COMPLETED, pagesDone = pages, pagesTotal = pages)
                    }
                    return
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    // Throwable, not Exception: a broken extension jar throws
                    // NoClassDefFoundError at class-load, and an uncaught Error
                    // used to escape the retry loop entirely, leaving the job
                    // stuck RUNNING forever. Errors are deterministic failures
                    // (reloading the same jar will not help): fail now, no
                    // retry, no backoff.
                    if (t is Error || attempts >= MAX_ATTEMPTS) {
                        finish(id, self) { it.copy(state = DownloadJobState.FAILED, error = t.message ?: t.toString()) }
                        return
                    }
                    backoffSleeper(backoffDelayMs(attempts))
                }
            }
        } catch (ce: CancellationException) {
            // Pause or explicit cancel. Cleanup must run even though we're cancelled.
            withContext(NonCancellable) {
                mutex.withLock {
                    // Only the current owner of the id may mutate it. In the
                    // pause->resume race the job may already have been
                    // relaunched by a newer worker; flipping that worker's
                    // RUNNING state back to QUEUED (or dropping its payload)
                    // would corrupt the queue.
                    if (workerJobs[id] === self) {
                        workerJobs.remove(id)
                        if (cancelledIds.remove(id)) {
                            payloads.remove(id)
                            removeJobLocked(id)
                        } else {
                            // Paused (or scope shutdown): back to QUEUED, payload kept
                            // so resume can re-run the job.
                            updateJobLocked(id) { it.copy(state = DownloadJobState.QUEUED) }
                        }
                    }
                }
            }
            throw ce
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    // Registration may already belong to a newer worker for
                    // this id (pause -> resume relaunched it while we were
                    // unwinding): remove only our own.
                    if (workerJobs[id] === self) {
                        workerJobs.remove(id)
                    }
                    pumpLocked()
                }
            }
        }
    }

    /**
     * Terminal transition (COMPLETED/FAILED) + drain bookkeeping. Cancels win
     * races. Despite the historical `Locked` suffix this acquires [mutex]
     * itself; [self] is the calling worker's [Job] so the workerJobs entry is
     * only dropped when it still belongs to that worker.
     */
    private suspend fun finish(id: Long, self: Job?, transform: (DownloadJob) -> DownloadJob) {
        val drain: Pair<Int, Int>? = mutex.withLock {
            if (workerJobs[id] === self) workerJobs.remove(id)
            if (cancelledIds.remove(id)) {
                payloads.remove(id)
                removeJobLocked(id)
            } else {
                val idx = jobList.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    val updated = transform(jobList[idx])
                    jobList[idx] = updated
                    when (updated.state) {
                        DownloadJobState.COMPLETED -> {
                            batchCompleted++
                            payloads.remove(id)
                        }
                        DownloadJobState.FAILED -> {
                            batchFailed++
                            // Payload kept so retry()/re-enqueue can re-run the job.
                        }
                        else -> Unit
                    }
                }
            }
            maybeNotifyDrainLocked()
        }
        // Fire outside the lock: a slow/blocking listener must not stall the
        // queue, and a throwing listener must not masquerade as a download
        // failure inside executeJob's retry loop.
        if (drain != null) {
            runCatching { notifier.onDrained(drain.first, drain.second) }
        }
    }

    /**
     * Returns the pending drain summary (resetting the batch counters) when
     * every job is terminal and there is something to report, null otherwise.
     * Called only from COMPLETED/FAILED transitions -- pause and cancel never
     * notify (the user caused them and knows).
     */
    private fun maybeNotifyDrainLocked(): Pair<Int, Int>? {
        val active = jobList.any { it.state == DownloadJobState.QUEUED || it.state == DownloadJobState.RUNNING }
        if (active) return null
        if (batchCompleted <= 0 && batchFailed <= 0) return null
        val summary = batchCompleted to batchFailed
        batchCompleted = 0
        batchFailed = 0
        return summary
    }

    /** 1s, 4s, 16s, ... Only the first MAX_ATTEMPTS-1 delays are ever used. */
    private fun backoffDelayMs(failedAttempts: Int): Long = 1000L * (1L shl (2 * (failedAttempts - 1)))

    // ── State helpers (mutex held) ──────────────────────────────────────

    private fun updateJobLocked(id: Long, transform: (DownloadJob) -> DownloadJob) {
        val idx = jobList.indexOfFirst { it.id == id }
        if (idx >= 0) {
            jobList[idx] = transform(jobList[idx])
            emitLocked()
        }
    }

    private fun removeJobLocked(id: Long) {
        jobList.removeAll { it.id == id }
        emitLocked()
    }

    private fun emitLocked() {
        _jobs.value = jobList.toList()
    }

    companion object {
        const val MAX_ATTEMPTS = 3

        /** Process-wide queue shared by all screens (enqueue here, watch in Downloads). */
        val shared: DownloadQueue by lazy { DownloadQueue() }
    }
}
