package mihon.desktop.loader.library

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises [StatsRepository] against a throwaway database: aggregate counts,
 * per-source / per-category breakdowns, the 7/30-day read windows and the
 * uncategorized ("Default") complement.
 */
class StatsRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: MihonDesktopDatabase
    private lateinit var repo: StatsRepository

    @Before
    fun setUp() {
        db = LibraryDatabase.openDatabase(folder.newFile("stats.db"))
        repo = StatsRepository(db)
    }

    private fun addManga(url: String, sourceId: Long = 1L, extensionName: String = "Ext A") {
        db.libraryMangaQueries.insertOrReplace(
            sourceId = sourceId,
            packageName = "pkg",
            jarFileName = "pkg-v1.jar",
            extensionName = extensionName,
            mangaUrl = url,
            title = "Manga $url",
            thumbnailUrl = null,
            author = null,
            addedAt = System.currentTimeMillis(),
        )
    }

    private fun markRead(sourceId: Long, mangaUrl: String, chapterUrl: String, readAt: Long) {
        db.readChapterQueries.insertOrReplace(
            sourceId = sourceId,
            mangaUrl = mangaUrl,
            chapterUrl = chapterUrl,
            chapterName = "Chapter $chapterUrl",
            readAt = readAt,
        )
    }

    private fun addDownload(sourceId: Long, mangaUrl: String, chapterUrl: String) {
        db.downloadedChapterQueries.insertOrReplace(
            sourceId = sourceId,
            mangaUrl = mangaUrl,
            chapterUrl = chapterUrl,
            chapterName = "Chapter $chapterUrl",
            pageCount = 5L,
            downloadedAt = System.currentTimeMillis(),
        )
    }

    @Test
    fun `snapshot returns zeros for an empty library`() = runBlocking {
        val stats = repo.snapshot()

        assertEquals(0L, stats.totalManga)
        assertEquals(0L, stats.totalReadChapters)
        assertEquals(0L, stats.totalDownloadedChapters)
        assertEquals(0L, stats.categoryCount)
        assertEquals(0L, stats.mangaWithNoCategory)
        assertEquals(0L, stats.readChaptersLast7Days)
        assertEquals(0L, stats.readChaptersLast30Days)
        assertEquals(0.0, stats.averageChaptersPerManga, 0.0)
        assertTrue(stats.mangaPerSource.isEmpty())
        assertTrue(stats.readChaptersPerSource.isEmpty())
        assertTrue(stats.mangaPerCategory.isEmpty())
    }

    @Test
    fun `snapshot counts manga, read and downloaded chapters`() = runBlocking {
        addManga("m1")
        addManga("m2")
        addManga("m3", sourceId = 2L, extensionName = "Ext B")
        markRead(1L, "m1", "c1", System.currentTimeMillis())
        markRead(1L, "m1", "c2", System.currentTimeMillis())
        markRead(2L, "m3", "c1", System.currentTimeMillis())
        addDownload(1L, "m1", "c1")
        addDownload(1L, "m1", "c2")

        val stats = repo.snapshot()

        assertEquals(3L, stats.totalManga)
        assertEquals(3L, stats.totalReadChapters)
        assertEquals(2L, stats.totalDownloadedChapters)
        assertEquals(1.0, stats.averageChaptersPerManga, 0.001)
    }

    @Test
    fun `manga per source groups by extension name, largest first`() = runBlocking {
        addManga("m1")
        addManga("m2")
        addManga("m3", sourceId = 2L, extensionName = "Ext B")

        val stats = repo.snapshot()

        assertEquals(listOf("Ext A" to 2L, "Ext B" to 1L), stats.mangaPerSource.map { it.extensionName to it.mangaCount })
    }

    @Test
    fun `read chapters per source attributes reads through the library join`() = runBlocking {
        addManga("m1")
        addManga("m2", sourceId = 2L, extensionName = "Ext B")
        markRead(1L, "m1", "c1", System.currentTimeMillis())
        markRead(1L, "m1", "c2", System.currentTimeMillis())
        markRead(2L, "m2", "c1", System.currentTimeMillis())

        val stats = repo.snapshot()

        assertEquals(
            listOf("Ext A" to 2L, "Ext B" to 1L),
            stats.readChaptersPerSource.map { it.extensionName to it.chapterCount },
        )
    }

    @Test
    fun `recent activity counts only chapters inside the 7 and 30 day windows`() = runBlocking {
        addManga("m1")
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        markRead(1L, "m1", "today", now)
        markRead(1L, "m1", "three-days-ago", now - 3 * day)
        markRead(1L, "m1", "ten-days-ago", now - 10 * day)
        markRead(1L, "m1", "forty-days-ago", now - 40 * day)

        val stats = repo.snapshot()

        assertEquals(4L, stats.totalReadChapters)
        assertEquals(2L, stats.readChaptersLast7Days)
        assertEquals(3L, stats.readChaptersLast30Days)
    }

    @Test
    fun `read window boundaries are inclusive - exactly 7 or 30 days old still counts`() = runBlocking {
        // Fixed clock: wall-clock drift between markRead and snapshot() would
        // otherwise make an exact-boundary assert flaky.
        val fakeNow = 1_726_000_000_000L
        val day = 24L * 60 * 60 * 1000
        val fixedClockRepo = StatsRepository(db) { fakeNow }
        addManga("m1")
        markRead(1L, "m1", "edge-7d", fakeNow - 7 * day)
        markRead(1L, "m1", "edge-30d", fakeNow - 30 * day)
        markRead(1L, "m1", "just-outside-7d", fakeNow - 7 * day - 1)
        markRead(1L, "m1", "just-outside-30d", fakeNow - 30 * day - 1)

        val stats = fixedClockRepo.snapshot()

        assertEquals(1L, stats.readChaptersLast7Days) // edge-7d only
        // 30-day window: edge-7d + just-outside-7d (still recent) + edge-30d;
        // only just-outside-30d falls beyond it.
        assertEquals(3L, stats.readChaptersLast30Days)
    }

    @Test
    fun `category breakdown counts mapped manga and the uncategorized complement`() = runBlocking {
        addManga("m1")
        addManga("m2")
        addManga("m3")
        db.categoryQueries.insert("Reading", 1L)
        db.categoryQueries.insert("Plan to read", 2L)
        val m1 = db.libraryMangaQueries.selectOne(1L, "m1").executeAsOne()
        val m2 = db.libraryMangaQueries.selectOne(1L, "m2").executeAsOne()
        db.mangaCategoryQueries.insertOrIgnore(m1.id, 1L)
        db.mangaCategoryQueries.insertOrIgnore(m2.id, 2L)
        // m3 stays uncategorized.

        val stats = repo.snapshot()

        assertEquals(2L, stats.categoryCount)
        assertEquals(
            listOf("Reading" to 1L, "Plan to read" to 1L),
            stats.mangaPerCategory.map { it.name to it.mangaCount },
        )
        assertEquals(1L, stats.mangaWithNoCategory)
    }

    @Test
    fun `same manga in two categories is not double-counted in the default complement`() = runBlocking {
        addManga("m1")
        addManga("m2")
        db.categoryQueries.insert("A", 1L)
        db.categoryQueries.insert("B", 2L)
        val m1 = db.libraryMangaQueries.selectOne(1L, "m1").executeAsOne()
        db.mangaCategoryQueries.insertOrIgnore(m1.id, 1L)
        db.mangaCategoryQueries.insertOrIgnore(m1.id, 2L)

        val stats = repo.snapshot()

        assertEquals(1L, stats.mangaWithNoCategory) // only m2
    }
}
