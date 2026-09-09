package mihon.desktop.loader.backup.tachibk

import kotlinx.coroutines.runBlocking
import mihon.desktop.loader.library.CategoryRepository
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
import java.io.File

/**
 * Import/export semantics for `.tachibk` interop: fresh imports, merges into
 * an existing library (stable row ids, preserved category mappings), skipped
 * unresolvable sources, and the full export → wipe → import round trip.
 */
class TachibkManagerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: MihonDesktopDatabase
    private lateinit var library: LibraryRepository
    private lateinit var categories: CategoryRepository
    private lateinit var manager: TachibkManager

    /** sourceId 1000 resolves (simulates an installed extension source). */
    private val installedSources = mapOf(
        1000L to InstalledSource(
            sourceId = 1000L,
            name = "MangaDex",
            packageName = "eu.kanade.tachiyomi.extension.mangadex",
            jarFileName = "mangadex-v1.jar",
            extensionName = "MangaDex",
        ),
    )

    @Before
    fun setUp() {
        db = LibraryDatabase.openDatabase(folder.newFile("library.db"))
        library = LibraryRepository(db)
        categories = CategoryRepository(db)
        manager = TachibkManager(
            database = db,
            installedSourcesProvider = { installedSources },
            onBackupActivity = {},
        )
    }

    private fun manga(
        url: String = "manga/a",
        source: Long = 1000L,
        title: String = "Manga A",
        chapters: List<TachibkChapter> = emptyList(),
        history: List<TachibkHistory> = emptyList(),
        categories: List<Long> = emptyList(),
        dateAdded: Long = 0L,
    ) = TachibkManga(
        source = source,
        url = url,
        title = title,
        chapters = chapters,
        history = history,
        categories = categories,
        dateAdded = dateAdded,
    )

    @Test
    fun `imports fresh manga with read chapters, progress and categories`() = runBlocking {
        val backup = TachibkBackup(
            backupManga = listOf(
                manga(
                    url = "manga/a",
                    chapters = listOf(
                        TachibkChapter(url = "c/1", name = "Ch 1", read = true, lastPageRead = 3, sourceOrder = 0),
                        TachibkChapter(url = "c/2", name = "Ch 2", read = true, lastPageRead = 7, sourceOrder = 1),
                        TachibkChapter(url = "c/3", name = "Ch 3", read = false, sourceOrder = 2),
                    ),
                    history = listOf(
                        TachibkHistory(chapterUrl = "c/1", lastRead = 1000L),
                        TachibkHistory(chapterUrl = "c/2", lastRead = 2000L),
                    ),
                    categories = listOf(0L),
                    dateAdded = 555L,
                ),
            ),
            backupCategories = listOf(TachibkCategory(name = "Reading", order = 0)),
        )

        val result = manager.importBackup(backup)

        assertEquals(TachibkImportResult(1, 0, 0, 2, 1), result)

        val row = db.libraryMangaQueries.selectOne(1000L, "manga/a").executeAsOne()
        assertEquals("Manga A", row.title)
        assertEquals("mangadex-v1.jar", row.jarFileName)
        assertEquals("eu.kanade.tachiyomi.extension.mangadex", row.packageName)
        assertEquals(555L, row.addedAt)

        // Only the read chapters became readChapters rows, with history readAt.
        val read = db.readChapterQueries.selectForManga(1000L, "manga/a").executeAsList()
        assertEquals(setOf("c/1", "c/2"), read.map { it.chapterUrl }.toSet())
        assertEquals(2000L, read.first { it.chapterUrl == "c/2" }.readAt)

        // Progress: chapter with the most recent history timestamp (c/2, page 7).
        val progress = db.readingProgressQueries.selectForManga(1000L, "manga/a").executeAsOne()
        assertEquals("c/2", progress.chapterUrl)
        assertEquals(7L, progress.pageIndex)

        // Category created and assigned via order value 0.
        val cats = categories.categoriesForManga(1000L, "manga/a")
        assertEquals(listOf("Reading"), cats.map { it.name })
    }

    @Test
    fun `progress falls back to last read chapter by source order without history`() {
        val backup = TachibkBackup(
            backupManga = listOf(
                manga(
                    chapters = listOf(
                        TachibkChapter(url = "c/1", name = "Ch 1", read = true, lastPageRead = 5, sourceOrder = 0),
                        TachibkChapter(url = "c/2", name = "Ch 2", read = true, lastPageRead = 2, sourceOrder = 1),
                    ),
                ),
            ),
        )
        manager.importBackup(backup)
        val progress = db.readingProgressQueries.selectForManga(1000L, "manga/a").executeAsOne()
        assertEquals("c/2", progress.chapterUrl)
        assertEquals(2L, progress.pageIndex)
    }

    @Test
    fun `skips manga whose source is neither installed nor in the library`() {
        val backup = TachibkBackup(
            backupManga = listOf(
                manga(url = "manga/known", source = 1000L),
                manga(url = "manga/unknown", source = 9999L),
            ),
        )
        val result = manager.importBackup(backup)
        assertEquals(TachibkImportResult(imported = 1, updated = 0, skipped = 1, readChapters = 0, categoriesCreated = 0), result)
        assertNotNull(db.libraryMangaQueries.selectOne(1000L, "manga/known").executeAsOneOrNull())
        assertNull(db.libraryMangaQueries.selectOne(9999L, "manga/unknown").executeAsOneOrNull())
    }

    @Test
    fun `unknown source still imports when a library row already uses that sourceId`() = runBlocking {
        // Re-import scenario: a manga from an uninstalled source whose row
        // already exists (e.g. jar renamed but row intact).
        library.add(
            sourceId = 4242L,
            packageName = "pkg.old",
            jarFileName = "old-v1.jar",
            extensionName = "Old",
            mangaUrl = "manga/old",
            title = "Old title",
            thumbnailUrl = null,
            author = null,
        )
        val backup = TachibkBackup(
            backupManga = listOf(
                manga(url = "manga/old", source = 4242L, title = "New title"),
            ),
        )
        val result = manager.importBackup(backup)
        assertEquals(0, result.imported)
        assertEquals(1, result.updated)
        assertEquals(0, result.skipped)
        val row = db.libraryMangaQueries.selectOne(4242L, "manga/old").executeAsOne()
        assertEquals("New title", row.title)
        assertEquals("old-v1.jar", row.jarFileName)
    }

    @Test
    fun `merge updates in place keeping row id and category mappings`() = runBlocking {
        library.add(
            sourceId = 1000L,
            packageName = "pkg",
            jarFileName = "mangadex-v0.jar",
            extensionName = "MangaDex",
            mangaUrl = "manga/a",
            title = "Manga A",
            thumbnailUrl = null,
            author = null,
        )
        val original = db.libraryMangaQueries.selectOne(1000L, "manga/a").executeAsOne()
        val cat = categories.create("Reading")!!
        categories.setMangaCategories(1000L, "manga/a", listOf(cat))

        val backup = TachibkBackup(
            backupManga = listOf(
                manga(
                    url = "manga/a",
                    title = "Manga A (retitled)",
                    // no category data in the backup -> local mappings untouched
                    chapters = listOf(TachibkChapter(url = "c/1", name = "Ch 1", read = true, lastPageRead = 4)),
                    history = listOf(TachibkHistory(chapterUrl = "c/1", lastRead = 1234L)),
                ),
            ),
        )
        val result = manager.importBackup(backup)

        assertEquals(1, result.updated)
        val row = db.libraryMangaQueries.selectOne(1000L, "manga/a").executeAsOne()
        assertEquals(original.id, row.id)
        assertEquals(original.addedAt, row.addedAt)
        assertEquals("Manga A (retitled)", row.title)
        // Category mapping survived (backup had no category assignment).
        assertEquals(listOf("Reading"), categories.categoriesForManga(1000L, "manga/a").map { it.name })
        assertEquals(listOf(original.id), categories.mangaIdsForCategory(cat))
    }

    @Test
    fun `backup category assignment replaces mappings`() = runBlocking {
        library.add(
            sourceId = 1000L,
            packageName = "pkg",
            jarFileName = "mangadex-v1.jar",
            extensionName = "MangaDex",
            mangaUrl = "manga/a",
            title = "Manga A",
            thumbnailUrl = null,
            author = null,
        )
        val oldCat = categories.create("Old")!!
        categories.setMangaCategories(1000L, "manga/a", listOf(oldCat))

        val backup = TachibkBackup(
            backupManga = listOf(manga(url = "manga/a", categories = listOf(0L))),
            backupCategories = listOf(TachibkCategory(name = "Reading", order = 0)),
        )
        manager.importBackup(backup)

        // "Reading" was created (missing) and now mapped; "Old" unmapped.
        assertEquals(listOf("Reading"), categories.categoriesForManga(1000L, "manga/a").map { it.name })
        assertEquals(emptyList<Long>(), categories.mangaIdsForCategory(oldCat))
    }

    @Test
    fun `import from file handles gzip and reports malformed files`() = runBlocking {
        val payload = TachibkCodec.encode(TachibkBackup(backupManga = listOf(manga(url = "manga/a"))))
        val file = folder.newFile("mihon.tachibk")
        file.writeBytes(TachibkGzip.gzip(payload))

        val result = manager.import(file)
        assertEquals(1, result.imported)
        assertNotNull(db.libraryMangaQueries.selectOne(1000L, "manga/a").executeAsOneOrNull())

        val garbage = folder.newFile("garbage.tachibk")
        garbage.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        try {
            manager.import(garbage)
            throw AssertionError("expected TachibkFormatException")
        } catch (e: TachibkFormatException) {
            // clear error, not a crash
        }
    }

    @Test
    fun `export then wipe then import restores identical state`() = runBlocking {
        // Seed: two manga, one read chapter each, progress on the first,
        // two categories with one manga in each.
        library.add(1000L, "pkg", "mangadex-v1.jar", "MangaDex", "manga/a", "Manga A", "t.jpg", "Author A")
        library.add(1000L, "pkg", "mangadex-v1.jar", "MangaDex", "manga/b", "Manga B", null, null)
        library.markAsRead(1000L, "manga/a", "c/1", "Ch 1")
        library.markAsRead(1000L, "manga/b", "c/9", "Ch 9")
        library.saveProgress(1000L, "manga/a", "c/1", "Ch 1", 5)
        val catA = categories.create("Reading")!!
        val catB = categories.create("Plan to read")!!
        categories.setMangaCategories(1000L, "manga/a", listOf(catA))
        categories.setMangaCategories(1000L, "manga/b", listOf(catB))

        val file = folder.newFile("roundtrip.tachibk")
        manager.export(file)

        // Wipe: brand-new database, same manager shape.
        db = LibraryDatabase.openDatabase(folder.newFile("library2.db"))
        library = LibraryRepository(db)
        categories = CategoryRepository(db)
        manager = TachibkManager(
            database = db,
            installedSourcesProvider = { installedSources },
            onBackupActivity = {},
        )
        val result = manager.import(file)

        assertEquals(2, result.imported)
        assertEquals(2, db.libraryMangaQueries.selectAll().executeAsList().size)

        val a = db.libraryMangaQueries.selectOne(1000L, "manga/a").executeAsOne()
        val b = db.libraryMangaQueries.selectOne(1000L, "manga/b").executeAsOne()
        assertEquals("Manga A", a.title)
        assertEquals("Author A", a.author)
        assertEquals("t.jpg", a.thumbnailUrl)
        assertEquals("mangadex-v1.jar", a.jarFileName)
        assertEquals("Manga B", b.title)

        // Read state identical.
        assertEquals(setOf("c/1"), library.readChapters(1000L, "manga/a"))
        assertEquals(setOf("c/9"), library.readChapters(1000L, "manga/b"))

        // Progress identical (chapter + page).
        val progress = db.readingProgressQueries.selectForManga(1000L, "manga/a").executeAsOne()
        assertEquals("c/1", progress.chapterUrl)
        assertEquals(5L, progress.pageIndex)

        // Categories identical (name + assignment).
        assertEquals(listOf("Reading"), categories.categoriesForManga(1000L, "manga/a").map { it.name })
        assertEquals(listOf("Plan to read"), categories.categoriesForManga(1000L, "manga/b").map { it.name })

        // Export is structurally valid for Android Mihon: gunzip + decode
        // yields the expected top-level fields.
        val decoded = TachibkCodec.decode(TachibkGzip.readBackupPayload(file))
        assertEquals(2, decoded.backupManga.size)
        assertEquals(2, decoded.backupCategories.size)
        assertTrue(decoded.backupSources.any { it.sourceId == 1000L })
    }

    @Test
    fun `exported chapters carry read state, lastPageRead and history`() = runBlocking {
        library.add(1000L, "pkg", "mangadex-v1.jar", "MangaDex", "manga/a", "Manga A", null, null)
        library.markAsRead(1000L, "manga/a", "c/1", "Ch 1")
        library.saveProgress(1000L, "manga/a", "c/1", "Ch 1", 5)

        val file = folder.newFile("export.tachibk")
        manager.export(file)

        val decoded = TachibkCodec.decode(TachibkGzip.readBackupPayload(file))
        val manga = decoded.backupManga.single()
        val chapter = manga.chapters.single()
        assertEquals("c/1", chapter.url)
        assertEquals("Ch 1", chapter.name)
        assertTrue(chapter.read)
        assertEquals(5L, chapter.lastPageRead)
        assertEquals(1, manga.history.size)
        assertEquals("c/1", manga.history[0].chapterUrl)
        assertTrue(manga.history[0].lastRead > 0)
    }

    @Test
    fun `import is idempotent`() {
        val backup = TachibkBackup(
            backupManga = listOf(
                manga(url = "manga/a", chapters = listOf(TachibkChapter(url = "c/1", name = "Ch 1", read = true))),
            ),
        )
        manager.importBackup(backup)
        val first = db.libraryMangaQueries.selectOne(1000L, "manga/a").executeAsOne()
        val second = manager.importBackup(backup)
        assertEquals(0, second.imported)
        assertEquals(1, second.updated)
        assertEquals(first.id, db.libraryMangaQueries.selectOne(1000L, "manga/a").executeAsOne().id)
        assertEquals(1, db.libraryMangaQueries.selectAll().executeAsList().size)
    }

    @Test
    fun `re-importing the same backup does not inflate the chapters-read count`() {
        val backup = TachibkBackup(
            backupManga = listOf(
                manga(
                    chapters = listOf(
                        TachibkChapter(url = "c/1", name = "Ch 1", read = true),
                        TachibkChapter(url = "c/2", name = "Ch 2", read = true),
                    ),
                    history = listOf(
                        TachibkHistory(chapterUrl = "c/1", lastRead = 1000L),
                        TachibkHistory(chapterUrl = "c/2", lastRead = 2000L),
                    ),
                ),
            ),
        )
        val first = manager.importBackup(backup)
        assertEquals(2, first.readChapters)
        val second = manager.importBackup(backup)
        // Both chapters already existed: nothing new was read, the existing
        // row was updated, nothing imported or skipped.
        assertEquals(TachibkImportResult(imported = 0, updated = 1, skipped = 0, readChapters = 0, categoriesCreated = 0), second)
    }

    @Test
    fun `importing a stale backup does not regress newer local progress or readAt`() {
        // Local state is newer (readAt/updatedAt 9000) than the backup's
        // history (lastRead 1000): the import must keep the local values.
        db.libraryMangaQueries.insertOrReplace(
            sourceId = 1000L,
            packageName = "pkg",
            jarFileName = "mangadex-v1.jar",
            extensionName = "MangaDex",
            mangaUrl = "manga/a",
            title = "Manga A",
            thumbnailUrl = null,
            author = null,
            addedAt = 111L,
        )
        db.readChapterQueries.insertOrReplace(
            sourceId = 1000L,
            mangaUrl = "manga/a",
            chapterUrl = "c/1",
            chapterName = "Ch 1",
            readAt = 9000L,
        )
        db.readingProgressQueries.upsert(
            sourceId = 1000L,
            mangaUrl = "manga/a",
            chapterUrl = "c/1",
            chapterName = "Ch 1",
            pageIndex = 9L,
            updatedAt = 9000L,
        )

        val staleBackup = TachibkBackup(
            backupManga = listOf(
                manga(
                    chapters = listOf(TachibkChapter(url = "c/1", name = "Ch 1", read = true, lastPageRead = 1)),
                    history = listOf(TachibkHistory(chapterUrl = "c/1", lastRead = 1000L)),
                ),
            ),
        )
        val result = manager.importBackup(staleBackup)
        assertEquals(1, result.updated)
        assertEquals(0, result.readChapters)

        // readAt keeps max(existing 9000, backup 1000).
        assertEquals(9000L, db.readChapterQueries.selectOne(1000L, "c/1").executeAsOne().readAt)
        // Progress untouched: newer local chapter/page/timestamp win.
        val progress = db.readingProgressQueries.selectForManga(1000L, "manga/a").executeAsOne()
        assertEquals("c/1", progress.chapterUrl)
        assertEquals(9L, progress.pageIndex)
        assertEquals(9000L, progress.updatedAt)

        // A newer backup (lastRead 9500) does overwrite local progress.
        manager.importBackup(
            TachibkBackup(
                backupManga = listOf(
                    manga(
                        chapters = listOf(TachibkChapter(url = "c/1", name = "Ch 1", read = true, lastPageRead = 2)),
                        history = listOf(TachibkHistory(chapterUrl = "c/1", lastRead = 9500L)),
                    ),
                ),
            ),
        )
        var newer = db.readingProgressQueries.selectForManga(1000L, "manga/a").executeAsOne()
        assertEquals(2L, newer.pageIndex)
        assertEquals(9500L, newer.updatedAt)
        assertEquals(9500L, db.readChapterQueries.selectOne(1000L, "c/1").executeAsOne().readAt)

        // A newer backup with lastPageRead = 0 (absent on the wire) keeps
        // the local page index instead of resetting it to 0.
        manager.importBackup(
            TachibkBackup(
                backupManga = listOf(
                    manga(
                        chapters = listOf(TachibkChapter(url = "c/1", name = "Ch 1", read = true, lastPageRead = 0)),
                        history = listOf(TachibkHistory(chapterUrl = "c/1", lastRead = 9800L)),
                    ),
                ),
            ),
        )
        newer = db.readingProgressQueries.selectForManga(1000L, "manga/a").executeAsOne()
        assertEquals(2L, newer.pageIndex)
        assertEquals(9800L, newer.updatedAt)
    }

    @Test
    fun `export with an empty library throws instead of writing a Mihon-rejected backup`() = runBlocking {
        // Mirrors Mihon's BackupCreator: no manga -> no backupManga entries ->
        // the field is absent on the wire, and Android Mihon rejects the
        // whole Backup. We refuse to write one.
        val target = File(folder.root, "empty.tachibk")
        try {
            manager.export(target)
            throw AssertionError("expected IllegalStateException for empty library")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("empty"))
        }
        assertTrue("no partial backup file written", !target.exists())
    }
}
