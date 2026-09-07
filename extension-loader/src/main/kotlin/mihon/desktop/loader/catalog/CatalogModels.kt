@file:OptIn(ExperimentalSerializationApi::class)

package mihon.desktop.loader.catalog

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Mirrors `mihon.data.extension.model.NetworkExtensionStore` from upstream Mihon: the
 * protobuf schema keiyoushi (and any store using the same repo tooling) publishes its
 * extension catalog in. Field numbers are load-bearing -- they must match upstream
 * exactly, the names don't matter to the wire format.
 *
 * Upstream resolves this indirectly (`repo.json` -> `index_v2` -> this), see
 * [CatalogClient] for the desktop equivalent of that chain.
 */
@Serializable
data class NetworkExtensionStore(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val badgeLabel: String,
    @ProtoNumber(3) val signingKey: String,
    @ProtoNumber(4) val contact: Contact,
    @ProtoNumber(101) val extensionList: ExtensionList? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
) {
    @Serializable
    data class Contact(
        @ProtoNumber(1) val website: String,
        @ProtoNumber(2) val discord: String? = null,
    )

    @Serializable
    data class ExtensionList(@ProtoNumber(1) val extensions: List<Extension> = emptyList())

    @Serializable
    data class Extension(
        @ProtoNumber(1) val name: String,
        @ProtoNumber(2) val packageName: String,
        @ProtoNumber(3) val resources: Resources,
        @ProtoNumber(4) val extensionLib: String,
        @ProtoNumber(5) val versionCode: Long,
        @ProtoNumber(6) val versionName: String,
        @ProtoNumber(7) val contentWarning: ContentWarning = ContentWarning.UNSPECIFIED,
        @ProtoNumber(8) val sources: List<Source> = emptyList(),
    )

    @Serializable
    data class Resources(
        @ProtoNumber(1) val apkUrl: String,
        @ProtoNumber(2) val iconUrl: String,
    )

    @Serializable
    data class Source(
        @ProtoNumber(1) val id: Long,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(3) val language: String,
        @ProtoNumber(4) val homeUrl: String = "",
        @ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
        @ProtoNumber(7) val message: String? = null,
    )

    enum class ContentWarning {
        UNSPECIFIED,
        SAFE,
        MIXED,
        NSFW,
    }
}

/**
 * The `repo.json` a store's index URL ultimately points at. Only the `index_v2` field
 * is needed here -- that's the actual protobuf catalog URL described above.
 */
@Serializable
data class LegacyExtensionRepo(
    val index_v2: String? = null,
)
