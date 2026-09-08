package mihon.desktop.loader.library

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mihon.desktop.loader.ExtensionLoader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Periodically checks library manga for new chapters in the background.
 *
 * Uses a plain JVM [ScheduledExecutorService] (no Android WorkManager). The scheduler
 * runs every [intervalMinutes] minutes (default 60). Each run iterates all library
 * entries, re-loads each extension jar from cache, calls `getMangaUpdate` on the source,
 * and records which manga have new chapters via [UpdateListener].
 *
 * Call [start] once at app launch and [stop] on shutdown.
 */
class LibraryUpdateScheduler(
    private val repository: LibraryRepository = LibraryRepository(),
    private val intervalMinutes: Long = 60,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "library-update-scheduler").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)
    private val updating = AtomicBoolean(false)

    /** Manga that have new chapters: key = "sourceId:mangaUrl", value = new chapter count. */
    private val _updates = java.util.concurrent.ConcurrentHashMap<String, Int>()
    val updates: Map<String, Int> get() = _updates.toMap()

    private var listener: UpdateListener? = null

    fun interface UpdateListener {
        fun onUpdatesFound(mangaKey: String, chapterCount: Int)
    }

    fun setUpdateListener(listener: UpdateListener?) {
        this.listener = listener
    }

    /**
     * Start the periodic scheduler. No-op if already running.
     */
    fun start() {
        if (running.compareAndSet(false, true)) {
            executor.scheduleWithFixedDelay(
                ::runUpdate,
                0, // run immediately on start
                intervalMinutes,
                TimeUnit.MINUTES,
            )
        }
    }

    /**
     * Stop the scheduler and cancel any in-progress update.
     */
    fun stop() {
        if (running.compareAndSet(true, false)) {
            executor.shutdownNow()
            scope.cancel()
        }
    }

    /**
     * Trigger an immediate update (e.g. from a manual refresh button).
     */
    fun triggerNow() {
        if (updating.compareAndSet(false, true)) {
            scope.launch(Dispatchers.IO) {
                try {
                    doUpdate()
                } finally {
                    updating.set(false)
                }
            }
        }
    }

    private fun runUpdate() {
        if (updating.compareAndSet(false, true)) {
            scope.launch(Dispatchers.IO) {
                try {
                    doUpdate()
                } finally {
                    updating.set(false)
                }
            }
        }
    }

    private suspend fun doUpdate() {
        val entries = repository.all()
        var updatesFound = 0
        for (entry in entries) {
            val sources = runCatching {
                ExtensionLoader.loadCached(entry.jarFileName).sources
            }.getOrDefault(emptyList())

            val source = sources.firstOrNull { it.id == entry.sourceId } ?: continue
            if (source !is CatalogueSource) continue

            runCatching {
                val manga = eu.kanade.tachiyomi.source.model.SManga.create().apply {
                    url = entry.mangaUrl
                    title = entry.title
                    thumbnail_url = entry.thumbnailUrl
                }
                val update = source.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)
                // recordNewChapters diffs against updateHistory so only chapters
                // we have never seen before are recorded/returned.
                repository.recordNewChapters(
                    sourceId = entry.sourceId,
                    mangaUrl = entry.mangaUrl,
                    mangaTitle = entry.title,
                    thumbnailUrl = entry.thumbnailUrl,
                    packageName = entry.packageName,
                    jarFileName = entry.jarFileName,
                    chapters = update.chapters,
                )
            }.onSuccess { freshChapters ->
                if (freshChapters.isNotEmpty()) {
                    val key = "${entry.sourceId}:${entry.mangaUrl}"
                    _updates[key] = freshChapters.size
                    listener?.onUpdatesFound(key, freshChapters.size)
                    updatesFound++
                }
            }
        }
        if (updatesFound > 0) {
            NotificationManager.notify(
                title = "Library updated",
                message = "$updatesFound manga have new chapters",
            )
        }
    }

    /**
     * Clear the updates map (e.g. after user has seen them).
     */
    fun clearUpdates() {
        _updates.clear()
    }
}
