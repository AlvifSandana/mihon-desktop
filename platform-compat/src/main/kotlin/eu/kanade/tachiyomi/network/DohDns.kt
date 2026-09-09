package eu.kanade.tachiyomi.network

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DNS-over-HTTPS (RFC 8484), opt-in like upstream Mihon.
 *
 * Upstream wires this inside `NetworkHelper` from a preference; here the
 * enabled flag + provider are constructor parameters so `platform-compat`
 * stays free of dependencies on modules above it — the app passes the user's
 * choice (from [mihon.desktop.loader.prefs.AppPreferences]) in at bootstrap
 * time via `DesktopExtensionRuntime`.
 *
 * Changing the setting requires an app restart: `NetworkHelper` is an Injekt
 * singleton and both `HttpSource` and the UI cache the built `OkHttpClient`
 * for their lifetime.
 */
object DohDns {

    const val PROVIDER_GOOGLE = "google"
    const val PROVIDER_CLOUDFLARE = "cloudflare"

    /**
     * Well-known DoH endpoints. Cloudflare uses its RFC-compliant hostname
     * (not the bare `1.1.1.1`) so TLS certificate validation works.
     */
    internal val PROVIDER_URLS: Map<String, HttpUrl> = mapOf(
        PROVIDER_GOOGLE to "https://dns.google/dns-query".toHttpUrl(),
        PROVIDER_CLOUDFLARE to "https://cloudflare-dns.com/dns-query".toHttpUrl(),
    )

    fun isKnownProvider(provider: String): Boolean = provider in PROVIDER_URLS

    /**
     * Builds the DoH [Dns] for [provider], wrapped in [FallbackDns] so a dead
     * or unreachable DoH server degrades to [fallback] (system DNS) instead of
     * breaking every request. Returns null for unknown providers — the caller
     * then keeps the OkHttp default (system DNS).
     *
     * The DoH server's own hostname is resolved through [Dns.SYSTEM], the
     * standard OkHttp `DnsOverHttps` bootstrap pattern — DoH must not be used
     * to find the DoH server itself.
     */
    fun create(provider: String, fallback: Dns = Dns.SYSTEM): Dns? {
        val url = PROVIDER_URLS[provider] ?: return null

        // Plain client used only for the DoH queries themselves. Set an
        // explicit User-Agent per the OkHttp DoH docs — some providers reject
        // requests without one rather than relying on OkHttp's default.
        // Short timeouts so a dead DoH endpoint fails over to system DNS
        // quickly instead of stalling every connection attempt.
        val bootstrapClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", DOH_USER_AGENT)
                        .build(),
                )
            }
            .build()

        val doh = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url(url)
            .systemDns(Dns.SYSTEM)
            .build()

        return FallbackDns(doh, fallback)
    }

    private const val DOH_USER_AGENT = "mihon-desktop DoH client (OkHttp)"
}

/**
 * [Dns] wrapper that tries [primary] and falls back to [fallback] (usually
 * [Dns.SYSTEM]) when the primary fails — DoH endpoints go down, get blocked,
 * or get hijacked just like plain DNS. The fallback keeps the app usable in
 * that case; the first failure is logged once (never with a full URL).
 *
 * Must be thread-safe: OkHttp calls [lookup] from multiple connection threads.
 */
class FallbackDns(
    private val primary: Dns,
    private val fallback: Dns = Dns.SYSTEM,
) : Dns {

    private val loggedFailure = AtomicBoolean(false)

    override fun lookup(hostname: String): List<InetAddress> {
        val primaryResult = try {
            primary.lookup(hostname).also {
                if (it.isEmpty()) throw UnknownHostException("primary DNS returned no addresses")
            }
        } catch (e: IOException) {
            warnOnce(e)
            null
        }
        return primaryResult ?: fallback.lookup(hostname)
    }

    private fun warnOnce(e: IOException) {
        if (loggedFailure.compareAndSet(false, true)) {
            // Host-level diagnostics only — never log full request URLs.
            System.err.println(
                "W DohDns: DNS-over-HTTPS lookup failed " +
                    "(${e.javaClass.simpleName}: ${e.message}); falling back to system DNS",
            )
        }
    }
}
