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
import java.security.MessageDigest

/** keiyoushi's sha256 manifest for every published apk/jar, keyed by extension package name. */
const val KEIYOUSHI_RELEASE_ASSETS_JSON =
    "https://raw.githubusercontent.com/keiyoushi/extensions/repo/release-assets.json"

@Serializable
data class ReleaseAsset(val name: String, val sha256: String)

@Serializable
data class PackageReleaseAssets(val apk: ReleaseAsset? = null, val jar: ReleaseAsset? = null)

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
 * fetched, verification is skipped rather than blocking installs on it.
 */
class ExtensionDownloader(
    private val client: OkHttpClient,
    private val cacheDir: File,
    private val fallbackResolver: GitHubReleaseAssetResolver = GitHubReleaseAssetResolver(client),
    private val releaseAssetsUrl: String = KEIYOUSHI_RELEASE_ASSETS_JSON,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var releaseAssetsCache: Map<String, PackageReleaseAssets>? = null

    suspend fun download(extension: CatalogExtension): File = withContext(Dispatchers.IO) {
        cacheDir.mkdirs()
        val target = File(cacheDir, extension.jarFileName)
        val expectedSha256 = releaseAssets()[extension.packageName]?.jar?.sha256

        if (target.exists()) {
            if (expectedSha256 == null || sha256Of(target) == expectedSha256) {
                return@withContext target
            }
            target.delete()
        }

        val url = resolveDownloadUrl(extension)
        downloadTo(url, target)
        verify(target, expectedSha256, extension)
        target
    }

    private fun verify(target: File, expectedSha256: String?, extension: CatalogExtension) {
        if (expectedSha256 == null) return
        val actual = sha256Of(target)
        if (actual != expectedSha256) {
            target.delete()
            error(
                "Downloaded ${extension.jarFileName} failed sha256 verification " +
                    "(expected $expectedSha256, got $actual) -- refusing to load it.",
            )
        }
    }

    /** Best-effort; an unreachable/malformed manifest just disables verification, not installs. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun releaseAssets(): Map<String, PackageReleaseAssets> {
        releaseAssetsCache?.let { return it }
        val fetched = runCatching {
            client.newCall(GET(releaseAssetsUrl)).execute().use { response ->
                if (!response.isSuccessful) return@use emptyMap()
                json.decodeFromStream<Map<String, PackageReleaseAssets>>(response.body.byteStream())
            }
        }.getOrDefault(emptyMap())
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
        if (headSucceeds(extension.jarUrl)) return extension.jarUrl

        return fallbackResolver.findAssetUrl(extension.jarFileName)
            ?: error(
                "Could not find ${extension.jarFileName} in the same release as its apk, nor in recent " +
                    "releases. It may only exist further back in this repo's history than this loader " +
                    "scans -- see GitHubReleaseAssetResolver's kdoc.",
            )
    }

    private fun headSucceeds(url: String): Boolean {
        val request = okhttp3.Request.Builder().url(url).head().build()
        return client.newCall(request).execute().use { it.isSuccessful }
    }

    private fun downloadTo(url: String, target: File) {
        val tmp = File(target.parentFile, "${target.name}.part")
        client.newCall(GET(url)).execute().use { response ->
            check(response.isSuccessful) { "Failed to download $url: HTTP ${response.code}" }
            tmp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
        }
        tmp.renameTo(target)
    }
}
