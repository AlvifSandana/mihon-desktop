package mihon.desktop.loader.library

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.sql.DriverManager
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * Verifies [LibraryDatabase.openDatabase] repairs databases created by older
 * versions whose hand-copied `ensureTables()` DDL had drifted from the `.sq`
 * schema files, and that existing data survives the repair.
 */
class LibraryDatabaseMigrationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun exec(dbFile: java.io.File, vararg statements: String) {
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                statements.forEach(st::execute)
            }
        }
    }

    @Test
    fun `legacy readingProgress is collapsed to latest row per manga`() {
        val dbFile = folder.newFile("library.db")
        exec(
            dbFile,
            // Legacy drifted shape: (page, totalPages, readAt), PK (sourceId, chapterUrl)
            """CREATE TABLE readingProgress (
                sourceId INTEGER NOT NULL, mangaUrl TEXT NOT NULL, chapterUrl TEXT NOT NULL,
                page INTEGER NOT NULL, totalPages INTEGER NOT NULL, readAt INTEGER NOT NULL,
                PRIMARY KEY (sourceId, chapterUrl))""",
            """INSERT INTO readingProgress VALUES (1, 'm1', 'c1', 0, 20, 100)""",
            """INSERT INTO readingProgress VALUES (1, 'm1', 'c2', 5, 20, 200)""",
        )

        val db = LibraryDatabase.openDatabase(dbFile)

        // New shape is queryable and the most recent read of the manga survived
        val progress = db.readingProgressQueries.selectForManga(1L, "m1").executeAsOneOrNull()
        assertNotNull(progress)
        assertEquals("c2", progress!!.chapterUrl)
        assertEquals(5L, progress.pageIndex)
    }

    @Test
    fun `legacy downloadedChapters gains pageCount with chapterName carried over`() {
        val dbFile = folder.newFile("library.db")
        exec(
            dbFile,
            // Legacy drifted shape: chapterNumber, no pageCount
            """CREATE TABLE downloadedChapters (
                sourceId INTEGER NOT NULL, mangaUrl TEXT NOT NULL, chapterUrl TEXT NOT NULL,
                chapterNumber REAL, chapterName TEXT, downloadedAt INTEGER NOT NULL,
                PRIMARY KEY (sourceId, chapterUrl))""",
            """INSERT INTO downloadedChapters VALUES (1, 'm1', 'c1', 1.5, 'Chapter 1.5', 123)""",
            """INSERT INTO downloadedChapters VALUES (1, 'm1', 'c2', 2.0, NULL, 456)""",
        )

        val db = LibraryDatabase.openDatabase(dbFile)

        val rows = db.downloadedChapterQueries.selectForManga(1L, "m1").executeAsList()
            .sortedBy { it.chapterUrl }
        assertEquals(2, rows.size)
        assertEquals("Chapter 1.5", rows[0].chapterName)
        // chapterName NULL fell back to the number, then to empty string
        assertEquals("2.0", rows[1].chapterName)
    }

    @Test
    fun `legacy updateHistory without packageName is dropped`() {
        val dbFile = folder.newFile("library.db")
        exec(
            dbFile,
            """CREATE TABLE updateHistory (
                sourceId INTEGER NOT NULL, mangaUrl TEXT NOT NULL, mangaTitle TEXT NOT NULL,
                thumbnailUrl TEXT, chapterUrl TEXT NOT NULL, chapterName TEXT NOT NULL,
                chapterNumber REAL, fetchedAt INTEGER NOT NULL,
                PRIMARY KEY (sourceId, chapterUrl))""",
            """INSERT INTO updateHistory VALUES (1, 'm1', 'Manga', NULL, 'c1', 'C1', 1.0, 1)""",
        )

        val db = LibraryDatabase.openDatabase(dbFile)

        // Pre-diffing rows were garbage (every chapter of every manga) -- dropped
        assertTrue(db.updateHistoryQueries.selectAll().executeAsList().isEmpty())
    }

    @Test
    fun `current updateHistory gains baseline column without losing rows`() {
        val dbFile = folder.newFile("library.db")
        exec(
            dbFile,
            // Shape from the previous release: packageName present, no baseline
            """CREATE TABLE updateHistory (
                sourceId INTEGER NOT NULL, mangaUrl TEXT NOT NULL, mangaTitle TEXT NOT NULL,
                thumbnailUrl TEXT, chapterUrl TEXT NOT NULL, chapterName TEXT NOT NULL,
                chapterNumber REAL, packageName TEXT NOT NULL, jarFileName TEXT NOT NULL,
                fetchedAt INTEGER NOT NULL,
                PRIMARY KEY (sourceId, chapterUrl))""",
            """INSERT INTO updateHistory VALUES (1, 'm1', 'Manga', NULL, 'c1', 'C1', 1.0, 'pkg', 'pkg-v1.jar', 111)""",
        )

        val db = LibraryDatabase.openDatabase(dbFile)

        val rows = db.updateHistoryQueries.selectAll().executeAsList()
        assertEquals(1, rows.size)
        assertEquals("c1", rows[0].chapterUrl)
        assertEquals("ALTER default must mark existing rows as real updates", 0L, rows[0].baseline)
    }

    @Test
    fun `readChapters gains chapterName with empty default for legacy rows`() {
        val dbFile = folder.newFile("library.db")
        exec(
            dbFile,
            """CREATE TABLE readChapters (
                sourceId INTEGER NOT NULL, mangaUrl TEXT NOT NULL, chapterUrl TEXT NOT NULL,
                readAt INTEGER NOT NULL,
                PRIMARY KEY (sourceId, chapterUrl))""",
            """INSERT INTO readChapters VALUES (1, 'm1', 'c1', 42)""",
        )

        val db = LibraryDatabase.openDatabase(dbFile)

        val row = db.readChapterQueries.selectOne(1L, "c1").executeAsOne()
        assertEquals("", row.chapterName)
    }

    @Test
    fun `repair is idempotent across repeated opens`() {
        val dbFile = folder.newFile("library.db")
        exec(
            dbFile,
            """CREATE TABLE readingProgress (
                sourceId INTEGER NOT NULL, mangaUrl TEXT NOT NULL, chapterUrl TEXT NOT NULL,
                page INTEGER NOT NULL, totalPages INTEGER NOT NULL, readAt INTEGER NOT NULL,
                PRIMARY KEY (sourceId, chapterUrl))""",
            """INSERT INTO readingProgress VALUES (1, 'm1', 'c1', 3, 20, 100)""",
        )

        // Open several times: each open must be a no-op after the first repair
        repeat(3) {
            val db = LibraryDatabase.openDatabase(dbFile)
            assertEquals("c1", db.readingProgressQueries.selectForManga(1L, "m1").executeAsOne().chapterUrl)
        }
    }
}
