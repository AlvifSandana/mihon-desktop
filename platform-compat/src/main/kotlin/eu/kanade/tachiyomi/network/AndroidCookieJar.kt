package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * Desktop stand-in for Mihon's AndroidCookieJar. Upstream backs this with
 * android.webkit.CookieManager (so cookies are shared with an Android WebView);
 * here it's a plain in-memory jar keyed by host, which is actually a more
 * faithful implementation for a desktop build with no WebView.
 */
class AndroidCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val bucket = store.getOrPut(url.host) { mutableListOf() }
        synchronized(bucket) {
            cookies.forEach { cookie ->
                bucket.removeAll { it.name == cookie.name && it.path == cookie.path }
                bucket.add(cookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = get(url)

    fun get(url: HttpUrl): List<Cookie> {
        val bucket = store[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        return synchronized(bucket) {
            bucket.removeAll { it.expiresAt < now }
            bucket.filter { it.matches(url) }
        }
    }

    fun remove(url: HttpUrl, cookieNames: List<String>? = null, maxAge: Int = -1): Int {
        val bucket = store[url.host] ?: return 0
        return synchronized(bucket) {
            val toRemove = bucket.filter { cookieNames == null || it.name in cookieNames }
            bucket.removeAll(toRemove)
            toRemove.size
        }
    }

    fun removeAll() {
        store.clear()
    }
}
