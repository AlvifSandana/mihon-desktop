package android.content

interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>?
    fun contains(key: String): Boolean
    fun getAll(): MutableMap<String, *>
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putStringSet(key: String, values: MutableSet<String>?): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
        fun commit(): Boolean
    }
}
