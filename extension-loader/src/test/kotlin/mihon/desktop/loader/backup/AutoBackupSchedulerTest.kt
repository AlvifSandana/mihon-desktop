package mihon.desktop.loader.backup

import mihon.desktop.loader.backup.tachibk.TachibkManager
import mihon.desktop.loader.library.LibraryDatabase
import mihon.desktop.loader.library.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Auto-backup rotation and interval logic. The interval decision is a pure
 * function ([AutoBackupScheduler.isBackupDue]) so it is tested with fake
 * timestamps instead of touching the real AppPreferences file in user.home.
 */
class AutoBackupSchedulerTest {

    @get:Rule
    val folder = TemporaryFolder()

    // ── Rotation ────────────────────────────────────────────────────────

    @Test
    fun `rotate keeps newest five and deletes the oldest`() {
        val dir = folder.newFolder("auto")
        for (i in 1..6) {
            File(dir, "auto-backup-2024010${i}_000000.json").writeText("{}")
        }
        val victims = AutoBackupScheduler.rotate(dir, "auto-backup", "json", keep = 5)
        assertEquals(1, victims.size)
        assertEquals("auto-backup-20240101_000000.json", victims[0].name)
        val remaining = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(
            listOf(
                "auto-backup-20240102_000000.json",
                "auto-backup-20240103_000000.json",
                "auto-backup-20240104_000000.json",
                "auto-backup-20240105_000000.json",
                "auto-backup-20240106_000000.json",
            ),
            remaining,
        )
    }

    @Test
    fun `rotate is a no-op at or below the keep limit`() {
        val dir = folder.newFolder("auto")
        for (i in 1..5) {
            File(dir, "auto-backup-2024010${i}_000000.json").writeText("{}")
        }
        assertEquals(0, AutoBackupScheduler.rotate(dir, "auto-backup", "json", keep = 5).size)
        assertEquals(5, dir.listFiles()!!.size)
    }

    @Test
    fun `rotate ignores other prefixes and extensions`() {
        val dir = folder.newFolder("auto")
        File(dir, "auto-backup-20240101_000000.json").writeText("{}")
        File(dir, "auto-tachibk-20240101_000000.tachibk").writeBytes(byteArrayOf(1))
        File(dir, "mihon-desktop-backup-20240101_000000.json").writeText("{}")
        File(dir, "notes.txt").writeText("x")
        // Rotating JSON backups must not touch tachibk or foreign files.
        AutoBackupScheduler.rotate(dir, "auto-backup", "json", keep = 0)
        val remaining = dir.listFiles()!!.map { it.name }.toSet()
        assertEquals(
            setOf(
                "auto-tachibk-20240101_000000.tachibk",
                "mihon-desktop-backup-20240101_000000.json",
                "notes.txt",
            ),
            remaining,
        )
    }

    // ── Interval logic (fake clock) ─────────────────────────────────────

    private val day = TimeUnit.DAYS.toMillis(1)

    @Test
    fun `interval zero means disabled`() {
        assertFalse(AutoBackupScheduler.isBackupDue(nowMs = 100 * day, lastAtMs = 0, intervalDays = 0))
    }

    @Test
    fun `never backed up is immediately due`() {
        assertTrue(AutoBackupScheduler.isBackupDue(nowMs = day, lastAtMs = 0, intervalDays = 1))
    }

    @Test
    fun `fresh backup is not due`() {
        val now = 100 * day
        assertFalse(AutoBackupScheduler.isBackupDue(nowMs = now, lastAtMs = now - 2, intervalDays = 1))
    }

    @Test
    fun `backup older than the interval is due`() {
        val now = 100 * day
        // Exactly one day old: due (>= semantics, matches "age ≥ interval").
        assertTrue(AutoBackupScheduler.isBackupDue(nowMs = now, lastAtMs = now - day, intervalDays = 1))
        // One ms short: not due.
        assertFalse(AutoBackupScheduler.isBackupDue(nowMs = now, lastAtMs = now - day + 1, intervalDays = 1))
        // 3-day interval, 2-day-old backup: not due.
        assertFalse(AutoBackupScheduler.isBackupDue(nowMs = now, lastAtMs = now - 2 * day, intervalDays = 3))
        // 3-day interval, 4-day-old backup: due.
        assertTrue(AutoBackupScheduler.isBackupDue(nowMs = now, lastAtMs = now - 4 * day, intervalDays = 3))
        // 7-day interval, 8-day-old backup: due.
        assertTrue(AutoBackupScheduler.isBackupDue(nowMs = now, lastAtMs = now - 8 * day, intervalDays = 7))
    }

    // ── Backup cycle (partial failure) ──────────────────────────────────

    @Test
    fun `tachibk export failure still rotates and notifies`() {
        // Hermetic notification capture: in-app list only, no OS delivery.
        NotificationManager.setSystemNotifier(null)
        NotificationManager.clear()
        try {
            val dir = folder.newFolder("auto")
            // Six stale backups of each kind: rotation must trim both to five.
            for (i in 1..6) {
                File(dir, "auto-backup-2024010${i}_000000.json").writeText("{}")
                File(dir, "auto-tachibk-2024010${i}_000000.tachibk").writeBytes(byteArrayOf(1))
            }

            // Empty library: the JSON backup succeeds (it allows zero manga)
            // but the .tachibk export throws (mirrors Mihon's BackupCreator)
            // — exactly the partial-failure case.
            val db = LibraryDatabase.openDatabase(folder.newFile("library.db"))
            val scheduler = AutoBackupScheduler(
                backupManager = BackupManager(database = db, onBackupActivity = {}),
                tachibkManager = TachibkManager(
                    database = db,
                    installedSourcesProvider = { emptyMap() },
                    onBackupActivity = {},
                ),
            )

            val json = scheduler.runBackup(dir = dir, tachibkEnabled = true)

            // The cycle reported success (JSON file written + returned)...
            assertNotNull(json)
            assertTrue(json!!.name.startsWith("auto-backup-"))
            // ...rotation ran for BOTH prefixes despite the tachibk failure:
            // JSON 6 old + 1 new = 7 -> keep 5; tachibk 6 old -> keep 5.
            assertEquals(5, dir.listFiles { f -> f.name.endsWith(".json") }!!.size)
            assertEquals(5, dir.listFiles { f -> f.name.endsWith(".tachibk") }!!.size)
            // ...and the user was still notified (key-based notify: the key is
            // stored raw and resolved by the app's resolver seam at display
            // time; unset here, so the raw key is what's stored).
            assertTrue(NotificationManager.notifications.any { it.title == "notif_auto_backup_title" })
            assertTrue(
                NotificationManager.notifications.any { it.messageArgs == listOf("auto") }
            )
        } finally {
            NotificationManager.setSystemNotifier(null)
            NotificationManager.clear()
        }
    }
}
