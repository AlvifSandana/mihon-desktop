package mihon.desktop.loader.catalog

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mihon.desktop.loader.ExtensionLoader
import mihon.desktop.loader.ExtensionMetadataReader
import mihon.desktop.loader.log.Logger
import mihon.desktop.loader.library.LibraryRepository
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ExtensionUpdateManager"

/** An extension jar present in the local cache, described by its own manifest. */
data class InstalledExtension(
    val jarFile: File,
    val packageName: String,
    val name: String,
    val versionCode: Long?,
    val versionName: String?,
)

/** An installed extension with a strictly newer version available in the catalog. */
data class ExtensionUpdate(
    val installed: InstalledExtension,
    val catalogEntry: CatalogExtension,
    val newVersionCode: Long,
    val newVersionName: String,
) {
    val packageName: String get() = installed.packageName
}

/**
 * Detects and applies extension updates: installed jars (matched by package name via
 * their manifests) versus the remote keiyoushi catalog.
 *
 * - [discoverInstalled] parses each cached jar's manifest only -- no class loading, no
 *   source instantiation, so it's safe to run on any screen entry.
 * - [findUpdates] is pure: given installed + catalog lists, returns the entries where
 *   the catalog version is strictly newer ([VersionComparator] -- versionCode when the
 *   installed jar has one, else version-name compare).
 * - [applyUpdate] downloads the new jar through [ExtensionDownloader] (sha256 + zip
 *   validated BEFORE the old file is touched), re-points the library/update-history
 *   DB rows at the new jar file name, and then retires the old jar via
 *   [ExtensionLoader.invalidateForUpdate]. One update runs at a time per package
 *   (Mutex keyed on package name).
 *
 * No restart is needed after an update: [ExtensionLoader] keys its cache on
 * (path, lastModified, length), so the next `load` picks up the new jar and
 * re-instantiates the sources. Screens that already hold old [Source] instances keep
 * them until the user revisits the screen.
 *
 * [pendingUpdates] is the reactive update list--the app badge and the extension
 * screens all observe it, so applying an update anywhere refreshes the count everywhere.
 */
class ExtensionUpdateManager(
    private val catalogClient: CatalogClient,
    private val downloader: ExtensionDownloader,
    private val libraryRepository: LibraryRepository,
    private val cacheDir: File,
) {
    constructor(
        client: OkHttpClient,
        cacheDir: File = ExtensionLoader.extensionCacheDir,
    ) : this(
        CatalogClient(client),
        ExtensionDownloader(client, cacheDir),
        LibraryRepository(),
        cacheDir,
    )

    /** Package name -> in-flight update guard; one download per package at a time. */
    private val packageLocks = ConcurrentHashMap<String, Mutex>()

    private val _pendingUpdates = MutableStateFlow<List<ExtensionUpdate>>(emptyList())
    val pendingUpdates: StateFlow<List<ExtensionUpdate>> = _pendingUpdates.asStateFlow()

    /**
     * Scans the extension cache and describes each installed jar from its manifest.
     * Unreadable jars are skipped; when several jars claim the same package (e.g. an
     * old file left behind by a manual copy), only the newest is reported.
     */
    suspend fun discoverInstalled(): List<InstalledExtension> = withContext(Dispatchers.IO) {
        val jars = cacheDir.listFiles()
            ?.filter { it.isFile && it.extension == "jar" }
            .orEmpty()

        jars.mapNotNull { jar ->
            runCatching { ExtensionMetadataReader.read(jar) }.getOrNull()
                ?.let { meta ->
                    InstalledExtension(
                        jarFile = jar,
                        packageName = meta.packageName,
                        name = meta.name ?: meta.packageName,
                        versionCode = meta.versionCode,
                        versionName = meta.versionName,
                    )
                }
        }.groupBy { it.packageName }
            .map { (_, group) -> group.maxWith(installedOrder) }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Pure update detection: for every installed extension with a catalog entry under
     * the same package name, decides whether the catalog version is strictly newer.
     * Installed versions newer than the catalog (stale index) and packages missing from
     * the catalog produce no update.
     */
    fun findUpdates(
        installed: List<InstalledExtension>,
        catalog: List<CatalogExtension>,
    ): List<ExtensionUpdate> {
        val catalogByPackage = catalog.associateBy { it.packageName }
        return installed.mapNotNull { ext ->
            val entry = catalogByPackage[ext.packageName] ?: return@mapNotNull null
            val isNewer = VersionComparator.isNewerVersion(
                installedVersionCode = ext.versionCode,
                installedVersionName = ext.versionName,
                newVersionCode = entry.versionCode,
                newVersionName = entry.versionName,
            )
            if (!isNewer) return@mapNotNull null
            ExtensionUpdate(
                installed = ext,
                catalogEntry = entry,
                newVersionCode = entry.versionCode,
                newVersionName = entry.versionName,
            )
        }
    }

    /**
     * Fetches the remote catalog, scans the local cache, and refreshes
     * [pendingUpdates]. Throws on catalog fetch failure (callers decide whether
     * that's fatal -- background checks usually just log it).
     *
     * @param catalog pre-fetched catalog entries; when null (the default) the remote
     *   keiyoushi index is fetched. Tests inject entries to stay hermetic; the UI can
     *   pass an already-loaded catalog to avoid a second fetch.
     */
    suspend fun checkForUpdates(catalog: List<CatalogExtension>? = null): List<ExtensionUpdate> =
        withContext(Dispatchers.IO) {
            val resolved = catalog ?: catalogClient.fetchCatalog()
            val updates = findUpdates(discoverInstalled(), resolved)
            _pendingUpdates.value = updates
            Logger.i(TAG, "Extension update check: ${updates.size} update(s) available")
            updates
        }

    /**
     * Downloads and installs the new jar for [update], then retires the old one.
     * The old jar is only touched after the new one is downloaded, sha256-checked
     * (when the manifest is reachable), zip-validated, and atomically moved into
     * place -- a failed download leaves the installed extension fully intact.
     *
     * Order matters once the new jar is in place: DB rows are re-pointed at the
     * new file name BEFORE the old jar is deleted, so persisted `jarFileName`
     * values never reference a jar that no longer exists (library entries load
     * through `ExtensionLoader.loadCached(jarFileName)`).
     *
     * @param onProgress called as (bytes downloaded, total bytes or null) during the
     *   download; runs on an IO thread, not the UI thread.
     * @return the new jar file.
     */
    suspend fun applyUpdate(
        update: ExtensionUpdate,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ): File {
        val lock = packageLocks.computeIfAbsent(update.packageName) { Mutex() }
        return lock.withLock {
            val newJar = downloader.download(update.catalogEntry, onProgress)
            val oldJar = update.installed.jarFile
            if (newJar.absolutePath != oldJar.absolutePath) {
                // Swap done: rebind DB rows first, then retire the old file.
                libraryRepository.rebindJarFileName(update.packageName, newJar.name)
                ExtensionLoader.invalidateForUpdate(oldJar)
            }
            _pendingUpdates.value =
                _pendingUpdates.value.filterNot { it.packageName == update.packageName }
            Logger.i(
                TAG,
                "Updated ${update.packageName} to v${update.newVersionName} " +
                    "(${oldJar.name} -> ${newJar.name})",
            )
            newJar
        }
    }

    /** Applies every pending update sequentially; failures are captured per update. */
    suspend fun applyAllPending(
        onProgress: (packageName: String, bytes: Long, total: Long?) -> Unit = { _, _, _ -> },
    ): List<Pair<ExtensionUpdate, Result<File>>> {
        val results = mutableListOf<Pair<ExtensionUpdate, Result<File>>>()
        // Iterate over a snapshot: applyUpdate mutates _pendingUpdates.
        for (update in _pendingUpdates.value.toList()) {
            val result = runCatching {
                applyUpdate(update) { bytes, total -> onProgress(update.packageName, bytes, total) }
            }
            result.onFailure {
                Logger.e(TAG, "Failed to update ${update.packageName}: ${it.message}", it)
            }
            results += update to result
        }
        return results
    }

    /** Ascending, so [maxWith] keeps the newest version. */
    private val installedOrder = compareBy<InstalledExtension> { it.versionCode ?: Long.MIN_VALUE }
        .thenBy { VersionComparator.compareVersionNames(it.versionName ?: "", "0") }

    companion object {
        /** Shared app-wide instance: the badge and every extension screen observe it. */
        val default: ExtensionUpdateManager by lazy {
            ExtensionUpdateManager(Injekt.get<NetworkHelper>().client)
        }
    }
}
