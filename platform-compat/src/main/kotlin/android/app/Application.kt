package android.app

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Desktop stand-in for android.app.Application/Context. Preferences persist to real files
 * under [dataDir] (default `~/.mihon-desktop`) instead of living only in memory, so
 * ConfigurableSource.getSourcePreferences() and extension trust survive a restart.
 */
open class Application(
    private val dataDir: File = File(System.getProperty("user.home"), ".mihon-desktop"),
) : Context {
    override val filesDir: File = File(dataDir, "files").apply { mkdirs() }
    override val cacheDir: File = File(dataDir, "cache").apply { mkdirs() }
    private val prefsDir: File = File(dataDir, "prefs").apply { mkdirs() }

    private val openPrefs = ConcurrentHashMap<String, FileSharedPreferences>()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        return openPrefs.getOrPut(name) { FileSharedPreferences(File(prefsDir, "$name.properties")) }
    }
}

/** Separator between elements of an encoded string-set value. Not valid in a normal string. */
private const val SET_SEPARATOR = '\u0001'

/**
 * A [SharedPreferences] backed by a `java.util.Properties` file. Values are stored with a
 * one-character type tag (`S`/`I`/`L`/`B`/`T`) so they round-trip through the string-only
 * Properties format.
 */
private class FileSharedPreferences(private val file: File) : SharedPreferences {
    private val lock = Any()
    private val props = java.util.Properties().apply {
        if (file.exists()) file.inputStream().use { load(it) }
    }

    private fun raw(key: String): String? = synchronized(lock) { props.getProperty(key) }

    override fun getString(key: String, defValue: String?): String? {
        val v = raw(key) ?: return defValue
        return if (v.startsWith("S:")) v.substring(2) else defValue
    }

    override fun getInt(key: String, defValue: Int): Int {
        val v = raw(key) ?: return defValue
        return if (v.startsWith("I:")) v.substring(2).toIntOrNull() ?: defValue else defValue
    }

    override fun getLong(key: String, defValue: Long): Long {
        val v = raw(key) ?: return defValue
        return if (v.startsWith("L:")) v.substring(2).toLongOrNull() ?: defValue else defValue
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        val v = raw(key) ?: return defValue
        return if (v.startsWith("B:")) v.substring(2).toBooleanStrictOrNull() ?: defValue else defValue
    }

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
        val v = raw(key) ?: return defValues
        if (!v.startsWith("T:")) return defValues
        val payload = v.substring(2)
        return if (payload.isEmpty()) mutableSetOf() else payload.split(SET_SEPARATOR).toMutableSet()
    }

    override fun contains(key: String): Boolean = synchronized(lock) { props.containsKey(key) }

    override fun getAll(): MutableMap<String, Any?> = synchronized(lock) {
        props.entries.associate { (k, v) -> k.toString() to decodeAny(v.toString()) }.toMutableMap()
    }

    private fun decodeAny(v: String): Any? = when {
        v.startsWith("S:") -> v.substring(2)
        v.startsWith("I:") -> v.substring(2).toIntOrNull()
        v.startsWith("L:") -> v.substring(2).toLongOrNull()
        v.startsWith("B:") -> v.substring(2).toBooleanStrictOrNull()
        v.startsWith("T:") -> v.substring(2).split(SET_SEPARATOR).toSet()
        else -> null
    }

    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, String>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?) = putOrRemove(key, value?.let { "S:$it" })
        override fun putInt(key: String, value: Int) = putOrRemove(key, "I:$value")
        override fun putLong(key: String, value: Long) = putOrRemove(key, "L:$value")
        override fun putBoolean(key: String, value: Boolean) = putOrRemove(key, "B:$value")
        override fun putStringSet(key: String, values: MutableSet<String>?) =
            putOrRemove(key, values?.let { "T:" + it.joinToString(SET_SEPARATOR.toString()) })

        private fun putOrRemove(key: String, encoded: String?): SharedPreferences.Editor {
            if (encoded == null) removals.add(key) else pending[key] = encoded
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            removals.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun apply() {
            commit()
        }

        override fun commit(): Boolean = synchronized(lock) {
            if (clearAll) props.clear()
            removals.forEach { props.remove(it) }
            pending.forEach { (k, v) -> props.setProperty(k, v) }
            file.parentFile?.mkdirs()
            file.outputStream().use { props.store(it, null) }
            true
        }
    }
}
