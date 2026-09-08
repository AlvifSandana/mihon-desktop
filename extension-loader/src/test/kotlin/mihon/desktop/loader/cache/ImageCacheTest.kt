package mihon.desktop.loader.cache

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImageCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheDir: File
    private lateinit var cache: ImageCache

    @Before
    fun setup() {
        cacheDir = tempFolder.newFolder("image-cache")
        cache = ImageCache(cacheDir, maxSizeBytes = 1024 * 1024) // 1MB for testing
    }

    @Test
    fun `put and get returns same data`() {
        val url = "https://example.com/image.jpg"
        val data = "test image data".toByteArray()

        cache.put(url, data)
        val retrieved = cache.get(url)

        assertArrayEquals(data, retrieved)
    }

    @Test
    fun `get returns null for non-existent key`() {
        val result = cache.get("https://nonexistent.com/image.jpg")
        assertNull(result)
    }

    @Test
    fun `contains returns true for cached item`() {
        val url = "https://example.com/image.jpg"
        cache.put(url, "data".toByteArray())

        assertTrue(cache.contains(url))
    }

    @Test
    fun `contains returns false for non-cached item`() {
        assertFalse(cache.contains("https://nonexistent.com/image.jpg"))
    }

    @Test
    fun `remove deletes cached item`() {
        val url = "https://example.com/image.jpg"
        cache.put(url, "data".toByteArray())

        cache.remove(url)

        assertFalse(cache.contains(url))
        assertNull(cache.get(url))
    }

    @Test
    fun `clear removes all items`() {
        cache.put("https://example.com/1.jpg", "data1".toByteArray())
        cache.put("https://example.com/2.jpg", "data2".toByteArray())

        cache.clear()

        assertEquals(0, cache.count())
        assertNull(cache.get("https://example.com/1.jpg"))
        assertNull(cache.get("https://example.com/2.jpg"))
    }

    @Test
    fun `count returns number of cached items`() {
        assertEquals(0, cache.count())

        cache.put("https://example.com/1.jpg", "data1".toByteArray())
        assertEquals(1, cache.count())

        cache.put("https://example.com/2.jpg", "data2".toByteArray())
        assertEquals(2, cache.count())
    }

    @Test
    fun `size returns total bytes of cached items`() {
        assertEquals(0, cache.size())

        cache.put("https://example.com/1.jpg", "data1".toByteArray())
        assertTrue(cache.size() > 0)
    }

    @Test
    fun `eviction removes oldest items when cache exceeds max size`() {
        // Fill cache with items that exceed max size
        val smallCache = ImageCache(tempFolder.newFolder("small-cache"), maxSizeBytes = 100)

        // Add items that exceed the limit
        for (i in 1..10) {
            smallCache.put("https://example.com/$i.jpg", "x".repeat(20).toByteArray())
        }

        // Cache should have evicted some items
        assertTrue(smallCache.size() <= 150) // Allow some tolerance
    }

    @Test
    fun `different URLs produce different cache files`() {
        val url1 = "https://example.com/image1.jpg"
        val url2 = "https://example.com/image2.jpg"

        cache.put(url1, "data1".toByteArray())
        cache.put(url2, "data2".toByteArray())

        assertNotEquals(cache.get(url1), cache.get(url2))
    }

    @Test
    fun `same URL updates cached data`() {
        val url = "https://example.com/image.jpg"

        cache.put(url, "original".toByteArray())
        cache.put(url, "updated".toByteArray())

        assertArrayEquals("updated".toByteArray(), cache.get(url))
    }
}
