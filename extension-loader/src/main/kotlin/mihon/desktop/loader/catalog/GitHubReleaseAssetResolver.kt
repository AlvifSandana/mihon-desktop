package mihon.desktop.loader.catalog

import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient

/**
 * keiyoushi builds its release bot to publish incremental "Repository Update" releases,
 * and (discovered while testing this loader against the live catalog, not documented
 * anywhere) the `.apk` and `.jar` for the *same* extension version don't always land in
 * the *same* release -- a jar rebuild isn't always triggered by the same commit as the
 * apk rebuild. That means deriving the jar URL by swapping the extension on
 * [CatalogExtension.apkUrl] (which encodes one specific release tag) sometimes 404s even
 * though `release-assets.json` confirms a jar genuinely exists for that version.
 *
 * This is the fallback for that case: scan the most recent releases' asset lists for the
 * exact jar filename. Bounded (not a full-history search) to keep this to one or two
 * unauthenticated GitHub API calls -- see docs/RESEARCH.md for the trade-off this makes.
 */
class GitHubReleaseAssetResolver(
    private val client: OkHttpClient,
    private val owner: String = "keiyoushi",
    private val repo: String = "extensions",
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class GhRelease(val assets: List<GhAsset>)

    @Serializable
    private data class GhAsset(val name: String, @SerialName("browser_download_url") val browserDownloadUrl: String)

    /** Returns the download URL for [fileName], scanning up to [maxReleases] recent releases. */
    @OptIn(ExperimentalSerializationApi::class)
    fun findAssetUrl(fileName: String, maxReleases: Int = 100): String? {
        var page = 1
        var scanned = 0
        while (scanned < maxReleases) {
            val perPage = minOf(100, maxReleases - scanned)
            val url = "https://api.github.com/repos/$owner/$repo/releases?per_page=$perPage&page=$page"
            val releases = client.newCall(GET(url)).execute().use { response ->
                if (!response.isSuccessful) return null
                json.decodeFromStream<List<GhRelease>>(response.body.byteStream())
            }
            if (releases.isEmpty()) return null

            releases.forEach { release ->
                release.assets.firstOrNull { it.name == fileName }?.let { return it.browserDownloadUrl }
            }

            scanned += releases.size
            page++
        }
        return null
    }
}
