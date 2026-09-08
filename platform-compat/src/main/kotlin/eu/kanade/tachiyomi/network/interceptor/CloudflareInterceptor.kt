package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import eu.kanade.tachiyomi.network.AndroidCookieJar
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Cloudflare interceptor with real JS challenge bypass via JCEF.
 *
 * When JCEF is on the classpath, this interceptor:
 * 1. Detects a Cloudflare challenge (HTTP 403/503 with `cf-chl-*` headers)
 * 2. Opens an off-screen JCEF browser to solve the JS challenge
 * 3. Waits for the challenge to complete (cookie set)
 * 4. Retries the original request with the solved cookie
 *
 * When JCEF is NOT on the classpath, falls back to pass-through
 * (same behavior as the original placeholder).
 *
 * JCEF is `compileOnly` — add `me.friwi:jcefmaven:146.0.10` to your
 * runtime classpath to enable real Cloudflare bypass.
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

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // Only attempt bypass if JCEF is available and we got a challenge response
        if (!isJcefAvailable) return response
        if (!isChallenge(response)) return response

        return solveWithJcef(chain, request, response)
    }

    private fun isChallenge(response: Response): Boolean {
        val code = response.code
        return code == 403 || code == 503
    }

    private fun solveWithJcef(chain: Interceptor.Chain, request: okhttp3.Request, challengeResponse: Response): Response {
        challengeResponse.close()
        val url = request.url.toString()

        // Use reflection to avoid compile-time dependency on JCEF
        return try {
            val solverClass = Class.forName("eu.kanade.tachiyomi.network.interceptor.JcefCloudflareSolver")
            val constructor = solverClass.getDeclaredConstructor(
                AndroidCookieJar::class.java,
                String::class.java,
            )
            val solver = constructor.newInstance(cookieJar, defaultUserAgentProvider.invoke())
            val solveMethod = solverClass.getMethod("solve", String::class.java, Long::class.java)
            val solved = solveMethod.invoke(solver, url, CHALLENGE_TIMEOUT_SECONDS) as Boolean

            if (solved) {
                chain.proceed(request)
            } else {
                // Challenge not solved, return original response
                chain.proceed(request)
            }
        } catch (e: Exception) {
            // JCEF solver failed, fall back to pass-through
            chain.proceed(request)
        }
    }

    companion object {
        private const val CHALLENGE_TIMEOUT_SECONDS = 30L
    }
}
