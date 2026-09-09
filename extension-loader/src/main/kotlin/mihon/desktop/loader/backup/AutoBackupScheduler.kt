package mihon.desktop.loader.backup

import kotlinx.coroutines.runBlocking
import mihon.desktop.loader.backup.tachibk.TachibkManager
import mihon.desktop.loader.library.NotificationManager
import mihon.desktop.loader.log.Logger
import mihon.desktop.loader.prefs.AppPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "AutoBackupScheduler"

/**
 * Automatic backups, patterned after LibraryUpdateScheduler: a single-thread
 * daemon scheduler checks on app start (and hourly afterwards — cheap
 * timestamp comparisons) whether the newest backup is older than the
 * configured interval, and if so writes:
 *
 * - a JSON backup (the full-fidelity mihon-desktop format: library, reading
 *   progress, download metadata) into `~/.mihon-desktop/backups/auto/`, and
 * - optionally, when "Back up Mihon format too" is on, a Mihon-compatible
 *   `.tachibk` export next to it.
 *
 * Each kind keeps its newest [MAX_KEEP] files; older ones are rotated away
 * (oldest deleted first). Manual exports/imports refresh the timestamp too
 * (see BackupManager/TachibkManager), so an auto backup never fires right
 * after a manual one.
 *
 * Off by default: `backup.auto.enabled = false`.
 */
class AutoBackupScheduler(
    private val backupManager: BackupManager = BackupManager(),
    private val tachibkManager: TachibkManager = TachibkManager(),
    /** Clock seam for tests. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "auto-backup-scheduler").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)

    /**
     * Start the periodic due-check. No-op if already running.
     *
     * [stop] is terminal — it shuts the executor down, and a stopped
     * scheduler cannot be restarted (same as LibraryUpdateScheduler);
     * create a new instance instead.
     */
    fun start() {
        if (running.compareAndSet(false, true)) {
            executor.scheduleWithFixedDelay(
                { checkAndBackup() },
                INITIAL_DELAY_MINUTES,
                CHECK_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            )
        }
    }

    fun stop() {
        if (running.compareAndSet(true, false)) {
            executor.shutdownNow()
        }
    }

    /**
     * Runs one backup now if the newest one is older than the interval
     * (and the feature is enabled). Returns the file written, or null.
     */
    fun checkAndBackup(): File? {
        if (!enabled()) return null
        if (!isBackupDue(clock(), lastBackupAt(), intervalDays())) return null

        return runBackup(
            dir = autoDir,
            tachibkEnabled = AppPreferences.getBoolean(
                AppPreferences.KEY_AUTO_BACKUP_TACHIBK,
                AppPreferences.DEFAULT_AUTO_BACKUP_TACHIBK,
            ),
        )
    }

    /**
     * One backup cycle: JSON export (+ optional `.tachibk`), rotation,
     * notification. A `.tachibk` failure is partial, not fatal — the JSON
     * backup already succeeded, so rotation and the notification always run
     * and the error is logged; only a JSON failure fails the whole cycle.
     * Split out (with [dir]/[tachibkEnabled] seams) so tests can drive it
     * without touching the real preferences or `~/.mihon-desktop`.
     */
    internal fun runBackup(dir: File, tachibkEnabled: Boolean): File? {
        return try {
            val json = runBlocking { backupManager.exportBackup(dir = dir, prefix = JSON_PREFIX) }

            var tachibkError: Throwable? = null
            if (tachibkEnabled) {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(clock()))
                try {
                    runBlocking {
                        tachibkManager.export(File(dir, "$TACHIBK_PREFIX-$timestamp.tachibk"))
                    }
                } catch (t: Throwable) {
                    tachibkError = t
                }
            }

            // Always rotate + notify: a failed .tachibk must not leave a
            // growing pile of old JSON backups (or a silent cycle).
            rotate(dir, JSON_PREFIX, "json", MAX_KEEP)
            rotate(dir, TACHIBK_PREFIX, "tachibk", MAX_KEEP)
            NotificationManager.notify("notif_auto_backup_title", "notif_auto_backup_message", dir.name)

            tachibkError?.let {
                Logger.e(TAG, "Automatic .tachibk backup failed (JSON backup succeeded)", it)
            }
            json
        } catch (t: Throwable) {
            Logger.e(TAG, "Automatic backup failed", t)
            null
        }
    }

    private fun enabled(): Boolean =
        AppPreferences.getBoolean(AppPreferences.KEY_AUTO_BACKUP_ENABLED, AppPreferences.DEFAULT_AUTO_BACKUP_ENABLED)

    private fun intervalDays(): Int =
        AppPreferences.getInt(AppPreferences.KEY_AUTO_BACKUP_INTERVAL_DAYS, AppPreferences.DEFAULT_AUTO_BACKUP_INTERVAL_DAYS)

    private fun lastBackupAt(): Long =
        AppPreferences.getLong(AppPreferences.KEY_AUTO_BACKUP_LAST_AT, 0L)

    companion object {
        /** Auto backups live in their own subdirectory. */
        val autoDir: File = File(System.getProperty("user.home"), ".mihon-desktop/backups/auto")
        const val MAX_KEEP = 5
        const val JSON_PREFIX = "auto-backup"
        const val TACHIBK_PREFIX = "auto-tachibk"
        /** First due-check after start (minutes), then hourly. */
        private const val INITIAL_DELAY_MINUTES = 1L
        private const val CHECK_INTERVAL_MINUTES = 60L

        /**
         * Pure interval decision (seam for tests): a backup is due when an
         * interval is configured (> 0 days) and the last backup's age has
         * reached it. A never-backed-up library (lastAt = 0) is always due.
         */
        fun isBackupDue(nowMs: Long, lastAtMs: Long, intervalDays: Int): Boolean {
            if (intervalDays <= 0) return false
            return nowMs - lastAtMs >= TimeUnit.DAYS.toMillis(intervalDays.toLong())
        }

        /**
         * Keeps the newest [keep] files whose name matches
         * `<prefix>-<timestamp>.<extension>`, deleting the rest (oldest
         * first — the timestamp format is lexicographically ordered).
         * Returns the deleted files (for tests).
         */
        fun rotate(dir: File, prefix: String, extension: String, keep: Int): List<File> {
            val files = dir.listFiles { f ->
                f.isFile && f.name.startsWith("$prefix-") && f.name.endsWith(".$extension")
            }?.sortedByDescending { it.name } ?: return emptyList()
            val victims = files.drop(keep)
            for (victim in victims) {
                if (!victim.delete()) {
                    Logger.w(TAG, "Could not rotate away old backup ${victim.name}")
                }
            }
            return victims
        }
    }
}
