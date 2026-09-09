package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import eu.kanade.tachiyomi.network.AndroidCookieJar
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Cloudflare interceptor.
 *
 * Detection (see [isChallenge]) — a response is treated as a Cloudflare
 * challenge when either:
 *  - the response carries the authoritative `cf-mitigated: challenge` header
 *    (set by Cloudflare regardless of status code), or
 *  - the status is 403/429/503 AND the body (bounded to the first
 *    [BODY_SCAN_LIMIT_BYTES]) contains a known challenge marker:
 *    `cdn-cgi/challenge-platform`, `cf-chl`, "Just a moment…",
 *    "Checking your browser".
 *
 * A `server: cloudflare` header alone is NOT enough — every response served
 * through Cloudflare carries it.
 *
 * On a detected challenge:
 *  1. If JCEF is on the runtime classpath, the off-screen JCEF browser
 *     solves the JS challenge and the request is retried with the solved
 *     cookies. JCEF stays `compileOnly` (`me.friwi:jcefmaven`).
 *  2. Otherwise the request is retried once with the default browser-like
 *     User-Agent — many sources send their own UA, and some Cloudflare
 *     configurations only challenge non-browser UAs.
 *  3. If the challenge persists, the call fails with
 *     [CloudflareChallengeException] carrying a clear explanation instead of
 *     silently handing a challenge page to the source's HTML parser.
 */
class CloudflareInterceptor(
    private val context: Context,
    private val cookieJar: AndroidCookieJar,
    private val defaultUserAgentProvider: () -> String,
) : Interceptor {

    private val isJcefAvailable: Boolean by lazy {
        try {
            Class.forName("me.friwi.jcefmaven.CefAppBuilder")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    /**
     * One solver for the interceptor's lifetime. JCEF initializes a native
     * CEF subprocess tree; re-creating it per challenge would re-download /
     * re-init CEF every time and the discarded instances are never disposed
     * (process leak). Reuse keeps a single CEF app warm across challenges.
     */
    private val jcefSolver by lazy { JcefCloudflareSolver(cookieJar, defaultUserAgentProvider) }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (!isChallenge(response)) return response

        response.close()

        if (isJcefAvailable) {
            return solveWithJcef(chain, request)
        }

        // No embedded browser available. Retry once with the default
        // (Chrome desktop) User-Agent when the request sent something else.
        // Bodies are one-shot: the challenged response above is closed, and
        // this retry body is only ever peeked at (not consumed) before being
        // handed to the caller.
        val defaultUa = defaultUserAgentProvider.invoke()
        if (request.header("User-Agent") != defaultUa) {
            val retryResponse = chain.proceed(
                request.newBuilder().header("User-Agent", defaultUa).build(),
            )
            if (!isChallenge(retryResponse)) return retryResponse
            retryResponse.close()
        }

        throw CloudflareChallengeException(request.url.host)
    }

    /**
     * A Cloudflare challenge response. Checks the authoritative header first
     * (cheap), then a bounded body scan only for 403/429/503 responses.
     * Reading via [Response.peekBody] does not consume the original body.
     */
    internal fun isChallenge(response: Response): Boolean {
        if (response.header(HEADER_CF_MITIGATED)?.equals(VALUE_CHALLENGE, ignoreCase = true) == true) {
            return true
        }

        val code = response.code
        if (code != 403 && code != 429 && code != 503) return false

        // No body (e.g. a bare 429 with Content-Length 0): nothing to scan,
        // and peekBody would fail — the status alone is too ambiguous.
        if (response.body == null) return false

        val body = try {
            response.peekBody(BODY_SCAN_LIMIT_BYTES).string()
        } catch (_: IOException) {
            // Unreadable body: treat as non-challenge; the status alone is
            // too ambiguous (plain 403s must pass through).
            ""
        }
        return CHALLENGE_BODY_MARKERS.any { body.contains(it, ignoreCase = true) }
    }

    private fun solveWithJcef(chain: Interceptor.Chain, request: Request): Response {
        // JcefCloudflareSolver lives in this same artifact and only touches
        // JCEF through reflection, so it is safe to use directly — the
        // isJcefAvailable check above already gated runtime availability.
        // The solver is a lazy singleton so the CEF process tree is
        // initialized once and reused for every challenge.
        jcefSolver.solve(request.url.toString(), CHALLENGE_TIMEOUT_SECONDS)

        // Retry with the (hopefully) solved cookies. Even if `solve` reported
        // failure the cookies may have changed, so retry once either way.
        val retried = chain.proceed(request)
        if (!isChallenge(retried)) return retried
        retried.close()
        throw CloudflareChallengeException(request.url.host)
    }

    companion object {
        private const val CHALLENGE_TIMEOUT_SECONDS = 30L
        private const val HEADER_CF_MITIGATED = "cf-mitigated"
        private const val VALUE_CHALLENGE = "challenge"

        /** Challenge pages are small; only scan this much of any 403/503 body. */
        internal const val BODY_SCAN_LIMIT_BYTES: Long = 64L * 1024

        /** Cloudflare-specific strings that appear in challenge/interstitial pages. */
        internal val CHALLENGE_BODY_MARKERS = listOf(
            "cdn-cgi/challenge-platform",
            "cf-chl",
            "just a moment",
            "checking your browser",
        )
    }
}

/**
 * Thrown when a Cloudflare challenge could not be solved (no embedded browser
 * on the classpath and the browser-UA retry didn't clear it). Propagates to
 * source calls as a regular [IOException] so the UI can surface the actual
 * cause instead of a source's parse error on a challenge page.
 */
class CloudflareChallengeException(
    host: String,
) : IOException(
    "Cloudflare challenge on '$host' could not be solved. Enable the embedded browser (JCEF) or try a different source.",
)
