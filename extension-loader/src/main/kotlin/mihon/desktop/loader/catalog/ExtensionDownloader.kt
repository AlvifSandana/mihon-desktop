package mihon.desktop.loader.catalog

import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import mihon.desktop.loader.ExtensionLoader
import mihon.desktop.loader.JarIntegrity
import mihon.desktop.loader.log.Logger

private const val TAG = "ExtensionDownloader"

/** keiyoushi's sha256 manifest for every published apk/jar, keyed by extension package name. */
const val KEIYOUSHI_RELEASE_ASSETS_JSON =
    "https://raw.githubusercontent.com/keiyoushi/extensions/repo/release-assets.json"

@Serializable
data class ReleaseAsset(val name: String, val sha256: String)

@Serializable
data class PackageReleaseAssets(val apk: ReleaseAsset? = null, val jar: ReleaseAsset? = null)

/**
 * Constructor seam for [ExtensionDownloader]'s raw HTTP: production uses [OkHttpFetcher],
 * tests install a fake that serves bytes from local files -- no network, no OkHttp client.
 */
interface HttpFetcher {
    /**
     * Streams the body at [url] into [destination], reporting progress as
     * (bytes copied, total bytes or null when unknown). Throws on HTTP/network failure.
     */
    fun fetch(url: String, destination: File, onProgress: (Long, Long?) -> Unit)

    /** Whether a HEAD request against [url] succeeds (used to try the primary jar URL). */
    fun exists(url: String): Boolean
}

/** [HttpFetcher] backed by the shared [client] (the NetworkHelper OkHttpClient in the app). */
class OkHttpFetcher(private val client: OkHttpClient) : HttpFetcher {

    override fun fetch(url: String, destination: File, onProgress: (Long, Long?) -> Unit) {
        // Catalog-derived URLs must never travel over plain http.
        val httpsUrl = requireHttps(url)
        client.newCall(GET(httpsUrl)).execute().use { response ->
            check(response.isSuccessful) { "Failed to download $httpsUrl: HTTP ${response.code}" }
            val total = response.body.contentLength().let { if (it < 0) null else it }
            var copied = 0L
            destination.outputStream().use { out ->
                val input = response.body.byteStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    copied += read
                    onProgress(copied, total)
                }
            }
        }
    }

    override fun exists(url: String): Boolean {
        val httpsUrl = requireHttps(url)
        val request = okhttp3.Request.Builder().url(httpsUrl).head().build()
        return client.newCall(request).execute().use { it.isSuccessful }
    }
}

/**
 * Downloads (and caches) the `.jar` for a [CatalogExtension], never its `.apk`.
 *
 * Tries the URL derived from [CatalogExtension.jarUrl] first (same release tag as the
 * apk -- true for most extensions), and falls back to [GitHubReleaseAssetResolver] if
 * that 404s, since the two artifacts don't always land in the same release -- see that
 * resolver's kdoc.
 *
 * Every download (and every cache hit) is checked against keiyoushi's `release-assets.json`
 * sha256 manifest when that manifest is reachable and has an entry for the package, so a
 * truncated download or a stale/corrupted cache entry is never silently handed to
 * [ExtensionLoader][mihon.desktop.loader.ExtensionLoader]. If the manifest itself can't be
 * fetched, verification is skipped rather than blocking installs on it -- but the download
 * is then still zip-validated (see [downloadTo]) so non-jar garbage is always rejected.
 *
 * Downloads go to a `<name>.part` temp file that is sha256- and zip-validated BEFORE it
 * atomically replaces the target, so a failed or interrupted download never corrupts an
 * existing jar.
 */
class ExtensionDownloader(
    private val client: OkHttpClient,
    private val cacheDir: File,
    private val fallbackResolver: GitHubReleaseAssetResolver = GitHubReleaseAssetResolver(client),
    private val releaseAssetsUrl: String = KEIYOUSHI_RELEASE_ASSETS_JSON,
    private val fetcher: HttpFetcher = OkHttpFetcher(client),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var releaseAssetsCache: Map<String, PackageReleaseAssets>? = null

    /** Resolved fallback asset URLs per jar file name, so the 60 req/hr unauthenticated GitHub API is hit once per jar per session. */
    private val resolvedAssetUrls = ConcurrentHashMap<String, String>()

    suspend fun download(
        extension: CatalogExtension,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        // The file name arrives from the wire (apkUrl tail). It becomes a path
        // under cacheDir, so reject anything that could escape it -- same
        // allowlist ExtensionLoader.loadCached applies to DB-sourced names.
        require(ExtensionLoader.isValidCachedJarName(extension.jarFileName)) {
            "Refusing to cache extension jar with unsafe file name: '${extension.jarFileName}'"
        }
        cacheDir.mkdirs()
        val target = File(cacheDir, extension.jarFileName)
        val expectedSha256 = releaseAssets()[extension.packageName]?.jar?.sha256
        if (expectedSha256 == null) {
            // Manifest unreachable or no entry for this package: verification
            // is skipped (never silently -- see releaseAssets()'s warning), so
            // the download is still zip-validated below.
            Logger.w(
                TAG,
                "No sha256 manifest entry for ${extension.packageName}; " +
                    "its download will only be zip-validated",
            )
        }

        if (target.exists()) {
            if (expectedSha256 == null) {
                // Manifest unreachable: we cannot verify the cached file. Keep
                // the EXISTING sidecar (it records the hash as last served) --
                // re-recording the current on-disk content here would bless a
                // jar that was tampered with while the app was closed.
                return@withContext target
            }
            if (sha256Of(target) == expectedSha256) {
                // Verified against upstream: (re-)record the sidecar.
                JarIntegrity.writeSidecar(target)
                return@withContext target
            }
            // Stale/corrupted cache entry: fall through and re-download over it.
        }

        val url = resolveDownloadUrl(extension)
        downloadTo(url, target, expectedSha256, extension, onProgress)
        JarIntegrity.writeSidecar(target)
        target
    }

    /**
     * Best-effort; an unreachable/malformed manifest just disables verification, not
     * installs -- but never silently: a warning is logged either here (fetch
     * failure) or in [download] (package missing from the manifest).
     *
     * Routed through [fetcher] (not `client` directly) so tests can serve the
     * manifest hermetically and the https enforcement in [OkHttpFetcher] applies.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun releaseAssets(): Map<String, PackageReleaseAssets> {
        releaseAssetsCache?.let { return it }
        cacheDir.mkdirs()
        val tmp = File(cacheDir, "release-assets-${System.currentTimeMillis()}.tmp")
        val fetched = runCatching {
            fetcher.fetch(releaseAssetsUrl, tmp) { _, _ -> }
            tmp.inputStream().use { json.decodeFromStream<Map<String, PackageReleaseAssets>>(it) }
        }.onFailure {
            Logger.w(
                TAG,
                "Could not fetch sha256 manifest from $releaseAssetsUrl; " +
                    "downloads proceed zip-validated only: ${it.message}",
            )
        }.getOrDefault(emptyMap())
        tmp.delete()
        releaseAssetsCache = fetched
        return fetched
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun resolveDownloadUrl(extension: CatalogExtension): String {
        if (fetcher.exists(extension.jarUrl)) return extension.jarUrl

        // One unauthenticated GitHub API resolution per jar per session.
        resolvedAssetUrls[extension.jarFileName]?.let { return it }

        val resolved = fallbackResolver.findAssetUrl(extension.jarFileName)?.let { requireHttps(it) }
        if (resolved != null) {
            resolvedAssetUrls[extension.jarFileName] = resolved
            return resolved
        }
        error(
            "Could not find ${extension.jarFileName} in the same release as its apk, nor in recent " +
                "releases. It may only exist further back in this repo's history than this loader " +
                "scans -- see GitHubReleaseAssetResolver's kdoc.",
        )
    }

    /**
     * Downloads [url] into a temp file, validates it (sha256 when the manifest is
     * reachable, then the zip central directory -- which is what rejects non-jar garbage
     * when it isn't), then atomically moves it over [target]. On any failure the temp
     * file is deleted and an existing [target] is left untouched.
     */
    private fun downloadTo(
        url: String,
        target: File,
        expectedSha256: String?,
        extension: CatalogExtension,
        onProgress: (Long, Long?) -> Unit,
    ) {
        val tmp = File(target.parentFile, "${target.name}.part")
        try {
            fetcher.fetch(url, tmp, onProgress)
            if (expectedSha256 != null) {
                val actual = sha256Of(tmp)
                check(actual == expectedSha256) {
                    "Downloaded ${extension.jarFileName} failed sha256 verification " +
                        "(expected $expectedSha256, got $actual) -- refusing to load it."
                }
            }
            validateZip(tmp, target)
            // Drop the old sidecar before the swap so a crash between move and
            // writeSidecar can't leave a sidecar blessing content that's gone.
            JarIntegrity.removeSidecar(target)
            moveAtomically(tmp, target)
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    /** [ZipFile] parses the central directory, which a truncated or non-zip file fails. */
    private fun validateZip(tmp: File, target: File) {
        val zipError = runCatching { ZipFile(tmp).use { /* central directory parsed */ } }
            .exceptionOrNull()
        if (zipError != null) {
            throw IllegalStateException(
                "Download for ${target.name} is not a valid zip/jar (${zipError.message}) " +
                    "-- refusing to replace the existing file.",
                zipError,
            )
        }
    }

    private fun moveAtomically(tmp: File, target: File) {
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            // ATOMIC_MOVE alone doesn't promise replacement on every platform.
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        check(!tmp.exists() && target.exists()) { "Failed to move $tmp to $target" }
    }
}
