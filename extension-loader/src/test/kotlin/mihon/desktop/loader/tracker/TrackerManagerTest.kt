package mihon.desktop.loader.tracker

import kotlinx.coroutines.runBlocking
import mihon.desktop.loader.library.LibraryDatabase
import mihon.desktop.loader.library.LibraryRepository
import mihon.desktop.loader.library.MihonDesktopDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Exercises the reading-progress sync hook ([TrackerManager.pushChapterRead])
 * with a fake tracker: forward-only pushes, skip when logged out / not in
 * library / incognito, and persistence of the pushed state.
 */
class TrackerManagerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: MihonDesktopDatabase
    private lateinit var repository: TrackerRepository
    private lateinit var library: LibraryRepository
    private lateinit var fake: FakeTracker
    private lateinit var manager: TrackerManager

    @Before
    fun setUp() {
        db = LibraryDatabase.openDatabase(File(folder.root, "library.db"))
        repository = TrackerRepository(db)
        library = LibraryRepository(db)
        fake = FakeTracker()
        manager = TrackerManager(
            repository = repository,
            trackers = listOf(fake),
            isIncognito = { false },
        )
    }

    private fun addManga(url: String = "https://m/1"): Long {
        runBlocking {
            library.add(
                sourceId = 1,
                packageName = "pkg",
                jarFileName = "pkg.jar",
                extensionName = "Ext",
                mangaUrl = url,
                title = "Manga",
                thumbnailUrl = null,
                author = null,
            )
        }
        return db.libraryMangaQueries.selectOne(1, url).executeAsOne().id
    }

    @Test
    fun `push updates every bound tracker and persists the result`() = runBlocking {
        val mangaId = addManga()
        repository.upsert(
            TrackEntry(mangaId = mangaId, trackerName = "anilist", remoteId = "1", title = "A", lastChapterRead = 3.0),
        )
        fake.loggedIn = true

        manager.pushChapterRead(1, "https://m/1", 5.0)

        // The tracker saw the push...
        assertEquals(TrackUpdate(lastChapterRead = 5.0), fake.updates.single())
        // ...and the pushed state is what the DB now holds.
        assertEquals(5.0, repository.forMangaId(mangaId).single().lastChapterRead!!, 0.0)
    }

    @Test
    fun `push never regresses remote progress`() = runBlocking {
        val mangaId = addManga()
        repository.upsert(
            TrackEntry(mangaId = mangaId, trackerName = "anilist", remoteId = "1", title = "A", lastChapterRead = 10.0),
        )
        fake.loggedIn = true

        manager.pushChapterRead(1, "https://m/1", 4.0)
        manager.pushChapterRead(1, "https://m/1", 10.0) // equal is also a no-op

        assertTrue(fake.updates.isEmpty())
        assertEquals(10.0, repository.forMangaId(mangaId).single().lastChapterRead!!, 0.0)
    }

    @Test
    fun `push skips logged-out trackers`() = runBlocking {
        val mangaId = addManga()
        repository.upsert(
            TrackEntry(mangaId = mangaId, trackerName = "anilist", remoteId = "1", title = "A"),
        )
        fake.loggedIn = false

        manager.pushChapterRead(1, "https://m/1", 5.0)

        assertTrue(fake.updates.isEmpty())
        assertNull(repository.forMangaId(mangaId).single().lastChapterRead)
    }

    @Test
    fun `push is a no-op for manga outside the library`() = runBlocking {
        fake.loggedIn = true
        manager.pushChapterRead(9, "https://m/none", 5.0)
        assertTrue(fake.updates.isEmpty())
    }

    @Test
    fun `push is a no-op in incognito mode`() = runBlocking {
        val incognito = TrackerManager(
            repository = repository,
            trackers = listOf(fake),
            isIncognito = { true },
        )
        val mangaId = addManga()
        repository.upsert(
            TrackEntry(mangaId = mangaId, trackerName = "anilist", remoteId = "1", title = "A"),
        )
        fake.loggedIn = true

        incognito.pushChapterRead(1, "https://m/1", 5.0)

        assertTrue(fake.updates.isEmpty())
    }

    @Test
    fun `push ignores bindings of unknown trackers`() = runBlocking {
        val mangaId = addManga()
        repository.upsert(
            TrackEntry(mangaId = mangaId, trackerName = "kitsu", remoteId = "1", title = "A"),
        )
        manager.pushChapterRead(1, "https://m/1", 5.0) // must not crash
        assertTrue(fake.updates.isEmpty())
    }

    @Test
    fun `a failing tracker does not block the others`() = runBlocking {
        val mangaId = addManga()
        val failing = FakeTracker(name = "anilist")
        val working = FakeTracker(name = "myanimelist")
        val twoTrackerManager = TrackerManager(
            repository = repository,
            trackers = listOf(failing, working),
            isIncognito = { false },
        )
        repository.upsert(
            TrackEntry(mangaId = mangaId, trackerName = "anilist", remoteId = "1", title = "A"),
        )
        repository.upsert(
            TrackEntry(mangaId = mangaId, trackerName = "myanimelist", remoteId = "2", title = "A"),
        )
        failing.loggedIn = true
        failing.failUpdates = true
        working.loggedIn = true

        // The anilist push throws inside, but pushChapterRead swallows
        // per-tracker failures -- myanimelist still gets its update.
        twoTrackerManager.pushChapterRead(1, "https://m/1", 5.0)

        assertTrue(failing.updates.isEmpty())
        assertNull(repository.forMangaId(mangaId).single { it.trackerName == "anilist" }.lastChapterRead)
        assertEquals(listOf(TrackUpdate(lastChapterRead = 5.0)), working.updates)
        assertEquals(5.0, repository.forMangaId(mangaId).single { it.trackerName == "myanimelist" }.lastChapterRead!!, 0.0)
    }

    // ── fake tracker ────────────────────────────────────────────────────

    /** Minimal [Tracker] fake: records updates, optional failure mode. */
    private class FakeTracker(override val name: String = "anilist") : Tracker {
        override val id = 1L
        var loggedIn = false
        var failUpdates = false
        val updates = mutableListOf<TrackUpdate>()

        override fun isLoggedIn() = loggedIn
        override fun username(): String? = "fake-user"
        override fun logout() { loggedIn = false }
        override fun prepareLogin() = LoginRequest("https://auth", CredentialStyle.ACCESS_TOKEN, "")
        override suspend fun login(credential: String) { loggedIn = true }
        override suspend fun search(query: String): List<RemoteManga> = emptyList()
        override suspend fun bind(track: TrackEntry, seed: TrackUpdate) = track
        override suspend fun update(track: TrackEntry, changes: TrackUpdate): TrackEntry {
            if (failUpdates) throw TrackerException("boom")
            updates.add(changes)
            return track.copy(
                status = changes.status ?: track.status,
                score = changes.score ?: track.score,
                lastChapterRead = changes.lastChapterRead ?: track.lastChapterRead,
            )
        }
        override suspend fun refresh(track: TrackEntry) = track
        override suspend fun unbind(track: TrackEntry) {}
    }
}
