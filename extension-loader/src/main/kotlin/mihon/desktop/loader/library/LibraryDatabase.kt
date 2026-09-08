package mihon.desktop.loader.library

import app.cash.sqldelight.db.QueryResult
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
        return openDatabase(File(dataDir, "library.db")).also { instance = it }
    }

    /**
     * Opens the database at [dbFile], creating the full SQLDelight schema for
     * new files, and repairing/ensuring tables for existing ones. Split out from
     * [get] so migrations can be exercised against throwaway files in tests.
     */
    fun openDatabase(dbFile: File): MihonDesktopDatabase {
        val isNew = !dbFile.exists()

        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")

        if (isNew) {
            // Brand-new database — let SQLDelight create the full schema
            MihonDesktopDatabase.Schema.create(driver)
        } else {
            // Existing database — repair any legacy tables created by an older
            // ensureTables() whose hand-copied DDL had drifted from the .sq
            // source of truth, then ensure tables added in newer versions exist.
            // SQLDelight's Schema.create() uses bare CREATE TABLE (not IF NOT EXISTS)
            // so it throws "table already exists" on existing DBs. We handle this
            // manually with IF NOT EXISTS for each table.
            repairLegacyTables(driver)
            ensureTables(driver)
        }

        return MihonDesktopDatabase(driver)
    }

    /**
     * Migrates tables created by older versions of [ensureTables] whose DDL had
     * drifted from the `.sq` schema files:
     *
     * - `readingProgress` had `(page, totalPages, readAt)` columns and PK
     *   `(sourceId, chapterUrl)`; the real schema (`ReadingProgress.sq`) is
     *   `(chapterName, pageIndex, updatedAt)` with PK `(sourceId, mangaUrl)`.
     *   Because the legacy PK allowed one row per chapter, the data is collapsed
     *   to the most recent row per manga.
     * - `downloadedChapters` had a nullable `chapterNumber` column and no
     *   `pageCount` (`DownloadedChapter.sq` requires both).
     * - `updateHistory` versions predating the `packageName`/`jarFileName`
     *   columns; any such rows were recorded by the pre-diffing scheduler and
     *   are garbage anyway, so the table is dropped and recreated.
     *
     * Each migration step is idempotent (`DROP TABLE IF EXISTS` before every
     * `CREATE`), so a failure midway never leaves an orphaned `*_migrate` table
     * that would crash the next launch.
     */
    private fun repairLegacyTables(driver: JdbcSqliteDriver) {
        // readingProgress: wrong shape -> recreate with data carry-over
        if (hasColumn(driver, "readingProgress", "page") && !hasColumn(driver, "readingProgress", "chapterName")) {
            driver.execute(null, "DROP TABLE IF EXISTS readingProgress_migrate", 0)
            driver.execute(null, """CREATE TABLE readingProgress_migrate (
                sourceId INTEGER NOT NULL,
                mangaUrl TEXT NOT NULL,
                chapterUrl TEXT NOT NULL,
                chapterName TEXT NOT NULL,
                pageIndex INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY (sourceId, mangaUrl)
            )""", 0)
            // Legacy PK (sourceId, chapterUrl) stored one row per chapter read;
            // the target PK (sourceId, mangaUrl) allows only one row per manga,
            // so collapse to the most recently read chapter of each manga.
            driver.execute(null, """INSERT INTO readingProgress_migrate
                (sourceId, mangaUrl, chapterUrl, chapterName, pageIndex, updatedAt)
                SELECT sourceId, mangaUrl, chapterUrl, '', page, MAX(readAt)
                FROM readingProgress
                GROUP BY sourceId, mangaUrl""", 0)
            driver.execute(null, "DROP TABLE readingProgress", 0)
            driver.execute(null, "ALTER TABLE readingProgress_migrate RENAME TO readingProgress", 0)
        }

        // downloadedChapters: chapterNumber instead of pageCount -> recreate
        if (hasColumn(driver, "downloadedChapters", "chapterNumber") && !hasColumn(driver, "downloadedChapters", "pageCount")) {
            driver.execute(null, "DROP TABLE IF EXISTS downloadedChapters_migrate", 0)
            driver.execute(null, """CREATE TABLE downloadedChapters_migrate (
                sourceId INTEGER NOT NULL,
                mangaUrl TEXT NOT NULL,
                chapterUrl TEXT NOT NULL,
                chapterName TEXT NOT NULL,
                pageCount INTEGER NOT NULL DEFAULT 0,
                downloadedAt INTEGER NOT NULL,
                PRIMARY KEY (sourceId, chapterUrl)
            )""", 0)
            driver.execute(null, """INSERT INTO downloadedChapters_migrate
                (sourceId, mangaUrl, chapterUrl, chapterName, pageCount, downloadedAt)
                SELECT sourceId, mangaUrl, chapterUrl,
                    COALESCE(chapterName, CAST(chapterNumber AS TEXT), ''), 0, downloadedAt
                FROM downloadedChapters""", 0)
            driver.execute(null, "DROP TABLE downloadedChapters", 0)
            driver.execute(null, "ALTER TABLE downloadedChapters_migrate RENAME TO downloadedChapters", 0)
        }

        // updateHistory without packageName: rows predate chapter-diffing (all
        // chapters of every manga were recorded), so drop and start clean.
        if (hasTable(driver, "updateHistory") && !hasColumn(driver, "updateHistory", "packageName")) {
            driver.execute(null, "DROP TABLE updateHistory", 0)
        }
        // Defensive: any updateHistory missing the jarFileName column is from an
        // unknown intermediate shape -- the generated row mapper would fail at
        // runtime, so drop rather than limp along.
        if (hasTable(driver, "updateHistory") && !hasColumn(driver, "updateHistory", "jarFileName")) {
            driver.execute(null, "DROP TABLE updateHistory", 0)
        }

        // Column additions on existing tables (cheap, non-destructive):
        // - readChapters.chapterName (History screen shows chapter names)
        // - updateHistory.baseline (seeds recorded at library-add time)
        if (hasTable(driver, "readChapters") && !hasColumn(driver, "readChapters", "chapterName")) {
            driver.execute(null, "ALTER TABLE readChapters ADD COLUMN chapterName TEXT NOT NULL DEFAULT ''", 0)
        }
        if (hasTable(driver, "updateHistory") && !hasColumn(driver, "updateHistory", "baseline")) {
            driver.execute(null, "ALTER TABLE updateHistory ADD COLUMN baseline INTEGER NOT NULL DEFAULT 0", 0)
        }
    }

    // JdbcSqliteDriver is synchronous, so results are always QueryResult.Value.
    private fun <T> QueryResult<T>.awaitValue(): T = (this as QueryResult.Value).value

    private fun hasTable(driver: JdbcSqliteDriver, table: String): Boolean =
        driver.executeQuery(
            null,
            "PRAGMA table_info($table)",
            // SqlCursor.next() already yields QueryResult<Boolean> -- use it as-is.
            mapper = { cursor -> cursor.next() },
            parameters = 0,
        ).awaitValue()

    private fun hasColumn(driver: JdbcSqliteDriver, table: String, column: String): Boolean =
        driver.executeQuery(
            null,
            "PRAGMA table_info($table)",
            mapper = { cursor ->
                // PRAGMA table_info columns: cid(0), name(1), type(2), ...
                var found = false
                while (cursor.next().awaitValue()) {
                    if (cursor.getString(1) == column) {
                        found = true
                        break
                    }
                }
                QueryResult.Value(found)
            },
            parameters = 0,
        ).awaitValue()

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
            // NOTE: keep these DDLs in sync with the .sq files in
            // src/main/sqldelight/... -- they are the fallback for existing DBs.
            """CREATE TABLE IF NOT EXISTS downloadedChapters (
                sourceId INTEGER NOT NULL,
                mangaUrl TEXT NOT NULL,
                chapterUrl TEXT NOT NULL,
                chapterName TEXT NOT NULL,
                pageCount INTEGER NOT NULL DEFAULT 0,
                downloadedAt INTEGER NOT NULL,
                PRIMARY KEY (sourceId, chapterUrl)
            )""",
            """CREATE TABLE IF NOT EXISTS readChapters (
                sourceId INTEGER NOT NULL,
                mangaUrl TEXT NOT NULL,
                chapterUrl TEXT NOT NULL,
                chapterName TEXT NOT NULL DEFAULT '',
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
                chapterName TEXT NOT NULL,
                pageIndex INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY (sourceId, mangaUrl)
            )""",
            """CREATE TABLE IF NOT EXISTS updateHistory (
                sourceId INTEGER NOT NULL,
                mangaUrl TEXT NOT NULL,
                mangaTitle TEXT NOT NULL,
                thumbnailUrl TEXT,
                chapterUrl TEXT NOT NULL,
                chapterName TEXT NOT NULL,
                chapterNumber REAL,
                packageName TEXT NOT NULL,
                jarFileName TEXT NOT NULL,
                baseline INTEGER NOT NULL DEFAULT 0,
                fetchedAt INTEGER NOT NULL,
                PRIMARY KEY (sourceId, chapterUrl)
            )""",
        )
        for (sql in stmts) {
            driver.execute(null, sql, 0)
        }
    }
}
