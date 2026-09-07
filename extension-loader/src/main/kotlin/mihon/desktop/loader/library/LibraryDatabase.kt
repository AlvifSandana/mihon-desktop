package mihon.desktop.loader.library

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

/**
 * Opens (creating on first run) the sqlite-backed library/reading-progress database at
 * `~/.mihon-desktop/library.db`. A plain JDBC driver stands in for SQLDelight's Android
 * driver -- same schema/query code either way, since SQLDelight generates against the
 * `SqlDriver` interface, not a platform-specific implementation.
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
            MihonDesktopDatabase.Schema.create(driver)
        }

        return MihonDesktopDatabase(driver).also { instance = it }
    }
}
