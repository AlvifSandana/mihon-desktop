package mihon.desktop.loader.library

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises [LibraryRepository] against a throwaway database, focusing on
 * remove(): purging a library entry must take its read chapters and reading
 * progress with it, without touching other manga.
 */
class LibraryRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: MihonDesktopDatabase
    private lateinit var repo: LibraryRepository

    @Before
    fun setUp() {
        db = LibraryDatabase.openDatabase(folder.newFile("library.db"))
        repo = LibraryRepository(db)
    }

    private fun addManga(url: String, sourceId: Long = 1L) {
        runBlocking {
            repo.add(
                sourceId = sourceId,
                packageName = "pkg",
                jarFileName = "pkg-v1.jar",
                extensionName = "Ext",
                mangaUrl = url,
                title = "Manga $url",
                thumbnailUrl = null,
                author = null,
            )
        }
    }

    @Test
    fun `remove purges read chapters and reading progress along with the entry`() = runBlocking {
        addManga("m1")
        repo.markAsRead(1L, "m1", "c1", "Chapter 1")
        repo.markAsRead(1L, "m1", "c2", "Chapter 2")
        repo.saveProgress(1L, "m1", "c2", "Chapter 2", pageIndex = 3)

        // Sanity: the state exists before the remove.
        assertEquals(setOf("c1", "c2"), repo.readChapters(1L, "m1"))
        assertNotNull(repo.progressFor(1L, "m1"))

        repo.remove(1L, "m1")

        assertNull(db.libraryMangaQueries.selectOne(1L, "m1").executeAsOneOrNull())
        assertTrue(db.readChapterQueries.selectForManga(1L, "m1").executeAsList().isEmpty())
        assertNull(db.readingProgressQueries.selectForManga(1L, "m1").executeAsOneOrNull())
    }

    @Test
    fun `remove leaves other manga and their state untouched`() = runBlocking {
        addManga("m1")
        addManga("m2")
        repo.markAsRead(1L, "m1", "c1", "Chapter 1")
        repo.markAsRead(1L, "m2", "c2", "Chapter 2")
        repo.saveProgress(1L, "m2", "c2", "Chapter 2", pageIndex = 1)

        repo.remove(1L, "m1")

        assertNull(db.libraryMangaQueries.selectOne(1L, "m1").executeAsOneOrNull())
        assertNotNull(db.libraryMangaQueries.selectOne(1L, "m2").executeAsOneOrNull())
        assertEquals(setOf("c2"), repo.readChapters(1L, "m2"))
        assertEquals("c2", repo.progressFor(1L, "m2")!!.chapterUrl)
    }

    @Test
    fun `reader preference setters preserve sibling values`() = runBlocking {
        // Each setter writes one column; the others must survive (they are
        // read-modify-write upserts on the same row).
        repo.setWebtoonMode(1L, "m1", true)
        repo.setDualPageMode(1L, "m1", true)
        repo.setPageTransition(1L, "m1", "slide")

        assertEquals(true, repo.getWebtoonMode(1L, "m1"))
        assertEquals(true, repo.getDualPageMode(1L, "m1"))
        assertEquals("slide", repo.getPageTransition(1L, "m1"))

        // Flipping one leaves the rest intact.
        repo.setDualPageMode(1L, "m1", false)
        assertEquals(true, repo.getWebtoonMode(1L, "m1"))
        assertEquals(false, repo.getDualPageMode(1L, "m1"))
        assertEquals("slide", repo.getPageTransition(1L, "m1"))
    }
}
