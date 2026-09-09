package mihon.desktop.loader.backup

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mihon.desktop.loader.library.CategoryRepository
import mihon.desktop.loader.library.LibraryDatabase
import mihon.desktop.loader.library.LibraryRepository
import mihon.desktop.loader.library.MihonDesktopDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regression tests for [BackupManager.importBackup] over an existing library:
 * re-imported manga must keep their libraryManga row id (INSERT OR REPLACE
 * would churn it and orphan mangaCategory mappings), while new manga are
 * inserted and metadata is refreshed in place.
 */
class BackupManagerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: MihonDesktopDatabase
    private lateinit var library: LibraryRepository
    private lateinit var categories: CategoryRepository
    private lateinit var backupManager: BackupManager

    @Before
    fun setUp() {
        db = LibraryDatabase.openDatabase(folder.newFile("library.db"))
        library = LibraryRepository(db)
        categories = CategoryRepository(db)
        backupManager = BackupManager(db)
    }

    private fun writeBackup(manga: List<BackupLibraryManga>): File {
        val data = BackupData(
            version = 1,
            exportedAt = 123L,
            libraryManga = manga,
            readingProgress = emptyMap(),
            downloadedChapters = emptyMap(),
        )
        return folder.newFile("backup.json").apply {
            writeText(Json { prettyPrint = true }.encodeToString(BackupData.serializer(), data))
        }
    }

    @Test
    fun `import over existing library keeps row id and category mappings intact`() = runBlocking {
        library.add(
            sourceId = 1L,
            packageName = "pkg",
            jarFileName = "pkg-v1.jar",
            extensionName = "Ext",
            mangaUrl = "m1",
            title = "Manga m1",
            thumbnailUrl = null,
            author = null,
        )
        val original = db.libraryMangaQueries.selectOne(1L, "m1").executeAsOne()
        val cat = categories.create("Reading")!!
        categories.setMangaCategories(1L, "m1", listOf(cat))

        val backup = writeBackup(
            listOf(
                BackupLibraryManga(
                    sourceId = 1L,
                    packageName = "pkg",
                    jarFileName = "pkg-v2.jar", // refreshed jar in the backup
                    extensionName = "Ext",
                    mangaUrl = "m1",
                    title = "Manga m1 (retitled)",
                    thumbnailUrl = null,
                    author = "Author",
                    addedAt = 555L,
                ),
            ),
        )

        assertEquals(1, backupManager.importBackup(backup))

        val row = db.libraryMangaQueries.selectOne(1L, "m1").executeAsOne()
        // Row id must not be churned by a REPLACE -- mangaCategory mappings
        // reference it and would be orphaned by a new id.
        assertEquals(original.id, row.id)
        // Metadata (and only metadata) is refreshed in place
        assertEquals("pkg-v2.jar", row.jarFileName)
        assertEquals("Manga m1 (retitled)", row.title)
        assertEquals("Author", row.author)
        // addedAt is preserved, like LibraryRepository.add's in-place update
        assertEquals(original.addedAt, row.addedAt)
        // The category mapping survived the import
        assertEquals(listOf("Reading"), categories.categoriesForManga(1L, "m1").map { it.name })
        assertEquals(listOf(original.id), categories.mangaIdsForCategory(cat))
    }

    @Test
    fun `import inserts manga not yet in the library and merges metadata for existing ones`() = runBlocking {
        library.add(
            sourceId = 1L,
            packageName = "pkg",
            jarFileName = "pkg-v1.jar",
            extensionName = "Ext",
            mangaUrl = "m1",
            title = "Manga m1",
            thumbnailUrl = null,
            author = null,
        )
        val existingId = db.libraryMangaQueries.selectOne(1L, "m1").executeAsOne().id
        val cat = categories.create("Reading")!!
        categories.setMangaCategories(1L, "m1", listOf(cat))

        val backup = writeBackup(
            listOf(
                BackupLibraryManga(1L, "pkg", "pkg-v1.jar", "Ext", "m1", "Manga m1", null, null, 100L),
                BackupLibraryManga(2L, "pkg2", "pkg2-v1.jar", "Ext2", "m2", "Manga m2", null, null, 200L),
            ),
        )

        assertEquals(2, backupManager.importBackup(backup))

        // Existing row: id and mappings intact
        assertEquals(existingId, db.libraryMangaQueries.selectOne(1L, "m1").executeAsOne().id)
        assertEquals(listOf("Reading"), categories.categoriesForManga(1L, "m1").map { it.name })
        // New row: inserted
        val newRow = db.libraryMangaQueries.selectOne(2L, "m2").executeAsOneOrNull()
        assertNotNull(newRow)
        assertEquals("Manga m2", newRow!!.title)
        // And the library grew by exactly one row
        assertEquals(2, db.libraryMangaQueries.selectAll().executeAsList().size)
    }

    @Test
    fun `import twice is idempotent`() = runBlocking {
        val backup = writeBackup(
            listOf(
                BackupLibraryManga(1L, "pkg", "pkg-v1.jar", "Ext", "m1", "Manga m1", null, null, 100L),
            ),
        )

        backupManager.importBackup(backup)
        val firstId = db.libraryMangaQueries.selectOne(1L, "m1").executeAsOne().id
        backupManager.importBackup(backup)
        val second = db.libraryMangaQueries.selectOne(1L, "m1").executeAsOne()

        assertEquals(firstId, second.id)
        assertEquals(1, db.libraryMangaQueries.selectAll().executeAsList().size)
    }
}
