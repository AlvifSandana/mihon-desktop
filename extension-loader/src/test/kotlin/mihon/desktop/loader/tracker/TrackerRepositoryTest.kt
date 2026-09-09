package mihon.desktop.loader.tracker

import kotlinx.coroutines.runBlocking
import mihon.desktop.loader.library.LibraryDatabase
import mihon.desktop.loader.library.LibraryRepository
import mihon.desktop.loader.library.MihonDesktopDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises [TrackerRepository] against a throwaway database: upsert (insert +
 * stable-id update), lookups by manga and by row id, delete, and the
 * library-removal wiring through [LibraryRepository.remove].
 */
class TrackerRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: MihonDesktopDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var library: LibraryRepository

    @Before
    fun setUp() {
        db = LibraryDatabase.openDatabase(folder.newFile("library.db"))
        repo = TrackerRepository(db)
        library = LibraryRepository(db)
    }

    /** Adds a library manga and returns its row id. */
    private fun addManga(url: String, sourceId: Long = 1L): Long {
        runBlocking {
            library.add(
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
        return db.libraryMangaQueries.selectOne(sourceId, url).executeAsOne().id
    }

    private fun entry(mangaId: Long, trackerName: String, remoteId: String, status: TrackStatus = TrackStatus.READING) =
        TrackEntry(
            mangaId = mangaId,
            trackerName = trackerName,
            remoteId = remoteId,
            title = "Remote $remoteId",
            status = status,
            score = 7.5,
            lastChapterRead = 12.0,
        )

    @Test
    fun `upsert inserts and assigns row id`() = runBlocking {
        val mangaId = addManga("https://m/1")
        val stored = repo.upsert(entry(mangaId, "anilist", "30002"))

        assertTrue(stored.id > 0)
        assertEquals(listOf(stored), repo.forMangaId(mangaId))
    }

    @Test
    fun `upsert update keeps row id and replaces fields`() = runBlocking {
        val mangaId = addManga("https://m/1")
        val first = repo.upsert(entry(mangaId, "anilist", "30002"))
        val second = repo.upsert(
            first.copy(
                remoteId = "30656",
                title = "Berserk",
                status = TrackStatus.COMPLETED,
                score = 9.0,
                lastChapterRead = 374.0,
            ),
        )

        assertEquals(first.id, second.id)
        val all = repo.forMangaId(mangaId)
        assertEquals(1, all.size)
        assertEquals("30656", all[0].remoteId)
        assertEquals(TrackStatus.COMPLETED, all[0].status)
        assertEquals(9.0, all[0].score!!, 0.0)
        assertEquals(374.0, all[0].lastChapterRead!!, 0.0)
    }

    @Test
    fun `two trackers on one manga coexist under UNIQUE constraint`() = runBlocking {
        val mangaId = addManga("https://m/1")
        repo.upsert(entry(mangaId, "anilist", "30002"))
        repo.upsert(entry(mangaId, "myanimelist", "2"))

        val entries = repo.forMangaId(mangaId)
        assertEquals(listOf("anilist", "myanimelist"), entries.map { it.trackerName })
        assertEquals(2, repo.all().size)
    }

    @Test
    fun `forManga resolves by source id and url`() = runBlocking {
        val mangaId = addManga("https://m/1", sourceId = 42)
        repo.upsert(entry(mangaId, "anilist", "30002"))
        addManga("https://m/2", sourceId = 42)

        assertEquals(1, repo.forManga(42, "https://m/1").size)
        // Not in the library -> no bindings, no crash.
        assertTrue(repo.forManga(42, "https://m/none").isEmpty())
        assertNull(repo.mangaIdFor(42, "https://m/none"))
    }

    @Test
    fun `delete removes a single binding`() = runBlocking {
        val mangaId = addManga("https://m/1")
        val a = repo.upsert(entry(mangaId, "anilist", "30002"))
        repo.upsert(entry(mangaId, "myanimelist", "2"))
        repo.delete(a.id)

        assertEquals(listOf("myanimelist"), repo.forMangaId(mangaId).map { it.trackerName })
    }

    @Test
    fun `deleteAllForManga removes only that manga's bindings`() = runBlocking {
        val mangaId1 = addManga("https://m/1")
        val mangaId2 = addManga("https://m/2")
        repo.upsert(entry(mangaId1, "anilist", "30002"))
        repo.upsert(entry(mangaId1, "myanimelist", "2"))
        repo.upsert(entry(mangaId2, "anilist", "99999"))

        repo.deleteAllForManga(mangaId1)

        assertTrue(repo.forMangaId(mangaId1).isEmpty())
        assertEquals(1, repo.forMangaId(mangaId2).size)
    }

    @Test
    fun `library remove purges tracker rows like mangaCategory`() = runBlocking {
        val mangaId = addManga("https://m/1")
        repo.upsert(entry(mangaId, "anilist", "30002"))

        library.remove(1, "https://m/1")

        assertTrue(repo.all().isEmpty())
        // The library row is gone too, so re-adding must not resurrect ghosts.
        val mangaIdAgain = addManga("https://m/1")
        assertTrue(repo.forMangaId(mangaIdAgain).isEmpty())
    }

    @Test
    fun `migration purge also drops old entry's tracker rows`() = runBlocking {
        val oldMangaId = addManga("https://old")
        repo.upsert(entry(oldMangaId, "anilist", "30002"))

        library.applyMigration(
            sourceId = 2,
            packageName = "pkg",
            jarFileName = "pkg-v1.jar",
            extensionName = "Ext",
            mangaUrl = "https://new",
            title = "New",
            thumbnailUrl = null,
            author = null,
            chapters = emptyList(),
            readChapters = emptyList(),
            progress = null,
            categoryIds = emptyList(),
            oldSourceId = 1,
            oldMangaUrl = "https://old",
        )

        assertTrue(repo.all().isEmpty())
        assertNotNull(repo.mangaIdFor(2, "https://new"))
    }
}
