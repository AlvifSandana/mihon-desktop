package mihon.desktop.loader.catalog

import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/** keiyoushi's own repo -- see https://keiyoushi.github.io for the human-facing site. */
const val KEIYOUSHI_REPO_JSON = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/repo.json"

/**
 * Rejects any catalog-derived URL that is not https before it reaches OkHttp.
 * Catalog entries come off the wire (repo.json / index.pb / GitHub API); a
 * plain-http URL in there is either a hostile repo or a broken one, and must
 * never be fetched -- credentials, cookies and jar bytes would travel in clear.
 *
 * @return [url] unchanged when it parses as an https URL.
 * @throws IllegalStateException when [url] is not a valid https URL.
 */
fun requireHttps(url: String): String {
    val parsed = url.toHttpUrlOrNull()
    check(parsed != null && parsed.isHttps) { "Refusing non-https extension URL: $url" }
    return url
}

/**
 * Fetches a keiyoushi-style extension catalog.
 *
 * Mirrors the resolution chain in upstream Mihon's `ExtensionStoreService.fetch` /
 * `getExtensions`: a repo is identified by a `repo.json` carrying an `index_v2` field,
 * which points at the actual catalog -- a **gzip-compressed, protobuf**-encoded
 * `NetworkExtensionStore` message (see docs/RESEARCH.md for why the plain
 * `index.min.json` at the repo root is NOT this -- it's a deliberate "please update your
 * client" stub for pre-protobuf clients, not a real catalog).
 */
class CatalogClient(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun fetchCatalog(repoJsonUrl: String = KEIYOUSHI_REPO_JSON): List<CatalogExtension> =
        withContext(Dispatchers.IO) {
            val repoUrl = requireHttps(repoJsonUrl)
            val repo = client.newCall(GET(repoUrl)).execute().use { response ->
                check(response.isSuccessful) { "Failed to fetch $repoUrl: HTTP ${response.code}" }
                json.decodeFromStream<LegacyExtensionRepo>(ungzipIfNeeded(response.body.bytes()))
            }
            // index_v2 comes off the wire: enforce https before fetching it.
            val indexPbUrl = requireHttps(
                repo.index_v2
                    ?: error("$repoUrl has no index_v2 -- not a protobuf-catalog repo this client understands"),
            )

            val store = client.newCall(GET(indexPbUrl)).execute().use { response ->
                check(response.isSuccessful) { "Failed to fetch $indexPbUrl: HTTP ${response.code}" }
                val bytes = ungzipIfNeeded(response.body.bytes()).readBytes()
                ProtoBuf.decodeFromByteArray(NetworkExtensionStore.serializer(), bytes)
            }

            store.extensionList?.extensions.orEmpty().map { it.toCatalogExtension() }
        }

    /** keiyoushi serves both repo.json and index.pb gzip-compressed. */
    private fun ungzipIfNeeded(bytes: ByteArray) =
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(bytes))
        } else {
            ByteArrayInputStream(bytes)
        }
}
