package mihon.desktop.loader.tracker

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Stores tracker OAuth tokens in `~/.mihon-desktop/tracker-auth.properties`,
 * a plain properties file with owner-only permissions on POSIX systems.
 *
 * Deliberately NOT part of [mihon.desktop.loader.backup.BackupManager] or the
 * `.tachibk` interop -- both serialize the library database only, so tokens
 * never leave the machine in a backup (see TrackerAuthStoreTest for the
 * exclusion guarantee).
 *
 * Thread-safe: synchronized on every access, file rewritten atomically per put
 * (tokens change rarely -- a few bytes per login).
 */
class TrackerAuthStore(
    private val file: File = File(
        File(System.getProperty("user.home"), ".mihon-desktop"),
        "tracker-auth.properties",
    ),
) {
    private val lock = Any()

    fun get(key: String): String? = synchronized(lock) { readProps().getProperty(key) }

    fun put(key: String, value: String) = synchronized(lock) {
        val props = readProps()
        props.setProperty(key, value)
        writeProps(props)
    }

    fun remove(key: String) = synchronized(lock) {
        val props = readProps()
        if (props.remove(key) != null) writeProps(props)
    }

    /** Drops every key starting with [prefix] (e.g. "anilist." on logout). */
    fun clearPrefix(prefix: String) = synchronized(lock) {
        val props = readProps()
        val keys = props.stringPropertyNames().filter { it.startsWith(prefix) }
        if (keys.isNotEmpty()) {
            keys.forEach { props.remove(it) }
            writeProps(props)
        }
    }

    fun clear() = synchronized(lock) {
        if (file.exists()) file.delete()
    }

    private fun readProps(): java.util.Properties {
        val props = java.util.Properties()
        if (file.exists()) {
            file.inputStream().use { props.load(it) }
        }
        return props
    }

    private fun writeProps(props: java.util.Properties) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        // Create the temp file EMPTY, restrict it to owner-only, and only
        // then write the secrets -- never let token bytes touch a file with
        // umask-default (typically world-readable) permissions. A stale tmp
        // (e.g. crashed write) is truncated by the store() below.
        if (!tmp.exists()) Files.createFile(tmp.toPath())
        runCatching {
            Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
        tmp.outputStream().use { props.store(it, "Mihon Desktop tracker auth (secrets -- never backed up)") }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                // Keep tmp: it still holds the newest token bytes and the
                // next write truncates and reuses it. Deleting here would
                // lose the tokens outright -- file.delete() above already
                // removed the previous store, so the failure mode must be
                // "stale store", never "no store".
                throw IllegalStateException("Could not write tracker auth store at $file")
            }
        }
        runCatching {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
    }

    companion object {
        /** Shared production instance; tests construct their own with a temp file. */
        val shared: TrackerAuthStore by lazy { TrackerAuthStore() }
    }
}
