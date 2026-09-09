package mihon.desktop.loader.download

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mihon.desktop.loader.library.NotificationManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises [DownloadQueue] against a fake [ChapterDownloadExecutor] (no
 * network, no disk): FIFO order, concurrency limit, dedupe, pause/resume,
 * cancel, retry with (faked) backoff, and the once-per-drain notification.
 *
 * Backoff is never slept: the queue's sleeper seam records delays instead.
 * Timing-sensitive steps use gated downloads (CompletableDeferred) so the
 * tests are deterministic rather than sleep-based.
 */
class DownloadQueueTest {

    // ── Fakes & helpers ──────────────────────────────────────────────────

    private class FakeSource(override val id: Long) : Source {
        override val name = "Fake Source"
        override val lang = "en"
        override val supportsLatest = false

        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) =
            MangasPage(emptyList(), false)

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ) = SMangaUpdate(manga, chapters)

        override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
    }

    /**
     * Fake chapter downloader. Per chapter: fails the first [failFirstN]
     * calls with IOException, optionally blocks on a gate before the page
     * loop, then emits [pagesPerChapter] progress ticks. Tracks concurrent
     * executions for the concurrency-limit test.
     */
    private class FakeExecutor : ChapterDownloadExecutor {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val events = CopyOnWriteArrayList<String>() // "start:url:attempt" / "end:url:attempt"
        val callsPerChapter = ConcurrentHashMap<String, Int>()
        val failFirstN = ConcurrentHashMap<String, Int>()
        val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        var pagesPerChapter = 3
        var pageDelayMs = 0L

        override suspend fun download(
            source: Source,
            mangaUrl: String,
            chapter: SChapter,
            onProgress: suspend (pagesDone: Int, pagesTotal: Int) -> Unit,
        ): Int {
            val attempt = callsPerChapter.merge(chapter.url, 1, Int::plus)!!
            active.incrementAndGet()
            maxActive.accumulateAndGet(active.get()) { a, b -> if (a >= b) a else b }
            try {
                events += "start:${chapter.url}:$attempt"
                if (attempt <= (failFirstN[chapter.url] ?: 0)) throw IOException("fake failure $attempt")
                gates[chapter.url]?.await() // cancellable: pause/cancel interrupts here
                for (i in 1..pagesPerChapter) {
                    if (pageDelayMs > 0) delay(pageDelayMs)
                    onProgress(i, pagesPerChapter)
                }
                events += "end:${chapter.url}:$attempt"
                return pagesPerChapter
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private val source = FakeSource(1L)

    private val notifications = CopyOnWriteArrayList<Pair<Int, Int>>()
    private val sleeperDelays = CopyOnWriteArrayList<Long>()

    /** Every queue created by a test, closed in [tearDown] to drop pref listeners. */
    private val queues = CopyOnWriteArrayList<DownloadQueue>()

    @After
    fun tearDown() {
        queues.forEach { it.close() }
        queues.clear()
    }

    private fun chapter(url: String, name: String = "Chapter $url") = SChapter.create().apply {
        this.url = url
        this.name = name
    }

    private fun queue(executor: ChapterDownloadExecutor, concurrency: Int = 2): DownloadQueue =
        queueWithProvider(executor) { concurrency }

    private fun queueWithProvider(executor: ChapterDownloadExecutor, concurrencyProvider: () -> Int): DownloadQueue =
        DownloadQueue(
            executor = executor,
            notifier = { completed, failed -> notifications += completed to failed },
            backoffSleeper = { millis -> sleeperDelays += millis }, // never sleeps
            concurrencyProvider = concurrencyProvider,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ).also { queues += it }

    /** Polls [condition] until true or fails with [message] after [timeoutMs]. */
    private suspend fun awaitCondition(
        message: String,
        timeoutMs: Long = 5_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                org.junit.Assert.fail("timeout waiting for: $message")
            }
            delay(20)
        }
    }

    private fun DownloadQueue.job(url: String): DownloadJob? =
        jobs.value.firstOrNull { it.chapterUrl == url }

    // ── FIFO + concurrency ───────────────────────────────────────────────

    @Test
    fun `jobs start FIFO with two workers and queued jobs keep order`() = runBlocking {
        val executor = FakeExecutor()
        val q = queue(executor, concurrency = 2)
        // Gate the first two chapters so nothing completes before assertions.
        executor.gates["c1"] = CompletableDeferred()
        executor.gates["c2"] = CompletableDeferred()

        val added = q.enqueueAll(source, "m1", listOf("c1", "c2", "c3", "c4").map { chapter(it) }, "Title")
        assertEquals(4, added)

        // Both workers pick the first two jobs (in list order), rest stay queued.
        awaitCondition("two jobs running") { q.jobs.value.count { it.state == DownloadJobState.RUNNING } == 2 }
        assertEquals(
            setOf("c1", "c2"),
            q.jobs.value.filter { it.state == DownloadJobState.RUNNING }.map { it.chapterUrl }.toSet(),
        )
        assertEquals(
            listOf("c3", "c4"),
            q.jobs.value.filter { it.state == DownloadJobState.QUEUED }.map { it.chapterUrl },
        )

        executor.gates["c1"]?.complete(Unit)
        executor.gates["c2"]?.complete(Unit)
        awaitCondition("all completed") { q.jobs.value.all { it.state == DownloadJobState.COMPLETED } }
        assertEquals(4, q.jobs.value.count { it.state == DownloadJobState.COMPLETED })
    }

    @Test
    fun `single worker runs jobs strictly in enqueue order`() = runBlocking {
        val executor = FakeExecutor().apply { pageDelayMs = 10 }
        val q = queue(executor, concurrency = 1)
        val urls = listOf("c1", "c2", "c3", "c4", "c5")

        q.enqueueAll(source, "m1", urls.map { chapter(it) }, "Title")

        awaitCondition("all completed") { q.jobs.value.all { it.state == DownloadJobState.COMPLETED } }
        val completionOrder = executor.events
            .filter { it.startsWith("end:") }
            .map { it.removePrefix("end:").substringBefore(':') }
        assertEquals(urls, completionOrder)
    }

    @Test
    fun `concurrency limit is respected and all jobs finish`() = runBlocking {
        val executor = FakeExecutor().apply { pageDelayMs = 30 }
        val q = queue(executor, concurrency = 2)
        val urls = (1..6).map { "c$it" }

        q.enqueueAll(source, "m1", urls.map { chapter(it) }, "Title")

        awaitCondition("all completed", timeoutMs = 10_000) {
            q.jobs.value.all { it.state == DownloadJobState.COMPLETED }
        }
        assertTrue("max simultaneous was ${executor.maxActive.get()}", executor.maxActive.get() <= 2)
        assertEquals(6, q.jobs.value.count { it.state == DownloadJobState.COMPLETED })
    }

    // ── Dedupe ───────────────────────────────────────────────────────────

    @Test
    fun `enqueue dedupes same chapter while queued or running`() = runBlocking {
        val executor = FakeExecutor()
        val q = queue(executor, concurrency = 1)
        val ch = chapter("c1")
        executor.gates["c1"] = CompletableDeferred()

        assertTrue(q.enqueue(source, "m1", ch, "Title"))
        assertFalse("duplicate while queued", q.enqueue(source, "m1", ch, "Title"))

        awaitCondition("running") { q.job("c1")?.state == DownloadJobState.RUNNING }
        assertFalse("duplicate while running", q.enqueue(source, "m1", ch, "Title"))

        executor.gates["c1"]?.complete(Unit)
        awaitCondition("completed") { q.job("c1")?.state == DownloadJobState.COMPLETED }
        assertEquals(1, q.jobs.value.size)

        // Completed is terminal: re-enqueue is allowed (re-download).
        assertTrue(q.enqueue(source, "m1", ch, "Title"))
        awaitCondition("second completion") { executor.callsPerChapter["c1"] == 2 }
        assertEquals(2, q.jobs.value.count { it.chapterUrl == "c1" })
    }

    // ── Pause / resume ───────────────────────────────────────────────────

    @Test
    fun `pause requeues running jobs and stops pickups, resume continues`() = runBlocking {
        val executor = FakeExecutor()
        val q = queue(executor, concurrency = 2)
        executor.gates["c1"] = CompletableDeferred()
        executor.gates["c2"] = CompletableDeferred()

        q.enqueueAll(source, "m1", listOf("c1", "c2").map { chapter(it) }, "Title")
        awaitCondition("two running") { q.jobs.value.count { it.state == DownloadJobState.RUNNING } == 2 }

        q.pause()
        assertTrue(q.isPaused.value)
        // Running jobs are cancelled (blocked on the gate) and return to QUEUED.
        awaitCondition("both back to queued") {
            q.jobs.value.all { it.state == DownloadJobState.QUEUED }
        }
        assertTrue("no page loop ran", executor.events.none { it.startsWith("end:") })

        // While paused, new enqueues never start.
        q.enqueue(source, "m1", chapter("c3"), "Title")
        delay(200)
        assertTrue("no job running while paused", q.jobs.value.none { it.state == DownloadJobState.RUNNING })

        q.resume()
        assertFalse(q.isPaused.value)
        executor.gates["c1"]?.complete(Unit)
        executor.gates["c2"]?.complete(Unit)
        awaitCondition("all completed after resume") {
            q.jobs.value.all { it.state == DownloadJobState.COMPLETED }
        }
        assertEquals(3, q.jobs.value.count { it.state == DownloadJobState.COMPLETED })
    }

    @Test
    fun `enqueue while paused stays queued and resume starts it`() = runBlocking {
        val executor = FakeExecutor()
        val q = queue(executor, concurrency = 2)

        q.pause()
        assertTrue("enqueue accepted while paused", q.enqueue(source, "m1", chapter("c1"), "Title"))
        delay(200)
        assertEquals("stays QUEUED, no worker start", DownloadJobState.QUEUED, q.job("c1")?.state)
        assertTrue("executor untouched while paused", executor.events.isEmpty())

        q.resume()
        awaitCondition("runs to completion after resume") {
            q.job("c1")?.state == DownloadJobState.COMPLETED
        }
        assertEquals(1, executor.callsPerChapter["c1"])
    }

    @Test
    fun `pause resume cancel cycle keeps the relaunched workers cancellable`() = runBlocking {
        // Regression for the workerJobs clobber: a worker cancelled by pause()
        // unwinds through a cancellation handler and a finally that both drop
        // its workerJobs entry; when resume() relaunches the job in between,
        // the old worker's finally used to delete the NEW worker's
        // registration -- after which no pause()/cancel() could ever reach
        // the running job again. Hammering pause/resume over gated jobs
        // crosses that interleaving; the final pause must then be able to
        // flip EVERY job back to QUEUED (a clobbered worker sits parked on
        // its gate, uncancellable, stuck RUNNING).
        val executor = FakeExecutor()
        val q = queue(executor, concurrency = 4)
        val urls = listOf("c1", "c2", "c3", "c4")
        urls.forEach { executor.gates[it] = CompletableDeferred() }

        q.enqueueAll(source, "m1", urls.map { chapter(it) }, "Title")
        awaitCondition("all four running") { q.jobs.value.all { it.state == DownloadJobState.RUNNING } }

        // No awaits inside: each resume races the cancelled workers'
        // cancellation handlers on the mutex, which is exactly the window the
        // clobber lives in.
        repeat(300) {
            q.pause()
            q.resume()
        }

        q.pause()
        awaitCondition("every job paused back to queued") {
            q.jobs.value.all { it.state == DownloadJobState.QUEUED }
        }

        q.resume()
        urls.forEach { executor.gates[it]?.complete(Unit) }
        awaitCondition("all completed after resume") { q.jobs.value.all { it.state == DownloadJobState.COMPLETED } }
    }

    // ── Cancel ───────────────────────────────────────────────────────────

    @Test
    fun `cancel removes queued and running jobs`() = runBlocking {
        val executor = FakeExecutor()
        val q = queue(executor, concurrency = 1)
        executor.gates["c1"] = CompletableDeferred()

        q.enqueueAll(source, "m1", listOf("c1", "c2", "c3").map { chapter(it) }, "Title")
        awaitCondition("c1 running") { q.job("c1")?.state == DownloadJobState.RUNNING }

        // Cancel a QUEUED job: gone immediately, never downloaded.
        assertTrue(q.cancel(q.job("c2")!!))
        assertTrue("c2 removed", q.jobs.value.none { it.chapterUrl == "c2" })

        // Cancel the RUNNING job (blocked on its gate): removed, c3 takes over.
        assertTrue(q.cancel(q.job("c1")!!))
        awaitCondition("c1 removed") { q.job("c1") == null }
        assertTrue("c1 never completed", executor.events.none { it.startsWith("end:c1") })

        awaitCondition("c3 completed") { q.job("c3")?.state == DownloadJobState.COMPLETED }
        assertEquals(listOf("c3"), q.jobs.value.map { it.chapterUrl })
    }

    @Test
    fun `cancelAll drains the queue and fires no notification`() = runBlocking {
        val executor = FakeExecutor()
        val q = queue(executor, concurrency = 2)
        executor.gates["c1"] = CompletableDeferred()
        executor.gates["c2"] = CompletableDeferred()

        q.enqueueAll(source, "m1", listOf("c1", "c2", "c3").map { chapter(it) }, "Title")
        awaitCondition("two running") { q.jobs.value.count { it.state == DownloadJobState.RUNNING } == 2 }

        q.cancelAll()
        awaitCondition("queue drained") { q.jobs.value.isEmpty() }
        delay(200) // give any (wrong) notification a chance to land
        assertTrue("cancel must not notify", notifications.isEmpty())
    }

    @Test
    fun `cancel removes a failed job from the list`() = runBlocking {
        val executor = FakeExecutor().apply { failFirstN["c1"] = 3 }
        val q = queue(executor, concurrency = 1)
        q.enqueue(source, "m1", chapter("c1"), "Title")
        awaitCondition("failed after retries") { q.job("c1")?.state == DownloadJobState.FAILED }

        assertTrue("FAILED job is cancellable", q.cancel(q.job("c1")!!))
        assertTrue("failed row gone from jobs flow", q.jobs.value.none { it.chapterUrl == "c1" })
        // Unknown ids (e.g. cancelling the removed row twice) stay a no-op.
        assertFalse(q.cancel(q.job("c1")?.id ?: -1L))
    }

    @Test
    fun `cancelAll resets batch counters so the next drain reports only the new batch`() = runBlocking {
        val executor = FakeExecutor()
        val q = queue(executor, concurrency = 1)
        executor.gates["c1"] = CompletableDeferred()
        executor.gates["c2"] = CompletableDeferred()

        // Batch 1: c1 completes, c2 still running when the user cancels all.
        q.enqueueAll(source, "m1", listOf("c1", "c2", "c3").map { chapter(it) }, "Title")
        awaitCondition("c1 running") { q.job("c1")?.state == DownloadJobState.RUNNING }
        executor.gates["c1"]?.complete(Unit)
        awaitCondition("c1 done, c2 running") {
            q.job("c1")?.state == DownloadJobState.COMPLETED && q.job("c2")?.state == DownloadJobState.RUNNING
        }

        q.cancelAll()
        // COMPLETED entries stay until clearCompleted; "drained" means no
        // QUEUED/RUNNING job is left.
        awaitCondition("no active jobs left") {
            q.jobs.value.none { it.state == DownloadJobState.QUEUED || it.state == DownloadJobState.RUNNING }
        }
        delay(200) // give any (wrong) notification a chance to land
        assertTrue("cancel must not notify", notifications.isEmpty())

        // Batch 2's summary must count only batch 2 (2), not 1 + batch 2 (3).
        q.enqueueAll(source, "m1", listOf("c4", "c5").map { chapter(it) }, "Title")
        awaitCondition("second batch drained") { notifications.size == 1 }
        assertEquals(2 to 0, notifications.single())
    }

    // ── Retry / failure ──────────────────────────────────────────────────

    @Test
    fun `failing chapter retries three times with backoff then fails`() = runBlocking {
        val executor = FakeExecutor().apply { failFirstN["c1"] = 3 }
        val q = queue(executor, concurrency = 1)

        q.enqueue(source, "m1", chapter("c1"), "Title")

        awaitCondition("failed after 3 attempts") { q.job("c1")?.state == DownloadJobState.FAILED }
        assertEquals(3, executor.callsPerChapter["c1"])
        // Backoff 1s then 4s between the three attempts; no 16s sleep needed.
        assertEquals(listOf(1000L, 4000L), sleeperDelays.toList())
        assertTrue(q.job("c1")!!.error!!.contains("fake failure"))
    }

    @Test
    fun `error-throwing executor fails the job immediately and the queue moves on`() = runBlocking {
        // NoClassDefFoundError from a broken extension jar is an Error, not an
        // Exception: the old catch (e: Exception) let it escape the retry loop
        // entirely, leaving the job stuck RUNNING forever. It must FAIL on the
        // first attempt -- no retry, no backoff -- and the queue must keep
        // serving the jobs behind it.
        val executor = ChapterDownloadExecutor { _, _, chapter, _ ->
            if (chapter.url == "bad") throw NoClassDefFoundError("broken extension jar")
            1 // single page, completes
        }
        val q = queue(executor, concurrency = 1)

        q.enqueueAll(source, "m1", listOf("bad", "good").map { chapter(it) }, "Title")

        awaitCondition("bad FAILED without retry") { q.job("bad")?.state == DownloadJobState.FAILED }
        awaitCondition("queue continues with the next job") {
            q.job("good")?.state == DownloadJobState.COMPLETED
        }
        assertEquals("Errors must not be retried", 1, q.job("bad")!!.attempts)
        assertTrue("no backoff sleep for Errors", sleeperDelays.isEmpty())
        assertTrue(q.job("bad")!!.error!!.contains("broken extension jar"))
        // One drain summary for the mixed batch: 1 completed, 1 failed.
        assertEquals(1 to 1, notifications.single())
    }

    @Test
    fun `retry requeues a failed job and it completes`() = runBlocking {
        val executor = FakeExecutor().apply { failFirstN["c1"] = 3 }
        val q = queue(executor, concurrency = 1)
        q.enqueue(source, "m1", chapter("c1"), "Title")
        awaitCondition("failed") { q.job("c1")?.state == DownloadJobState.FAILED }

        assertTrue(q.retry(q.job("c1")!!))
        awaitCondition("completed after retry") { q.job("c1")?.state == DownloadJobState.COMPLETED }
        assertEquals(4, executor.callsPerChapter["c1"]) // 3 failed + 1 successful
        assertEquals(1, q.job("c1")!!.attempts) // counter reset by retry, not carried from 3
    }

    @Test
    fun `enqueue over a failed job requeues that entry instead of duplicating`() = runBlocking {
        val executor = FakeExecutor().apply { failFirstN["c1"] = 3 }
        val q = queue(executor, concurrency = 1)
        q.enqueue(source, "m1", chapter("c1"), "Title")
        awaitCondition("failed") { q.job("c1")?.state == DownloadJobState.FAILED }

        assertTrue(q.enqueue(source, "m1", chapter("c1"), "Title"))
        awaitCondition("completed") { q.job("c1")?.state == DownloadJobState.COMPLETED }
        // One entry for the key, executed 4 times total.
        assertEquals(1, q.jobs.value.count { it.chapterUrl == "c1" })
        assertEquals(4, executor.callsPerChapter["c1"])
    }

    // ── Drain notification ───────────────────────────────────────────────

    @Test
    fun `drain notification fires once per batch with completion count`() = runBlocking {
        val executor = FakeExecutor()
        val q = queue(executor, concurrency = 2)

        q.enqueueAll(source, "m1", listOf("c1", "c2", "c3").map { chapter(it) }, "Title")
        awaitCondition("first drain") { notifications.size == 1 }
        assertEquals(3 to 0, notifications.single())

        // A second batch produces exactly one more summary.
        q.enqueueAll(source, "m1", listOf("c4", "c5").map { chapter(it) }, "Title")
        awaitCondition("second drain") { notifications.size == 2 }
        assertEquals(2 to 0, notifications[1])
    }

    @Test
    fun `drain notification reports failures alongside completions`() = runBlocking {
        val executor = FakeExecutor().apply { failFirstN["c1"] = 3 } // exhausts retries
        val q = queue(executor, concurrency = 2)

        q.enqueueAll(source, "m1", listOf("c1", "c2").map { chapter(it) }, "Title")
        awaitCondition("drain with failure") { notifications.size == 1 }
        assertEquals(1 to 1, notifications.single())
    }

    @Test
    fun `default drain notifier posts message keys with raw counts`() {
        // The default notifier goes through the real NotificationManager
        // (key-based path); hermetic: in-app only, cleared afterwards.
        NotificationManager.setSystemNotifier(null)
        NotificationManager.clear()
        try {
            val notifier = NotificationDrainNotifier()
            notifier.onDrained(3, 0)
            notifier.onDrained(1, 0)
            notifier.onDrained(2, 1)
            notifier.onDrained(1, 2)

            val posted = NotificationManager.notifications
            assertTrue(posted.all { it.title == "notif_downloads_complete_title" })
            assertEquals(
                listOf(
                    "notif_downloads_complete_one_failed" to listOf(1, 2),
                    "notif_downloads_complete_many_failed" to listOf(2, 1),
                    "notif_downloads_complete_one" to listOf(1, 0),
                    "notif_downloads_complete_many" to listOf(3, 0),
                ),
                posted.map { it.message to it.messageArgs },
            )
        } finally {
            NotificationManager.clear()
            // setSystemNotifier(null) above is process-global; restore the
            // default OS-delivery seam so later tests in this JVM (and any
            // tray on the dev machine) are not polluted by this test.
            NotificationManager.resetForTest()
        }
        assertNotNull("system notifier must be restored after the test", NotificationManager.currentSystemNotifier())
    }

    // ── Progress ─────────────────────────────────────────────────────────

    @Test
    fun `running job exposes per-page progress`() = runBlocking {
        val executor = FakeExecutor().apply { pagesPerChapter = 4; pageDelayMs = 30 }
        val q = queue(executor, concurrency = 1)

        q.enqueue(source, "m1", chapter("c1"), "Title")

        awaitCondition("progress seen") {
            val job = q.job("c1")
            job?.state == DownloadJobState.RUNNING && job.pagesTotal == 4 && job.pagesDone in 1..4
        }
        awaitCondition("completed") { q.job("c1")?.state == DownloadJobState.COMPLETED }
        assertEquals(4, q.job("c1")!!.pagesDone)
        assertEquals(4, q.job("c1")!!.pagesTotal)
    }

    // ── Completed handling ───────────────────────────────────────────────

    @Test
    fun `clearCompleted drops finished entries but keeps failed ones`() = runBlocking {
        val executor = FakeExecutor().apply { failFirstN["bad"] = 3 }
        val q = queue(executor, concurrency = 2)

        q.enqueueAll(source, "m1", listOf("ok", "bad").map { chapter(it) }, "Title")
        awaitCondition("one done one failed") {
            q.job("ok")?.state == DownloadJobState.COMPLETED && q.job("bad")?.state == DownloadJobState.FAILED
        }

        q.clearCompleted()
        assertEquals(listOf("bad"), q.jobs.value.map { it.chapterUrl })
    }

    // ── Live concurrency ─────────────────────────────────────────────────

    @Test
    fun `raising concurrency mid-flight picks up queued workers`() = runBlocking {
        var concurrency = 2
        val executor = FakeExecutor()
        val q = queueWithProvider(executor) { concurrency }
        listOf("c1", "c2", "c3", "c4").forEach { executor.gates[it] = CompletableDeferred() }

        q.enqueueAll(source, "m1", listOf("c1", "c2", "c3", "c4").map { chapter(it) }, "Title")
        awaitCondition("two running, two queued") {
            q.jobs.value.count { it.state == DownloadJobState.RUNNING } == 2 &&
                q.jobs.value.count { it.state == DownloadJobState.QUEUED } == 2
        }

        // Raise the limit mid-flight. The next pump (c1 finishing) re-reads it
        // and starts c3 + c4 while c2 is still running.
        concurrency = 4
        executor.gates["c1"]?.complete(Unit)
        awaitCondition("c3 and c4 picked up while c2 still running") {
            q.jobs.value.count { it.state == DownloadJobState.RUNNING } == 3
        }
        assertEquals(
            setOf("c2", "c3", "c4"),
            q.jobs.value.filter { it.state == DownloadJobState.RUNNING }.map { it.chapterUrl }.toSet(),
        )

        executor.gates["c2"]?.complete(Unit)
        executor.gates["c3"]?.complete(Unit)
        executor.gates["c4"]?.complete(Unit)
        awaitCondition("all completed") { q.jobs.value.all { it.state == DownloadJobState.COMPLETED } }
        assertTrue("max concurrent was ${executor.maxActive.get()}", executor.maxActive.get() >= 3)
    }
}
