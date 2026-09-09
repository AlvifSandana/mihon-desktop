package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.File

/**
 * Stand-in for Mihon's real `NetworkHelper`. Wires the interceptors that many
 * extensions defensively assert are present on the client
 * (UncaughtExceptionInterceptor / UserAgentInterceptor / CloudflareInterceptor),
 * an HTTP cache, and — opt-in like upstream — DNS-over-HTTPS.
 *
 * DoH is configured at construction time ([dohEnabled] + [dohProvider], see
 * [DohDns]) because the client is built once here and both `HttpSource` and
 * the UI hold on to the resulting `OkHttpClient` for their lifetime. Toggling
 * the preference therefore requires an app restart; it is read once in
 * `DesktopExtensionRuntime.bootstrap()`.
 */
class NetworkHelper(
    context: Context,
    dohEnabled: Boolean = false,
    dohProvider: String = DohDns.PROVIDER_GOOGLE,
) {
    fun defaultUserAgentProvider(): String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    val cookieJar = AndroidCookieJar()

    // 10MB HTTP cache for network responses
    private val cacheDir = File(System.getProperty("user.home"), ".mihon-desktop/http-cache")
    private val cache = Cache(cacheDir, 10L * 1024 * 1024)

    /** DoH-backed resolver when enabled; null means OkHttp's system-DNS default. */
    private val dohDns: Dns? = if (dohEnabled) DohDns.create(dohProvider) else null

    init {
        if (dohEnabled && dohDns == null) {
            System.err.println("W NetworkHelper: DoH enabled but provider '$dohProvider' is unknown; using system DNS")
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .cache(cache)
        .apply {
            if (dohDns != null) dns(dohDns)
        }
        .addInterceptor(UncaughtExceptionInterceptor())
        .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
        .addInterceptor(CloudflareInterceptor(context, cookieJar, ::defaultUserAgentProvider))
        .build()
}
