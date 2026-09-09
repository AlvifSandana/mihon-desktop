package mihon.desktop.loader.prefs

import mihon.desktop.loader.log.Logger
import java.io.File
import java.util.Properties
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Simple key-value preferences stored in `~/.mihon-desktop/app.properties`.
 * Survives app restarts. Thread-safe for reads; writes are synchronized.
 *
 * Writes also notify registered [listeners] with the changed key, so screens
 * can observe a preference reactively instead of reading it once into
 * `remember` (see the reader's incognito handling).
 */
object AppPreferences {
    private const val TAG = "AppPreferences"
    private val file = File(System.getProperty("user.home"), ".mihon-desktop/app.properties")
    private val props = Properties()
    private val lock = Any()

    init {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            if (file.exists()) {
                file.inputStream().use { props.load(it) }
            }
        }
    }

    fun getLong(key: String, default: Long): Long = synchronized(lock) {
        props.getProperty(key)?.toLongOrNull() ?: default
    }

    fun setLong(key: String, value: Long) {
        synchronized(lock) {
            props.setProperty(key, value.toString())
            save()
        }
        notifyChanged(key)
    }

    fun getInt(key: String, default: Int): Int = synchronized(lock) {
        props.getProperty(key)?.toIntOrNull() ?: default
    }

    fun setInt(key: String, value: Int) {
        synchronized(lock) {
            props.setProperty(key, value.toString())
            save()
        }
        notifyChanged(key)
    }

    fun getString(key: String, default: String): String = synchronized(lock) {
        props.getProperty(key) ?: default
    }

    fun setString(key: String, value: String) {
        synchronized(lock) {
            props.setProperty(key, value)
            save()
        }
        notifyChanged(key)
    }

    fun getBoolean(key: String, default: Boolean): Boolean = synchronized(lock) {
        props.getProperty(key)?.toBooleanStrictOrNull() ?: default
    }

    fun setBoolean(key: String, value: Boolean) {
        synchronized(lock) {
            props.setProperty(key, value.toString())
            save()
        }
        notifyChanged(key)
    }

    // ── Change listeners ─────────────────────────────────────────────────
    // Callbacks run on the writing thread and must be cheap.

    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged(key: String) {
        for (listener in listeners) {
            // A throwing listener must never propagate to the writer.
            runCatching { listener(key) }
                .onFailure { Logger.e(TAG, "Preference listener failed for '$key'", it) }
        }
    }

    private fun save() {
        file.outputStream().use { props.store(it, "Mihon Desktop Preferences") }
    }

    // Well-known keys
    const val KEY_UPDATE_INTERVAL = "library.update.interval.minutes"
    const val KEY_UPDATE_ENABLED = "library.update.enabled"
    const val KEY_THEME = "app.theme"
    /**
     * UI language: "system" (follows the OS locale) or an explicit tag such
     * as "en" / "id" (see the app module's `i18n.Strings`). Applied live.
     */
    const val KEY_APP_LANGUAGE = "app.language"
    const val DEFAULT_APP_LANGUAGE = "system"
    /** Theme preset id (see app-module ThemePresets); the mode itself is [KEY_THEME]. */
    const val KEY_THEME_PRESET = "app.theme.preset"
    /** Whether notifications are also delivered through the OS (tray/banners). */
    const val KEY_SYSTEM_NOTIFICATIONS = "app.notifications.system"
    const val KEY_READING_DIRECTION = "reader.direction"
    /**
     * Reader brightness filter (global): when enabled, a black scrim dims the
     * reader content. Scrim alpha = 1 - brightness/100. Does NOT change the
     * OS/screen brightness.
     */
    const val KEY_READER_BRIGHTNESS_ENABLED = "reader.brightness.enabled"
    /** Reader brightness percent (0-100), only applied while the filter is enabled. */
    const val KEY_READER_BRIGHTNESS = "reader.brightness"
    /** Pages preloaded around the current one in the paged reader (0-10). */
    const val KEY_READER_PRELOAD = "reader.preload"
    /** Parallel chapter downloads in the DownloadQueue (1-4). Applied live. */
    const val KEY_DOWNLOAD_CONCURRENCY = "downloads.concurrency"
    const val KEY_DOWNLOADED_ONLY = "library.downloaded_only"
    const val KEY_INCOGNITO_MODE = "library.incognito_mode"
    const val KEY_UPDATES_LAST_SEEN = "updates.last_seen_timestamp"
    /** Selected library category filter: "all", "default", or "category:<id>". */
    const val KEY_LIBRARY_CATEGORY_FILTER = "library.category_filter"
    /**
     * DNS-over-HTTPS for all source/extension network traffic. Read once at
     * bootstrap to build NetworkHelper's OkHttpClient — requires an app
     * restart to apply (see platform-compat DohDns).
     */
    const val KEY_DOH_ENABLED = "network.doh.enabled"
    /** DoH provider: "google" or "cloudflare" (see platform-compat `DohDns`). */
    const val KEY_DOH_PROVIDER = "network.doh.provider"
    /** Automatic backups: enabled + interval in days (0 = disabled). */
    const val KEY_AUTO_BACKUP_ENABLED = "backup.auto.enabled"
    const val KEY_AUTO_BACKUP_INTERVAL_DAYS = "backup.auto.interval.days"
    /** Also write a Mihon-compatible .tachibk on every automatic backup. */
    const val KEY_AUTO_BACKUP_TACHIBK = "backup.auto.tachibk"
    /** Epoch ms of the last backup (manual or automatic). */
    const val KEY_AUTO_BACKUP_LAST_AT = "backup.auto.last_at"
    /**
     * Optional MyAnimeList OAuth client id override. Blank (default) uses
     * Mihon's public client id -- only set this if you registered your own
     * MAL API client. Read by TrackerManager on every MAL call.
     */
    const val KEY_TRACKER_MAL_CLIENT_ID = "tracker.mal.client_id"
    const val DEFAULT_UPDATE_INTERVAL = 60L
    const val DEFAULT_THEME = "system"
    const val DEFAULT_THEME_PRESET = "default"
    const val DEFAULT_READING_DIRECTION = "ltr"
    const val DEFAULT_DOWNLOAD_CONCURRENCY = 2
    const val DEFAULT_READER_BRIGHTNESS = 100
    const val DEFAULT_READER_PRELOAD = 4
    /** Off by default, like upstream Mihon. */
    const val DEFAULT_DOH_ENABLED = false
    const val DEFAULT_DOH_PROVIDER = "google"
    /** Automatic backups off by default; interval options are 1/2/3/7 days. */
    const val DEFAULT_AUTO_BACKUP_ENABLED = false
    const val DEFAULT_AUTO_BACKUP_INTERVAL_DAYS = 1
    const val DEFAULT_AUTO_BACKUP_TACHIBK = false
}
