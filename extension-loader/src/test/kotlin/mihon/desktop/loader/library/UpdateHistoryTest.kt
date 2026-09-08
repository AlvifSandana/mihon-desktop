package mihon.desktop.loader.library

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Verifies the update-history diffing logic: only never-seen chapters are
 * recorded, baseline seeds never surface in Updates, and the unseen-badge
 * count excludes baselines.
 */
class UpdateHistoryTest {

    private fun newRepository(): LibraryRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MihonDesktopDatabase.Schema.create(driver)
        return LibraryRepository(MihonDesktopDatabase(driver))
    }

    private fun chapter(url: String, name: String = "Chapter $url", number: Float = 1f) =
        SChapter.create().apply {
            this.url = url
            this.name = name
            chapter_number = number
        }

    @Test
    fun `first record returns all chapters as fresh`() = runBlocking {
        val repo = newRepository()
        val fresh = repo.recordNewChapters(
            sourceId = 1L, mangaUrl = "m1", mangaTitle = "Manga One", thumbnailUrl = null,
            packageName = "pkg", jarFileName = "pkg-v1.jar",
            chapters = listOf(chapter("c1"), chapter("c2")),
        )
        assertEquals(2, fresh.size)
        assertEquals(2, repo.allUpdates().size)
    }

    @Test
    fun `second record only returns unseen chapters`() = runBlocking {
        val repo = newRepository()
        repo.recordNewChapters(
            sourceId = 1L, mangaUrl = "m1", mangaTitle = "Manga One", thumbnailUrl = null,
            packageName = "pkg", jarFileName = "pkg-v1.jar",
            chapters = listOf(chapter("c1"), chapter("c2")),
        )
        val fresh = repo.recordNewChapters(
            sourceId = 1L, mangaUrl = "m1", mangaTitle = "Manga One", thumbnailUrl = null,
            packageName = "pkg", jarFileName = "pkg-v1.jar",
            chapters = listOf(chapter("c1"), chapter("c2"), chapter("c3")),
        )
        assertEquals(listOf("c3"), fresh.map { it.chapterUrl })
        // c1/c2 were recorded as updates by the first run and stay visible;
        // only c3 was added by this run
        assertEquals(setOf("c1", "c2", "c3"), repo.allUpdates().map { it.chapterUrl }.toSet())
    }

    @Test
    fun `baseline seed never appears in updates or counts`() = runBlocking {
        val repo = newRepository()
        // Adding to library seeds existing chapters as known-but-baseline
        val seeded = repo.recordNewChapters(
            sourceId = 1L, mangaUrl = "m1", mangaTitle = "Manga One", thumbnailUrl = null,
            packageName = "pkg", jarFileName = "pkg-v1.jar",
            chapters = listOf(chapter("c1"), chapter("c2")),
            baseline = true,
        )
        assertEquals(2, seeded.size)
        assertTrue("baseline chapters must not be visible updates", repo.allUpdates().isEmpty())
        assertEquals("baseline chapters must not count as unseen", 0L, repo.updateCountSince(0L))

        // A later refresh only surfaces chapters added after the seed
        val fresh = repo.recordNewChapters(
            sourceId = 1L, mangaUrl = "m1", mangaTitle = "Manga One", thumbnailUrl = null,
            packageName = "pkg", jarFileName = "pkg-v1.jar",
            chapters = listOf(chapter("c1"), chapter("c2"), chapter("c3")),
        )
        assertEquals(listOf("c3"), fresh.map { it.chapterUrl })
        assertEquals(listOf("c3"), repo.allUpdates().map { it.chapterUrl })
    }

    @Test
    fun `re-seeding does not clobber recorded updates`() = runBlocking {
        val repo = newRepository()
        // Seed once, then a refresh records c3 as a real update
        repo.recordNewChapters(
            sourceId = 1L, mangaUrl = "m1", mangaTitle = "Manga One", thumbnailUrl = null,
            packageName = "pkg", jarFileName = "pkg-v1.jar",
            chapters = listOf(chapter("c1")), baseline = true,
        )
        repo.recordNewChapters(
            sourceId = 1L, mangaUrl = "m1", mangaTitle = "Manga One", thumbnailUrl = null,
            packageName = "pkg", jarFileName = "pkg-v1.jar",
            chapters = listOf(chapter("c1"), chapter("c3")),
        )
        // Re-favorite (seed again): INSERT OR IGNORE must not flip c3 to baseline
        repo.recordNewChapters(
            sourceId = 1L, mangaUrl = "m1", mangaTitle = "Manga One", thumbnailUrl = null,
            packageName = "pkg", jarFileName = "pkg-v1.jar",
            chapters = listOf(chapter("c1"), chapter("c3")), baseline = true,
        )
        assertEquals(listOf("c3"), repo.allUpdates().map { it.chapterUrl })
    }

    @Test
    fun `countSince only counts entries after the timestamp`() = runBlocking {
        val repo = newRepository()
        val before = System.currentTimeMillis() - 60_000
        repo.recordNewChapters(
            sourceId = 1L, mangaUrl = "m1", mangaTitle = "Manga One", thumbnailUrl = null,
            packageName = "pkg", jarFileName = "pkg-v1.jar",
            chapters = listOf(chapter("c1")),
        )
        assertEquals(1L, repo.updateCountSince(before))
        assertEquals(0L, repo.updateCountSince(System.currentTimeMillis() + 60_000))
    }

    @Test
    fun `chapters are scoped per source`() = runBlocking {
        val repo = newRepository()
        repo.recordNewChapters(
            sourceId = 1L, mangaUrl = "m1", mangaTitle = "Manga One", thumbnailUrl = null,
            packageName = "pkg", jarFileName = "pkg-v1.jar",
            chapters = listOf(chapter("c1")), baseline = true,
        )
        // Same mangaUrl + chapterUrl under a different source is a different entry
        val fresh = repo.recordNewChapters(
            sourceId = 2L, mangaUrl = "m1", mangaTitle = "Manga One", thumbnailUrl = null,
            packageName = "pkg", jarFileName = "pkg-v2.jar",
            chapters = listOf(chapter("c1")),
        )
        assertEquals(1, fresh.size)
    }

    @Test
    fun `removing a manga purges its update history including baselines`() = runBlocking {
        val repo = newRepository()
        repo.add(
            sourceId = 1L, packageName = "pkg", jarFileName = "pkg-v1.jar",
            extensionName = "Ext", mangaUrl = "m1", title = "Manga One",
            thumbnailUrl = null, author = null,
        )
        repo.recordNewChapters(
            sourceId = 1L, mangaUrl = "m1", mangaTitle = "Manga One", thumbnailUrl = null,
            packageName = "pkg", jarFileName = "pkg-v1.jar",
            chapters = listOf(chapter("c1")), baseline = true,
        )
        repo.recordNewChapters(
            sourceId = 1L, mangaUrl = "m1", mangaTitle = "Manga One", thumbnailUrl = null,
            packageName = "pkg", jarFileName = "pkg-v1.jar",
            chapters = listOf(chapter("c2")),
        )
        // Sanity: one baseline (hidden) + one real update
        assertEquals(1, repo.allUpdates().size)

        repo.remove(sourceId = 1L, mangaUrl = "m1")

        // Both kinds purged: re-adding re-seeds from scratch, no stale knowledge
        val fresh = repo.recordNewChapters(
            sourceId = 1L, mangaUrl = "m1", mangaTitle = "Manga One", thumbnailUrl = null,
            packageName = "pkg", jarFileName = "pkg-v1.jar",
            chapters = listOf(chapter("c1"), chapter("c2")),
        )
        assertEquals(setOf("c1", "c2"), fresh.map { it.chapterUrl }.toSet())
    }

    @Test
    fun `clearAllUpdates keeps baseline seeds`() = runBlocking {
        val repo = newRepository()
        repo.recordNewChapters(
            sourceId = 1L, mangaUrl = "m1", mangaTitle = "Manga One", thumbnailUrl = null,
            packageName = "pkg", jarFileName = "pkg-v1.jar",
            chapters = listOf(chapter("c1"), chapter("c2")), baseline = true,
        )
        repo.recordNewChapters(
            sourceId = 1L, mangaUrl = "m1", mangaTitle = "Manga One", thumbnailUrl = null,
            packageName = "pkg", jarFileName = "pkg-v1.jar",
            chapters = listOf(chapter("c3")),
        )
        repo.clearAllUpdates()
        // The visible update is gone, but the baseline knowledge survives: a
        // refresh won't resurrect c1/c2 as updates.
        assertTrue(repo.allUpdates().isEmpty())
        val fresh = repo.recordNewChapters(
            sourceId = 1L, mangaUrl = "m1", mangaTitle = "Manga One", thumbnailUrl = null,
            packageName = "pkg", jarFileName = "pkg-v1.jar",
            chapters = listOf(chapter("c1"), chapter("c2"), chapter("c3")),
        )
        assertTrue(fresh.isEmpty())
    }
}
