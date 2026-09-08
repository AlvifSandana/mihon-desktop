package mihon.desktop.loader.cache

import java.io.File
import java.security.MessageDigest

/**
 * Simple LRU disk cache for images.
 * Stores images as files in `~/.mihon-desktop/image-cache/`.
 * Evicts least-recently-used files when cache exceeds [maxSizeBytes].
 */
class ImageCache(
    private val cacheDir: File = File(System.getProperty("user.home"), ".mihon-desktop/image-cache"),
    private val maxSizeBytes: Long = 500L * 1024 * 1024, // 500 MB default
) {
    init {
        cacheDir.mkdirs()
    }

    /**
     * Get cached image bytes for [url], or null if not cached.
     * Updates access time on hit.
     */
    fun get(url: String): ByteArray? {
        val file = fileFor(url)
        if (!file.exists()) return null
        // Update access time by touching the file
        file.setLastModified(System.currentTimeMillis())
        return runCatching { file.readBytes() }.getOrNull()
    }

    /**
     * Put image bytes into cache for [url].
     * Evicts old entries if cache exceeds max size.
     */
    fun put(url: String, data: ByteArray) {
        val file = fileFor(url)
        file.writeBytes(data)
        evictIfNeeded()
    }

    /**
     * Check if [url] is cached.
     */
    fun contains(url: String): Boolean {
        return fileFor(url).exists()
    }

    /**
     * Remove cached entry for [url].
     */
    fun remove(url: String) {
        fileFor(url).delete()
    }

    /**
     * Clear entire cache.
     */
    fun clear() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Current cache size in bytes.
     */
    fun size(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Number of cached entries.
     */
    fun count(): Int {
        return cacheDir.listFiles()?.size ?: 0
    }

    private fun fileFor(url: String): File {
        val hash = md5(url)
        return File(cacheDir, "$hash.jpg")
    }

    private fun evictIfNeeded() {
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var totalSize = files.sumOf { it.length() }
        for (file in files) {
            if (totalSize <= maxSizeBytes) break
            val fileSize = file.length()
            if (file.delete()) {
                totalSize -= fileSize
            }
        }
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
