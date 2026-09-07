package android.content

import java.io.File

interface Context {
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences

    /** Where per-source/app files should live -- backed by a real directory on desktop. */
    val filesDir: File

    /** A cache directory that's fine to clear -- backed by a real directory on desktop. */
    val cacheDir: File

    companion object {
        const val MODE_PRIVATE: Int = 0
    }
}
