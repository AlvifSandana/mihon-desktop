package mihon.desktop.loader.library

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

/**
 * Opens (creating on first run) the sqlite-backed library/reading-progress database at
 * `~/.mihon-desktop/library.db`.
 */
object LibraryDatabase {
    private var instance: MihonDesktopDatabase? = null

    @Synchronized
    fun get(): MihonDesktopDatabase {
        instance?.let { return it }

        val dataDir = File(System.getProperty("user.home"), ".mihon-desktop")
        dataDir.mkdirs()
        val dbFile = File(dataDir, "library.db")
        val isNew = !dbFile.exists()

        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")

        if (isNew) {
            // Brand-new database — let SQLDelight create the full schema
            MihonDesktopDatabase.Schema.create(driver)
        } else {
            // Existing database — ensure any tables added in newer versions exist.
            // SQLDelight's Schema.create() uses bare CREATE TABLE (not IF NOT EXISTS)
            // so it throws "table already exists" on existing DBs. We handle this
            // manually with IF NOT EXISTS for each table.
            ensureTables(driver)
        }

        return MihonDesktopDatabase(driver).also { instance = it }
    }

    private fun ensureTables(driver: JdbcSqliteDriver) {
        val stmts = listOf(
            """CREATE TABLE IF NOT EXISTS libraryManga (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                sourceId INTEGER NOT NULL,
                packageName TEXT NOT NULL,
                jarFileName TEXT NOT NULL,
                extensionName TEXT NOT NULL,
                mangaUrl TEXT NOT NULL,
                title TEXT NOT NULL,
                thumbnailUrl TEXT,
                author TEXT,
                addedAt INTEGER NOT NULL,
                UNIQUE (sourceId, mangaUrl)
            )""",
            """CREATE TABLE IF NOT EXISTS downloadedChapters (
                sourceId INTEGER NOT NULL,
                mangaUrl TEXT NOT NULL,
                chapterUrl TEXT NOT NULL,
                chapterNumber REAL,
                chapterName TEXT,
                downloadedAt INTEGER NOT NULL,
                PRIMARY KEY (sourceId, chapterUrl)
            )""",
            """CREATE TABLE IF NOT EXISTS readChapters (
                sourceId INTEGER NOT NULL,
                mangaUrl TEXT NOT NULL,
                chapterUrl TEXT NOT NULL,
                readAt INTEGER NOT NULL,
                PRIMARY KEY (sourceId, chapterUrl)
            )""",
            """CREATE TABLE IF NOT EXISTS readerPreferences (
                sourceId INTEGER NOT NULL,
                mangaUrl TEXT NOT NULL,
                webtoonMode INTEGER NOT NULL DEFAULT 0,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY (sourceId, mangaUrl)
            )""",
            """CREATE TABLE IF NOT EXISTS readingProgress (
                sourceId INTEGER NOT NULL,
                mangaUrl TEXT NOT NULL,
                chapterUrl TEXT NOT NULL,
                page INTEGER NOT NULL,
                totalPages INTEGER NOT NULL,
                readAt INTEGER NOT NULL,
                PRIMARY KEY (sourceId, chapterUrl)
            )""",
        )
        for (sql in stmts) {
            driver.execute(null, sql, 0)
        }
    }
}
