package mihon.desktop.loader.tracker

import mihon.desktop.loader.log.Logger
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "TrackerHttp"

/** Synchronous HTTP result: status code + fully-read body. */
class HttpResult(
    val code: Int,
    val body: String,
    val url: String,
) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * Constructor seam for tracker API classes (same pattern as
 * [mihon.desktop.loader.catalog.HttpFetcher]): production wraps the shared
 * NetworkHelper OkHttpClient, tests install a fake that records requests and
 * serves canned bodies -- no network, no OkHttp client.
 *
 * [execute] throws only on transport failure; non-2xx statuses are returned
 * so callers (e.g. MAL's 401 refresh flow) can inspect them.
 */
fun interface HttpExecutor {
    fun execute(request: Request): HttpResult
}

/** [HttpExecutor] over the shared [client]; runs the call synchronously. */
class OkHttpExecutor(private val client: OkHttpClient) : HttpExecutor {
    override fun execute(request: Request): HttpResult =
        client.newCall(request).execute().use { response ->
            HttpResult(code = response.code, body = response.body.string(), url = request.url.toString())
        }
}

/** Logs and rethrows tracker network failures as [TrackerException] when callers want a typed error. */
class TrackerException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal fun HttpExecutor.executeCatching(request: Request): HttpResult = try {
    execute(request)
} catch (t: Throwable) {
    Logger.w(TAG, "Tracker request to ${request.url} failed: ${t.message}")
    throw t
}
