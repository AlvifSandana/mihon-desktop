package mihon.desktop.loader.migration

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mihon.desktop.loader.library.CategoryRepository
import mihon.desktop.loader.library.LibraryDatabase
import mihon.desktop.loader.library.LibraryManga
import mihon.desktop.loader.library.LibraryRepository
import mihon.desktop.loader.library.MihonDesktopDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * Exercises [MigrationEngine] against a throwaway database with fake sources
 * (no network) and a fake downloads seam: candidate ranking, read-state and
 * progress copy, category preservation, delete vs keep-both, options, batch
 * failure isolation and cancellation.
 */
class MigrationEngineTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: MihonDesktopDatabase
    private lateinit var library: LibraryRepository
    private lateinit var categories: CategoryRepository
    private lateinit var downloads: FakeDownloads
    private lateinit var engine: MigrationEngine

    private val oldSourceId = 100L
    private val newSourceId = 200L

    @Before
    fun setUp() {
        db = LibraryDatabase.openDatabase(folder.newFile("library.db"))
        library = LibraryRepository(db)
        categories = CategoryRepository(db)
        downloads = FakeDownloads()
        engine = MigrationEngine(libraryRepository = library, categoryRepository = categories, downloads = downloads)
    }

    // ── Fakes ────────────────────────────────────────────────────────────

    /** Catalogue source that answers search from a static list, ignoring the query. */
    private class FakeCatalogueSource(
        override val id: Long,
        override val name: String,
        private val searchResults: List<SManga>,
        private val chapters: List<SChapter> = emptyList(),
        private val failOnMangaUrl: String? = null,
    ) : CatalogueSource {
        override val lang = "en"
        override val supportsLatest = false
        var searchCount = 0
            private set

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            searchCount++
            return MangasPage(searchResults, hasNextPage = false)
        }

        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)

        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate {
            if (manga.url == failOnMangaUrl) throw IOException("network down")
            return SMangaUpdate(manga, this.chapters)
        }

        override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
    }

    private class FakeDownloads : MigrationDownloads {
        val enqueued = mutableListOf<Triple<Long, String, String>>() // (sourceId, mangaUrl, chapterUrl)
        val deleted = mutableListOf<Pair<Long, String>>()
        val seeded = mutableMapOf<Pair<Long, String>, List<DownloadedChapterRef>>()

        override suspend fun downloadedChapters(sourceId: Long, mangaUrl: String): List<DownloadedChapterRef> =
            seeded[sourceId to mangaUrl] ?: emptyList()

        override suspend fun deleteAllForManga(sourceId: Long, mangaUrl: String) {
            deleted += sourceId to mangaUrl
        }

        override suspend fun enqueueDownload(source: Source, mangaUrl: String, chapter: SChapter) {
            enqueued += Triple(source.id, mangaUrl, chapter.url)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun manga(url: String, title: String) = SManga.create().apply {
        this.url = url
        this.title = title
    }

    private fun chapter(url: String, name: String, number: Float) = SChapter.create().apply {
        this.url = url
        this.name = name
        chapter_number = number
    }

    private fun addLibraryManga(url: String, title: String): LibraryManga {
        runBlocking {
            library.add(
                sourceId = oldSourceId,
                packageName = "old.pkg",
                jarFileName = "old-v1.jar",
                extensionName = "OldExt",
                mangaUrl = url,
                title = title,
                thumbnailUrl = null,
                author = null,
            )
        }
        return db.libraryMangaQueries.selectOne(oldSourceId, url).executeAsOne()
    }

    private fun target(source: Source) = MigrationTarget(
        source = source,
        packageName = "new.pkg",
        jarFileName = "new-v1.jar",
        extensionName = "NewExt",
    )

    /** Target with the standard chapter list used by read/download copy tests. */
    private fun standardTarget(): Pair<MigrationTarget, FakeCatalogueSource> {
        val source = FakeCatalogueSource(
            id = newSourceId,
            name = "New Source",
            searchResults = listOf(manga("new-m1", "Solo Leveling")),
            chapters = listOf(
                chapter("n1", "Chapter 1", 1f),
                chapter("n2", "Chapter 2", 2f),
                chapter("n3", "Side Story Extra", -1f),
                chapter("n4", "Chapter 10", 10f),
            ),
        )
        return target(source) to source
    }

    // ── Candidate ranking ────────────────────────────────────────────────

    @Test
    fun `findCandidates ranks exact first then startsWith then contains and flags exact matches`() = runBlocking {
        val source = FakeCatalogueSource(
            id = newSourceId,
            name = "New",
            searchResults = listOf(
                manga("u-contains", "The One Piece Anthology"),
                manga("u-exact2", "one  piece"), // exact after normalization
                manga("u-unrelated", "Bleach"),
                manga("u-exact1", "One Piece"),
                manga("u-starts", "One Piece Red"),
            ),
        )

        val candidates = engine.findCandidates(target(source).source, "One Piece")

        // Tier 0 (exact): source order preserved within the tier (u-exact2 at
        // index 1 comes before u-exact1 at index 3), then tiers 1, 2, 3.
        assertEquals(
            listOf("u-exact2", "u-exact1", "u-starts", "u-contains", "u-unrelated"),
            candidates.map { it.manga.url },
        )
        assertEquals(listOf(true, true, false, false, false), candidates.map { it.exactMatch })
        assertEquals(1, source.searchCount)
    }

    @Test
    fun `findCandidates caps results at 10 keeping best ranks`() = runBlocking {
        val many = (1..15).map { manga("u$it", "Title $it") } + manga("u-exact", "Alpha")
        val source = FakeCatalogueSource(id = newSourceId, name = "New", searchResults = many)

        val candidates = engine.findCandidates(target(source).source, "Alpha")

        assertEquals(10, candidates.size)
        assertEquals("u-exact", candidates.first().manga.url) // exact match wins over source order
        assertTrue(candidates.first().exactMatch)
    }

    // ── Single-manga migration ───────────────────────────────────────────

    @Test
    fun `migrateManga copies read chapters by number with name fallback`() = runBlocking {
        val old = addLibraryManga("old-m1", "Solo Leveling")
        // Read on the old source: two by chapter number, one only matchable by name.
        library.markAsRead(oldSourceId, "old-m1", "oc1", "Chapter 1")
        library.markAsRead(oldSourceId, "old-m1", "oc2", "Chapter 2")
        library.markAsRead(oldSourceId, "old-m1", "oc3", "Side Story")
        val (t, _) = standardTarget()

        val status = engine.migrateManga(old, t, manga("new-m1", "Solo Leveling"), MigrationOptions())

        assertTrue(status is MigrationStatus.Migrated)
        assertEquals(setOf("n1", "n2", "n3"), library.readChapters(newSourceId, "new-m1"))
        // "Chapter 10" (n4) must not be touched: number 10 never appeared as read.
        assertTrue("n4" !in library.readChapters(newSourceId, "new-m1"))
    }

    @Test
    fun `migrateManga copies reading progress to the matching chapter`() = runBlocking {
        val old = addLibraryManga("old-m1", "Solo Leveling")
        library.saveProgress(oldSourceId, "old-m1", "oc2", "Chapter 2", pageIndex = 5)
        val (t, _) = standardTarget()

        engine.migrateManga(old, t, manga("new-m1", "Solo Leveling"), MigrationOptions())

        val progress = library.progressFor(newSourceId, "new-m1")
        assertNotNull(progress)
        assertEquals("n2", progress!!.chapterUrl)
        assertEquals("Chapter 2", progress.chapterName)
        assertEquals(5L, progress.pageIndex)
    }

    @Test
    fun `migrateManga preserves category mappings`() = runBlocking {
        val old = addLibraryManga("old-m1", "Solo Leveling")
        val catA = categories.create("Reading")!!
        val catB = categories.create("Dropped")!!
        categories.setMangaCategories(oldSourceId, "old-m1", listOf(catA, catB))
        val (t, _) = standardTarget()

        engine.migrateManga(old, t, manga("new-m1", "Solo Leveling"), MigrationOptions())

        assertEquals(listOf("Reading", "Dropped"), categories.categoriesForManga(newSourceId, "new-m1").map { it.name })
    }

    @Test
    fun `migrateManga with includeCategories false maps no categories`() = runBlocking {
        val old = addLibraryManga("old-m1", "Solo Leveling")
        val catA = categories.create("Reading")!!
        categories.setMangaCategories(oldSourceId, "old-m1", listOf(catA))
        val (t, _) = standardTarget()

        engine.migrateManga(old, t, manga("new-m1", "Solo Leveling"), MigrationOptions(includeCategories = false))

        assertTrue(categories.categoriesForManga(newSourceId, "new-m1").isEmpty())
    }

    @Test
    fun `migrateManga replaces the old entry by default`() = runBlocking {
        val old = addLibraryManga("old-m1", "Solo Leveling")
        val (t, _) = standardTarget()

        engine.migrateManga(old, t, manga("new-m1", "Solo Leveling"), MigrationOptions())

        assertNull(db.libraryMangaQueries.selectOne(oldSourceId, "old-m1").executeAsOneOrNull())
        val newRow = db.libraryMangaQueries.selectOne(newSourceId, "new-m1").executeAsOneOrNull()
        assertNotNull(newRow)
        assertEquals("new.pkg", newRow!!.packageName) // new source's data, not the old row's
        assertEquals(listOf(oldSourceId to "old-m1"), downloads.deleted) // downloads purged too
    }

    @Test
    fun `migrateManga with deleteFromLibrary false keeps both entries`() = runBlocking {
        val old = addLibraryManga("old-m1", "Solo Leveling")
        val (t, _) = standardTarget()

        engine.migrateManga(old, t, manga("new-m1", "Solo Leveling"), MigrationOptions(deleteFromLibrary = false))

        assertNotNull(db.libraryMangaQueries.selectOne(oldSourceId, "old-m1").executeAsOneOrNull())
        assertNotNull(db.libraryMangaQueries.selectOne(newSourceId, "new-m1").executeAsOneOrNull())
        assertTrue(downloads.deleted.isEmpty())
    }

    @Test
    fun `migrateManga failing to fetch target details leaves the old manga untouched`() = runBlocking {
        val old = addLibraryManga("old-m1", "Solo Leveling")
        val source = FakeCatalogueSource(
            id = newSourceId,
            name = "New",
            searchResults = listOf(manga("new-m1", "Solo Leveling")),
            failOnMangaUrl = "new-m1",
        )

        val status = engine.migrateManga(old, target(source), manga("new-m1", "Solo Leveling"), MigrationOptions())

        assertTrue(status is MigrationStatus.Failed)
        assertNotNull(db.libraryMangaQueries.selectOne(oldSourceId, "old-m1").executeAsOneOrNull())
        assertNull(db.libraryMangaQueries.selectOne(newSourceId, "new-m1").executeAsOneOrNull())
        assertTrue(downloads.deleted.isEmpty())
    }

    @Test
    fun `migrating onto the same source fails and touches nothing`() = runBlocking {
        val old = addLibraryManga("old-m1", "Solo Leveling")
        val source = FakeCatalogueSource(
            id = oldSourceId, // same source the manga is being migrated from
            name = "Old Source",
            searchResults = listOf(manga("old-m1", "Solo Leveling")),
        )

        val status = engine.migrateManga(old, target(source), manga("old-m1", "Solo Leveling"), MigrationOptions())

        assertTrue(status is MigrationStatus.Failed)
        assertNotNull(db.libraryMangaQueries.selectOne(oldSourceId, "old-m1").executeAsOneOrNull())
        assertTrue(downloads.deleted.isEmpty())
    }

    @Test
    fun `includeReadChapters false copies neither reads nor progress`() = runBlocking {
        val old = addLibraryManga("old-m1", "Solo Leveling")
        library.markAsRead(oldSourceId, "old-m1", "oc1", "Chapter 1")
        library.markAsRead(oldSourceId, "old-m1", "oc2", "Chapter 2")
        library.saveProgress(oldSourceId, "old-m1", "oc2", "Chapter 2", pageIndex = 3)
        val (t, _) = standardTarget()

        engine.migrateManga(old, t, manga("new-m1", "Solo Leveling"), MigrationOptions(includeReadChapters = false))

        assertTrue(library.readChapters(newSourceId, "new-m1").isEmpty())
        assertNull(library.progressFor(newSourceId, "new-m1"))
    }

    // ── Chapter matching ─────────────────────────────────────────────────

    @Test
    fun `matchChapter number tolerance boundary is 0_001`() {
        // 0.0009 off -> inside tolerance; 0.005 off -> outside. Names are
        // deliberately not equal to the needle so only the number path can match.
        val close = chapter("n-close", "Chapter 12 (HQ)", 12.0009f)
        val far = chapter("n-far", "Chapter 12.005", 12.005f)

        assertSame(close, MigrationEngine.matchChapter(12.0, "Chapter 12", listOf(far, close)))
        assertNull(MigrationEngine.matchChapter(12.0, "Chapter 12", listOf(far)))
    }

    @Test
    fun `numbered old chapter with no number hit is not matched by name contains`() = runBlocking {
        val old = addLibraryManga("old-m1", "Solo Leveling")
        library.markAsRead(oldSourceId, "old-m1", "oc2", "Chapter 2")
        // "Chapter 20" contains "chapter 2" after normalization, but its number
        // is 20, not 2 -- the contains check must not rescue the match.
        val source = FakeCatalogueSource(
            id = newSourceId,
            name = "New Source",
            searchResults = listOf(manga("new-m1", "Solo Leveling")),
            chapters = listOf(chapter("n20", "Chapter 20", 20f)),
        )

        engine.migrateManga(old, target(source), manga("new-m1", "Solo Leveling"), MigrationOptions())

        assertTrue(library.readChapters(newSourceId, "new-m1").isEmpty())
    }

    // ── Download option ──────────────────────────────────────────────────

    @Test
    fun `downloadChapters false never enqueues downloads`() = runBlocking {
        val old = addLibraryManga("old-m1", "Solo Leveling")
        library.markAsRead(oldSourceId, "old-m1", "oc2", "Chapter 2")
        downloads.seeded[oldSourceId to "old-m1"] = listOf(DownloadedChapterRef("od1", "Chapter 1"))
        val (t, _) = standardTarget()

        engine.migrateManga(old, t, manga("new-m1", "Solo Leveling"), MigrationOptions(downloadChapters = false))

        assertTrue(downloads.enqueued.isEmpty())
    }

    @Test
    fun `downloadChapters true enqueues matched read and previously downloaded chapters`() = runBlocking {
        val old = addLibraryManga("old-m1", "Solo Leveling")
        library.markAsRead(oldSourceId, "old-m1", "oc2", "Chapter 2") // read -> n2
        downloads.seeded[oldSourceId to "old-m1"] = listOf(DownloadedChapterRef("od1", "Chapter 1")) // downloaded -> n1
        val (t, _) = standardTarget()

        engine.migrateManga(old, t, manga("new-m1", "Solo Leveling"), MigrationOptions(downloadChapters = true))

        assertEquals(
            setOf("n1", "n2"),
            downloads.enqueued.map { it.third }.toSet(),
        )
        assertTrue(downloads.enqueued.all { it.first == newSourceId && it.second == "new-m1" })
    }

    @Test
    fun `downloadChapters true with includeReadChapters false downloads only previously downloaded chapters`() = runBlocking {
        val old = addLibraryManga("old-m1", "Solo Leveling")
        // Read but not downloaded: must be neither copied as read nor downloaded.
        library.markAsRead(oldSourceId, "old-m1", "oc2", "Chapter 2")
        library.saveProgress(oldSourceId, "old-m1", "oc2", "Chapter 2", pageIndex = 2)
        downloads.seeded[oldSourceId to "old-m1"] = listOf(DownloadedChapterRef("od1", "Chapter 1"))
        val (t, _) = standardTarget()

        engine.migrateManga(
            old,
            t,
            manga("new-m1", "Solo Leveling"),
            MigrationOptions(downloadChapters = true, includeReadChapters = false),
        )

        assertEquals(setOf("n1"), downloads.enqueued.map { it.third }.toSet())
        assertTrue(library.readChapters(newSourceId, "new-m1").isEmpty())
        assertNull(library.progressFor(newSourceId, "new-m1"))
    }

    // ── Batch ────────────────────────────────────────────────────────────

    @Test
    fun `batch migrates exact matches and reports ambiguous ones as needing manual match`() = runBlocking {
        val alpha = addLibraryManga("m-alpha", "Alpha")
        val beta = addLibraryManga("m-beta", "Beta")
        val source = FakeCatalogueSource(
            id = newSourceId,
            name = "New",
            searchResults = listOf(
                manga("n-alpha", "Alpha"),
                manga("n-beta-1", "beta"), // exact for Beta
                manga("n-beta-2", "Beta"), // exact for Beta too -> ambiguous
            ),
            chapters = listOf(chapter("n1", "Chapter 1", 1f)),
        )

        val results = engine.migrateBatch(listOf(alpha, beta), target(source), MigrationOptions())

        assertEquals(2, results.size)
        assertTrue(results[0].status is MigrationStatus.Migrated)
        assertTrue(results[1].status is MigrationStatus.NeedsManualMatch)
        // Ambiguous manga untouched: old row kept, no new row added.
        assertNotNull(db.libraryMangaQueries.selectOne(oldSourceId, "m-beta").executeAsOneOrNull())
        assertNull(db.libraryMangaQueries.selectOne(newSourceId, "n-beta-1").executeAsOneOrNull())
        assertNull(db.libraryMangaQueries.selectOne(newSourceId, "n-beta-2").executeAsOneOrNull())
        // The unambiguous one migrated.
        assertNull(db.libraryMangaQueries.selectOne(oldSourceId, "m-alpha").executeAsOneOrNull())
    }

    @Test
    fun `batch reports no match and leaves manga untouched`() = runBlocking {
        val old = addLibraryManga("m1", "Alpha")
        val source = FakeCatalogueSource(id = newSourceId, name = "New", searchResults = emptyList())

        val results = engine.migrateBatch(listOf(old), target(source), MigrationOptions())

        assertEquals(MigrationStatus.NoMatch, results.single().status)
        assertNotNull(db.libraryMangaQueries.selectOne(oldSourceId, "m1").executeAsOneOrNull())
        assertEquals(1, source.searchCount)
    }

    @Test
    fun `batch isolates per-manga failures`() = runBlocking {
        val ok1 = addLibraryManga("m1", "Alpha")
        val bad = addLibraryManga("m2", "Beta")
        val ok2 = addLibraryManga("m3", "Gamma")
        val source = FakeCatalogueSource(
            id = newSourceId,
            name = "New",
            searchResults = listOf(
                manga("n1", "Alpha"),
                manga("n2", "Beta"),
                manga("n3", "Gamma"),
            ),
            failOnMangaUrl = "n2", // Beta's target fetch explodes
            chapters = listOf(chapter("c1", "Chapter 1", 1f)),
        )

        val seen = mutableListOf<Int>()
        val results = engine.migrateBatch(
            entries = listOf(ok1, bad, ok2),
            target = target(source),
            options = MigrationOptions(),
            onItemResult = { _, _, _ -> seen += 1 },
        )

        assertEquals(3, results.size)
        assertTrue(results[0].status is MigrationStatus.Migrated)
        assertTrue(results[1].status is MigrationStatus.Failed)
        assertTrue(results[2].status is MigrationStatus.Migrated)
        assertEquals(3, seen.size) // every item reported, none aborted the loop
        // Failed manga untouched; the other two migrated.
        assertNotNull(db.libraryMangaQueries.selectOne(oldSourceId, "m2").executeAsOneOrNull())
        assertNull(db.libraryMangaQueries.selectOne(newSourceId, "n2").executeAsOneOrNull())
        assertNull(db.libraryMangaQueries.selectOne(oldSourceId, "m1").executeAsOneOrNull())
        assertNull(db.libraryMangaQueries.selectOne(oldSourceId, "m3").executeAsOneOrNull())
    }

    @Test
    fun `cancellation propagates instead of being swallowed`() = runBlocking {
        val old = addLibraryManga("m1", "Alpha")
        val source = FakeCatalogueSource(id = newSourceId, name = "New", searchResults = listOf(manga("n1", "Alpha")))

        val thrown = runCatching {
            engine.migrateBatch(
                entries = listOf(old),
                target = target(source),
                options = MigrationOptions(),
                resolver = { _, _ -> throw CancellationException("user cancelled") },
            )
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        // Nothing was written before the cancellation.
        assertNotNull(db.libraryMangaQueries.selectOne(oldSourceId, "m1").executeAsOneOrNull())
        assertNull(db.libraryMangaQueries.selectOne(newSourceId, "n1").executeAsOneOrNull())
    }
}
