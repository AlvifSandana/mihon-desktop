package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import eu.kanade.tachiyomi.network.AndroidCookieJar
import okhttp3.Interceptor
import okhttp3.Response

/**
 * KNOWN GAP (see docs/ARCHITECTURE.md "Known gaps"): upstream solves the Cloudflare
 * JS challenge with android.webkit.WebView. There is no desktop equivalent wired up
 * yet (a real fix needs an embedded browser engine, e.g. JCEF/KCEF, driving the
 * challenge page). This placeholder only exists so extensions that defensively assert
 * "CloudflareInterceptor must be present in default client" don't crash on startup;
 * requests that actually hit an active Cloudflare challenge will still fail.
 */
class CloudflareInterceptor(
    private val context: Context,
    private val cookieJar: AndroidCookieJar,
    private val defaultUserAgentProvider: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        return chain.proceed(chain.request())
    }
}
