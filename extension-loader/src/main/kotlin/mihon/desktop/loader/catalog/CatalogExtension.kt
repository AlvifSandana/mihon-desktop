package mihon.desktop.loader.catalog

/** Desktop-friendly view of one entry in a keiyoushi-style extension catalog. */
data class CatalogExtension(
    val name: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val extensionLibVersion: String,
    val isNsfw: Boolean,
    val apkUrl: String,
    val iconUrl: String,
    val sources: List<Source>,
) {
    /**
     * The JVM-native jar published alongside the apk (see docs/RESEARCH.md) -- keiyoushi
     * names it identically to the apk, just with the extension swapped.
     *
     * Both guards run here because this is where the wire-sourced [apkUrl] turns
     * into a URL we actually fetch: the `.apk` suffix is what makes the
     * extension-swap derivation valid, and every catalog-derived URL must be
     * https (see [requireHttps]).
     */
    val jarUrl: String
        get() {
            require(apkUrl.endsWith(".apk")) {
                "Catalog apkUrl must end in .apk, got: $apkUrl"
            }
            return requireHttps(apkUrl.removeSuffix(".apk") + ".jar")
        }

    val jarFileName: String get() = jarUrl.substringAfterLast('/')

    data class Source(val id: Long, val name: String, val lang: String, val homeUrl: String)
}

internal fun NetworkExtensionStore.Extension.toCatalogExtension(): CatalogExtension = CatalogExtension(
    name = name,
    packageName = packageName,
    versionName = versionName,
    versionCode = versionCode,
    extensionLibVersion = extensionLib,
    isNsfw = contentWarning >= NetworkExtensionStore.ContentWarning.MIXED,
    apkUrl = resources.apkUrl,
    iconUrl = resources.iconUrl,
    sources = sources.map { CatalogExtension.Source(it.id, it.name, it.language, it.homeUrl) },
)
