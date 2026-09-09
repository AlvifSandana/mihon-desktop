package mihon.desktop.loader.tracker

import kotlinx.coroutines.runBlocking
import mihon.desktop.loader.backup.BackupManager
import mihon.desktop.loader.backup.tachibk.TachibkManager
import mihon.desktop.loader.library.LibraryDatabase
import mihon.desktop.loader.library.LibraryRepository
import mihon.desktop.loader.library.MihonDesktopDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * [TrackerAuthStore] persistence semantics and the guarantee that tracker
 * tokens never end up inside a backup (JSON v1 or .tachibk).
 */
class TrackerAuthStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var authFile: File
    private lateinit var auth: TrackerAuthStore

    @Before
    fun setUp() {
        authFile = File(folder.root, "tracker-auth.properties")
        auth = TrackerAuthStore(authFile)
    }

    @Test
    fun `put writes through and a new instance reads back`() {
        auth.put("anilist.token", "tok-1")
        auth.put("mal.accessToken", "mal-tok")

        val reloaded = TrackerAuthStore(authFile)
        assertEquals("tok-1", reloaded.get("anilist.token"))
        assertEquals("mal-tok", reloaded.get("mal.accessToken"))
        assertTrue(authFile.exists())
    }

    @Test
    fun `remove deletes a single key`() {
        auth.put("mal.accessToken", "a")
        auth.put("mal.refreshToken", "r")
        auth.remove("mal.accessToken")

        assertNull(auth.get("mal.accessToken"))
        assertEquals("r", auth.get("mal.refreshToken"))
    }

    @Test
    fun `clearPrefix drops only that tracker's keys`() {
        auth.put("anilist.token", "a")
        auth.put("anilist.userId", "1")
        auth.put("mal.accessToken", "m")
        auth.clearPrefix("anilist.")

        assertNull(auth.get("anilist.token"))
        assertNull(auth.get("anilist.userId"))
        assertEquals("m", auth.get("mal.accessToken"))
    }

    @Test
    fun `clear wipes the whole file`() {
        auth.put("anilist.token", "a")
        auth.clear()
        assertNull(auth.get("anilist.token"))
        // The file itself is gone -- nothing left to leak.
        assertFalse(authFile.exists())
    }

    @Test
    fun `file is owner-only on posix systems`() {
        auth.put("anilist.token", "secret")
        if (File("/bin/sh").exists() && System.getProperty("os.name")?.lowercase()?.contains("win") != true) {
            val perms = Files.getPosixFilePermissions(authFile.toPath())
            assertEquals(PosixFilePermissions.fromString("rw-------"), perms)
        }
    }

    // ── backup exclusion ────────────────────────────────────────────────

    private fun libraryWithOneManga(): MihonDesktopDatabase {
        val db = LibraryDatabase.openDatabase(File(folder.root, "library.db"))
        runBlocking {
            LibraryRepository(db).add(
                sourceId = 1,
                packageName = "pkg",
                jarFileName = "pkg.jar",
                extensionName = "Ext",
                mangaUrl = "https://m/1",
                title = "Manga",
                thumbnailUrl = null,
                author = null,
            )
        }
        return db
    }

    @Test
    fun `tokens never appear in a JSON backup`() = runBlocking {
        auth.put("anilist.token", "SECRET-ANILIST-TOKEN")
        auth.put("mal.accessToken", "SECRET-MAL-TOKEN")
        val db = libraryWithOneManga()
        val manager = BackupManager(db, onBackupActivity = {})

        val backup = manager.exportBackup(dir = folder.newFolder("json-backups"))

        val text = backup.readText()
        assertTrue(text.contains("\"libraryManga\"")) // backup is real, not empty
        assertFalse("JSON backup must not contain tracker tokens", text.contains("SECRET-ANILIST-TOKEN"))
        assertFalse("JSON backup must not contain tracker tokens", text.contains("SECRET-MAL-TOKEN"))
        assertFalse("backup must not reference the auth store file", text.contains("tracker-auth"))
    }

    @Test
    fun `tokens never appear in a tachibk export`() = runBlocking {
        auth.put("anilist.token", "SECRET-ANILIST-TOKEN")
        auth.put("mal.refreshToken", "SECRET-MAL-REFRESH")
        val db = libraryWithOneManga()
        val manager = TachibkManager(
            database = db,
            installedSourcesProvider = { emptyMap() },
            onBackupActivity = {},
        )

        val target = File(folder.root, "export.tachibk")
        manager.export(target)

        // The .tachibk is gzipped protobuf; search the raw bytes for the
        // token strings -- a leak would embed them verbatim.
        val bytes = target.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse("tachibk must not contain tracker tokens", text.contains("SECRET-ANILIST-TOKEN"))
        assertFalse("tachibk must not contain tracker tokens", text.contains("SECRET-MAL-REFRESH"))
    }
}
