package eu.kanade.tachiyomi.network.interceptor

import android.app.Application
import eu.kanade.tachiyomi.network.AndroidCookieJar
import okhttp3.Authenticator
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CertificatePinner
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.reflect.KClass

class CloudflareInterceptorTest {

    private val defaultUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Fresh temp dir per test (auto-deleted) — no leaked interceptor dirs. */
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun interceptor() = CloudflareInterceptor(
        Application(tempFolder.newFolder()),
        AndroidCookieJar(),
    ) { defaultUa }

    private fun request(userAgent: String? = defaultUa): Request {
        val builder = Request.Builder().url("https://manga.example.com/chapter/1")
        if (userAgent != null) builder.header("User-Agent", userAgent)
        return builder.build()
    }

    /** Builds a buffered [Response]. */
    private fun response(
        code: Int,
        body: String = "",
        headers: Map<String, String> = emptyMap(),
        request: Request = request(),
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_2)
            .code(code)
            .message("status")
            .body(ResponseBody.create("text/html".toMediaType(), body.length.toLong(), Buffer().writeUtf8(body)))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    /** Builds a [Response] over a counting [Source], to observe how much was actually read. */
    private fun response(
        code: Int,
        bodySource: CountingSource,
        contentLength: Long,
        headers: Map<String, String> = emptyMap(),
        request: Request = request(),
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_2)
            .code(code)
            .message("status")
            .body(ResponseBody.create("text/html".toMediaType(), contentLength, bodySource.buffer()))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private fun chain(
        request: Request,
        responder: (Request) -> Response,
    ): FakeChain = FakeChain(request, responder)

    private class CountingSource(private val delegate: Source) : Source {
        var readTotal = 0L
            private set

        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = delegate.read(sink, byteCount)
            if (read > 0) readTotal += read
            return read
        }

        override fun close() = delegate.close()
        override fun timeout(): Timeout = delegate.timeout()
    }

    // ── Fake OkHttp plumbing ──────────────────────────────────────────────
    // Interceptor.Chain is a large interface in OkHttp 5; the interceptor
    // under test only touches request()/proceed(), everything else is a stub.

    private class FakeChain(
        private val initialRequest: Request,
        private val responder: (Request) -> Response,
    ) : Interceptor.Chain {
        val requests = mutableListOf<Request>()

        override fun request(): Request = initialRequest

        override fun proceed(request: Request): Response {
            requests += request
            return responder(request)
        }

        override fun connection(): Connection? = null
        override fun call(): Call = FakeCall(initialRequest)
        override fun connectTimeoutMillis(): Int = 10_000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
        override fun readTimeoutMillis(): Int = 10_000
        override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
        override fun writeTimeoutMillis(): Int = 10_000
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
        override val followSslRedirects: Boolean = true
        override val followRedirects: Boolean = true
        override val dns: Dns = Dns.SYSTEM
        override fun withDns(dns: Dns) = this
        override val socketFactory: SocketFactory = SocketFactory.getDefault()
        override fun withSocketFactory(socketFactory: SocketFactory) = this
        override val retryOnConnectionFailure: Boolean = true
        override fun withRetryOnConnectionFailure(retryOnConnectionFailure: Boolean) = this
        override val authenticator: Authenticator = Authenticator.NONE
        override fun withAuthenticator(authenticator: Authenticator) = this
        override val cookieJar: CookieJar = CookieJar.NO_COOKIES
        override fun withCookieJar(cookieJar: CookieJar) = this
        override val cache: Cache? = null
        override fun withCache(cache: Cache?) = this
        override val proxy: Proxy? = null
        override fun withProxy(proxy: Proxy?) = this
        override val proxySelector: ProxySelector = ProxySelector.getDefault()
            ?: ProxySelector.of(null)
        override fun withProxySelector(proxySelector: ProxySelector) = this
        override val proxyAuthenticator: Authenticator = Authenticator.NONE
        override fun withProxyAuthenticator(authenticator: Authenticator) = this
        override val sslSocketFactoryOrNull: SSLSocketFactory? = null
        override fun withSslSocketFactory(sslSocketFactory: SSLSocketFactory?, trustManager: X509TrustManager?) = this
        override val x509TrustManagerOrNull: X509TrustManager? = null
        override val hostnameVerifier: HostnameVerifier = HostnameVerifier { _, _ -> true }
        override fun withHostnameVerifier(hostnameVerifier: HostnameVerifier) = this
        override val certificatePinner: CertificatePinner = CertificatePinner.DEFAULT
        override fun withCertificatePinner(certificatePinner: CertificatePinner) = this
        override val connectionPool: ConnectionPool = ConnectionPool()
        override fun withConnectionPool(connectionPool: ConnectionPool) = this
        override val eventListener: EventListener = EventListener.NONE
    }

    private class FakeCall(private val request: Request) : Call {
        override fun request(): Request = request

        @Suppress("DEPRECATION")
        override fun execute(): Response = throw UnsupportedOperationException()

        override fun enqueue(responseCallback: Callback): Unit = throw UnsupportedOperationException()

        override fun cancel() {}
        override fun isExecuted(): Boolean = false
        override fun isCanceled(): Boolean = false
        override fun timeout(): Timeout = Timeout()
        override fun addEventListener(eventListener: EventListener) {}
        override fun <T> tag(type: Class<out T>): T? = null
        override fun <T : Any> tag(type: KClass<T>): T? = null
        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = throw UnsupportedOperationException()
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = throw UnsupportedOperationException()
        override fun clone(): Call = FakeCall(request)
    }

    // ── Detection: pass-through cases ─────────────────────────────────────

    @Test
    fun `plain 403 without cloudflare markers passes through`() {
        val request = request()
        val plain = response(403, "Forbidden")
        val chain = chain(request) { plain }

        val result = interceptor().intercept(chain)

        assertTrue(result === plain)
        assertEquals(403, result.code)
        assertEquals("Forbidden", result.body!!.string())
        assertEquals(1, chain.requests.size)
    }

    @Test
    fun `403 behind cloudflare but without challenge markers passes through`() {
        // `server: cloudflare` alone is NOT a challenge marker — every
        // response proxied through Cloudflare carries it.
        val request = request()
        val plain = response(403, "Access denied", headers = mapOf("server" to "cloudflare"))
        val chain = chain(request) { plain }

        val result = interceptor().intercept(chain)

        assertTrue(result === plain)
        assertEquals(1, chain.requests.size)
    }

    @Test
    fun `plain 503 without markers passes through`() {
        val request = request()
        val plain = response(503, "Service Unavailable")
        val chain = chain(request) { plain }

        val result = interceptor().intercept(chain)

        assertTrue(result === plain)
        assertEquals(1, chain.requests.size)
    }

    @Test
    fun `plain 429 without markers passes through`() {
        val request = request()
        val plain = response(429, "Too Many Requests")
        val chain = chain(request) { plain }

        val result = interceptor().intercept(chain)

        assertTrue(result === plain)
        assertEquals(1, chain.requests.size)
    }

    @Test
    fun `403 with null body passes through`() {
        // No body to scan (e.g. a bare 403/429 with Content-Length 0) —
        // must not crash in peekBody and must not count as a challenge.
        val request = request()
        val bodiless = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_2)
            .code(403)
            .message("status")
            .build()
        val chain = chain(request) { bodiless }

        val result = interceptor().intercept(chain)

        assertTrue(result === bodiless)
        assertEquals(1, chain.requests.size)
    }

    // ── Detection: challenge cases ────────────────────────────────────────

    @Test
    fun `200 with cf-mitigated challenge header is treated as challenge`() {
        val request = request()
        val challenged = response(200, "ok", headers = mapOf("cf-mitigated" to "challenge"))
        val chain = chain(request) { challenged }

        try {
            interceptor().intercept(chain)
            fail("expected CloudflareChallengeException")
        } catch (expected: CloudflareChallengeException) {
            assertTrue(expected.message!!.contains("manga.example.com"))
            assertTrue(expected.message!!.contains("Cloudflare", ignoreCase = true))
        }
        // Request already used the default browser UA — no retry.
        assertEquals(1, chain.requests.size)
    }

    @Test
    fun `403 with challenge body is treated as challenge`() {
        val request = request()
        val challenged = response(
            403,
            "<html><title>Just a moment...</title><script src=\"/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1\"></script></html>",
            headers = mapOf("server" to "cloudflare"),
        )
        val chain = chain(request) { challenged }

        try {
            interceptor().intercept(chain)
            fail("expected CloudflareChallengeException")
        } catch (expected: CloudflareChallengeException) {
            assertTrue(expected.message!!.contains("manga.example.com"))
        }
        assertEquals(1, chain.requests.size)
    }

    @Test
    fun `503 with cf-chl body marker is treated as challenge`() {
        val request = request()
        val challenged = response(503, "<html><body>cf-chl-widget</body></html>")
        val chain = chain(request) { challenged }

        try {
            interceptor().intercept(chain)
            fail("expected CloudflareChallengeException")
        } catch (expected: CloudflareChallengeException) {
            // expected
        }
    }

    @Test
    fun `429 with challenge body is treated as challenge`() {
        // Cloudflare rate-limits with 429 while issuing challenges —
        // upstream Mihon scans 403/429/503.
        val request = request()
        val challenged = response(429, "<html><title>Just a moment...</title></html>")
        val chain = chain(request) { challenged }

        try {
            interceptor().intercept(chain)
            fail("expected CloudflareChallengeException")
        } catch (expected: CloudflareChallengeException) {
            // expected
        }
        // Request already used the default browser UA — no retry.
        assertEquals(1, chain.requests.size)
    }

    @Test
    fun `cf-mitigated header value other than challenge is not a challenge`() {
        val request = request()
        val blocked = response(200, "ok", headers = mapOf("cf-mitigated" to "blocked"))
        val chain = chain(request) { blocked }

        val result = interceptor().intercept(chain)

        assertTrue(result === blocked)
    }

    // ── Retry with browser-like User-Agent ────────────────────────────────

    @Test
    fun `retries once with default browser user agent and succeeds`() {
        val customUaRequest = request(userAgent = "MihonDesktop/1.0 (test)")
        var first = true
        val chain = chain(customUaRequest) { req ->
            if (first) {
                first = false
                response(403, "Just a moment...", headers = mapOf("server" to "cloudflare"))
            } else {
                assertEquals(defaultUa, req.header("User-Agent"))
                response(200, "real page")
            }
        }

        val result = interceptor().intercept(chain)

        assertEquals(200, result.code)
        assertEquals("real page", result.body!!.string())
        assertEquals(2, chain.requests.size)
    }

    @Test
    fun `retry that is still challenged throws clear error`() {
        val customUaRequest = request(userAgent = "MihonDesktop/1.0 (test)")
        val chain = chain(customUaRequest) {
            response(403, "Just a moment...", headers = mapOf("server" to "cloudflare"))
        }

        try {
            interceptor().intercept(chain)
            fail("expected CloudflareChallengeException")
        } catch (expected: CloudflareChallengeException) {
            assertTrue(expected.message!!.contains("JCEF"))
        }
        assertEquals(2, chain.requests.size)
    }

    @Test
    fun `challenge with default user agent already set throws without retry`() {
        val request = request()
        val chain = chain(request) {
            response(403, "Just a moment...", headers = mapOf("server" to "cloudflare"))
        }

        try {
            interceptor().intercept(chain)
            fail("expected CloudflareChallengeException")
        } catch (expected: CloudflareChallengeException) {
            // expected
        }
        assertEquals(1, chain.requests.size)
    }

    // ── Bounded body scan ─────────────────────────────────────────────────

    @Test
    fun `body scan is bounded - marker beyond the limit is not read`() {
        // 1 MB body whose only challenge marker sits at the very end, well
        // past the 64 KB scan limit.
        val marker = "cdn-cgi/challenge-platform"
        val bigBody = "x".repeat(1024 * 1024) + marker
        val counting = CountingSource(Buffer().writeUtf8(bigBody))
        val request = request()
        val chained = response(
            403,
            bodySource = counting,
            contentLength = bigBody.length.toLong(),
            headers = mapOf("server" to "cloudflare"),
        )
        val chain = chain(request) { chained }

        val result = interceptor().intercept(chain)

        // Not detected as a challenge (marker never scanned) → pass-through.
        assertTrue(result === chained)
        val readDuringScan = counting.readTotal
        assertTrue(
            "expected bounded read, was $readDuringScan",
            readDuringScan <= CloudflareInterceptor.BODY_SCAN_LIMIT_BYTES + (16L * 1024),
        )
        // Original body is still fully readable (peek does not consume).
        assertEquals(bigBody, result.body!!.string())
        assertTrue(counting.readTotal > CloudflareInterceptor.BODY_SCAN_LIMIT_BYTES)
    }

    // ── Direct detection unit checks ──────────────────────────────────────

    @Test
    fun `isChallenge header and body combinations`() {
        val underTest = interceptor()

        assertTrue(
            underTest.isChallenge(response(200, "ok", headers = mapOf("cf-mitigated" to "challenge"))),
        )
        assertTrue(
            underTest.isChallenge(response(200, "ok", headers = mapOf("cf-mitigated" to "Challenge"))),
        )
        assertFalse(
            underTest.isChallenge(response(404, "not found", headers = mapOf("server" to "cloudflare"))),
        )
        assertFalse(underTest.isChallenge(response(500, "boom")))
        assertTrue(underTest.isChallenge(response(503, "checking your browser before accessing")))
        assertTrue(underTest.isChallenge(response(429, "just a moment")))
        assertFalse(underTest.isChallenge(response(403, "plain forbidden")))
    }

    @Test
    fun `exception type is an IOException for source compatibility`() {
        val exception = CloudflareChallengeException("manga.example.com")
        assertTrue(exception is IOException)
    }
}
