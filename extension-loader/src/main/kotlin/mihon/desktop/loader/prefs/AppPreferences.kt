package mihon.desktop.loader.prefs

import java.io.File
import java.util.Properties

/**
 * Simple key-value preferences stored in `~/.mihon-desktop/app.properties`.
 * Survives app restarts. Thread-safe for reads; writes are synchronized.
 */
object AppPreferences {
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

    fun setLong(key: String, value: Long) = synchronized(lock) {
        props.setProperty(key, value.toString())
        save()
    }

    fun getInt(key: String, default: Int): Int = synchronized(lock) {
        props.getProperty(key)?.toIntOrNull() ?: default
    }

    fun setInt(key: String, value: Int) = synchronized(lock) {
        props.setProperty(key, value.toString())
        save()
    }

    fun getString(key: String, default: String): String = synchronized(lock) {
        props.getProperty(key) ?: default
    }

    fun setString(key: String, value: String) = synchronized(lock) {
        props.setProperty(key, value)
        save()
    }

    fun getBoolean(key: String, default: Boolean): Boolean = synchronized(lock) {
        props.getProperty(key)?.toBooleanStrictOrNull() ?: default
    }

    fun setBoolean(key: String, value: Boolean) = synchronized(lock) {
        props.setProperty(key, value.toString())
        save()
    }

    private fun save() {
        file.outputStream().use { props.store(it, "Mihon Desktop Preferences") }
    }

    // Well-known keys
    const val KEY_UPDATE_INTERVAL = "library.update.interval.minutes"
    const val KEY_UPDATE_ENABLED = "library.update.enabled"
    const val KEY_THEME = "app.theme"
    const val KEY_READING_DIRECTION = "reader.direction"
    const val DEFAULT_UPDATE_INTERVAL = 60L
    const val DEFAULT_THEME = "system"
    const val DEFAULT_READING_DIRECTION = "ltr"
}
