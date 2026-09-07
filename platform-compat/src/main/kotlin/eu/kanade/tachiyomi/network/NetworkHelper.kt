package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.OkHttpClient

/**
 * Minimal stand-in for Mihon's real NetworkHelper (which also wires DoH and proper
 * cache dirs). Many extensions defensively assert that UncaughtExceptionInterceptor /
 * UserAgentInterceptor / CloudflareInterceptor are present on the client before making
 * requests, so all three are wired in — CloudflareInterceptor is currently a pass-through
 * placeholder, see its kdoc and docs/ARCHITECTURE.md "Known gaps".
 */
class NetworkHelper(context: Context) {
    fun defaultUserAgentProvider(): String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    val cookieJar = AndroidCookieJar()

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor(UncaughtExceptionInterceptor())
        .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
        .addInterceptor(CloudflareInterceptor(context, cookieJar, ::defaultUserAgentProvider))
        .build()
}
