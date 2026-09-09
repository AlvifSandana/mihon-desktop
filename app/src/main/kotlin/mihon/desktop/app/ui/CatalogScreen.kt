package mihon.desktop.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.desktop.loader.ExtensionLoader
import mihon.desktop.loader.catalog.CatalogClient
import mihon.desktop.loader.catalog.CatalogExtension
import mihon.desktop.loader.catalog.ExtensionDownloader
import mihon.desktop.loader.catalog.ExtensionUpdate
import mihon.desktop.loader.catalog.ExtensionUpdateManager
import mihon.desktop.loader.catalog.requireHttps
import mihon.desktop.loader.log.Logger
import mihon.desktop.app.i18n.Strings
import mihon.desktop.app.i18n.t
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private const val TAG = "CatalogScreen"

private enum class CatalogSortMode(val labelKey: String) {
    NAME_ASC("catalog_sort_name_az"),
    NAME_DESC("catalog_sort_name_za"),
    INSTALLED_FIRST("catalog_sort_installed"),
    LANGUAGE("catalog_sort_language"),
    VERSION("catalog_sort_version"),
}

/** Browse and install extensions from the keiyoushi catalog. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CatalogScreen(
    onSourceSelected: (CatalogExtension, Source) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val client = remember { Injekt.get<NetworkHelper>().client }
    val catalogClient = remember { CatalogClient(client) }
    val downloader = remember { ExtensionDownloader(client, ExtensionLoader.extensionCacheDir) }
    // Shared instance so the Browse-tab badge and the management screen see
    // the same pending updates this screen applies.
    val updateManager = remember { ExtensionUpdateManager.default }
    val pendingUpdates by updateManager.pendingUpdates.collectAsState()

    var extensions by remember { mutableStateOf<List<CatalogExtension>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var installingPackage by remember { mutableStateOf<String?>(null) }
    var sourcePicker by remember { mutableStateOf<Pair<CatalogExtension, List<Source>>?>(null) }

    // Installed extensions keyed by package name (from the jar manifests, NOT
    // file-name prefixes -- keiyoushi asset names don't contain the package).
    var installedVersions by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // Per-extension update flow UI state.
    var updatingPackage by remember { mutableStateOf<String?>(null) }
    var updateProgress by remember { mutableStateOf<Float?>(null) }
    var updateStatus by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // Filters
    var selectedLanguage by remember { mutableStateOf<String?>(null) }
    var showNsfw by remember { mutableStateOf(true) }
    var sortMode by remember { mutableStateOf(CatalogSortMode.INSTALLED_FIRST) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }

    // Get all unique languages from extensions
    val allLanguages = remember(extensions) {
        extensions.flatMap { ext -> ext.sources.map { it.lang } }.distinct().sorted()
    }

    val pendingPackageNames = remember(pendingUpdates) {
        pendingUpdates.map { it.packageName }.toSet()
    }

    // Fetch the catalog and refresh installed/known-update state.
    suspend fun reload() {
        runCatching { catalogClient.fetchCatalog() }
            .onSuccess { catalog ->
                extensions = catalog
                // Populate the shared pending-updates flow (also feeds the
                // Browse badge); failures here just mean no update info.
                runCatching { updateManager.checkForUpdates(catalog = catalog) }
                    .onFailure { Logger.w(TAG, "Extension update check failed: ${it.message}") }
                installedVersions = updateManager.discoverInstalled()
                    .associate { it.packageName to (it.versionName ?: "unknown") }
            }
            .onFailure {
                Logger.e(TAG, "Failed to fetch catalog: ${it.message}", it)
                error = it.message ?: it.toString()
            }
    }

    // Check installed extensions on mount
    LaunchedEffect(Unit) {
        loading = true
        error = null
        reload()
        loading = false
    }

    // Filter and sort extensions
    val filteredExtensions = remember(extensions, installedVersions, pendingPackageNames, query, selectedLanguage, showNsfw, sortMode) {
        extensions
            // Text search
            .filter { ext ->
                query.isBlank() || ext.name.contains(query, ignoreCase = true) ||
                    ext.packageName.contains(query, ignoreCase = true)
            }
            // Language filter
            .filter { ext ->
                selectedLanguage == null || ext.sources.any { it.lang == selectedLanguage }
            }
            // NSFW filter
            .filter { ext ->
                showNsfw || !ext.isNsfw
            }
            // Sort
            .let { list ->
                when (sortMode) {
                    CatalogSortMode.NAME_ASC -> list.sortedBy { it.name.lowercase() }
                    CatalogSortMode.NAME_DESC -> list.sortedByDescending { it.name.lowercase() }
                    CatalogSortMode.INSTALLED_FIRST -> list.sortedWith(
                        // Update-available entries sort above plain installs.
                        compareByDescending<CatalogExtension> { it.packageName in pendingPackageNames }
                            .thenByDescending { it.packageName in installedVersions }
                            .thenBy { it.name.lowercase() }
                    )
                    CatalogSortMode.LANGUAGE -> list.sortedWith(
                        compareBy<CatalogExtension> { ext -> ext.sources.firstOrNull()?.lang ?: "" }
                            .thenBy { it.name.lowercase() }
                    )
                    CatalogSortMode.VERSION -> list.sortedByDescending { it.versionCode }
                }
            }
    }

    fun isInstalled(extension: CatalogExtension): Boolean {
        return extension.packageName in installedVersions
    }

    fun updateExtension(update: ExtensionUpdate) {
        scope.launch {
            updatingPackage = update.packageName
            updateProgress = null
            error = null
            runCatching {
                updateManager.applyUpdate(update) { bytes, total ->
                    updateProgress = if (total != null && total > 0) bytes.toFloat() / total else null
                }
            }.onSuccess {
                updateStatus = updateStatus + (update.packageName to Strings.get("catalog_updated_to", update.newVersionName))
                installedVersions = updateManager.discoverInstalled()
                    .associate { it.packageName to (it.versionName ?: "unknown") }
            }.onFailure {
                Logger.e(TAG, "Failed to update ${update.catalogEntry.name}: ${it.message}", it)
                error = Strings.get("error_update_failed", update.catalogEntry.name, it.message ?: it)
            }
            updatingPackage = null
            updateProgress = null
        }
    }

    fun install(extension: CatalogExtension) {
        // Skip if already installed - just load from cache
        if (isInstalled(extension)) {
            scope.launch {
                error = null
                runCatching {
                    withContext(Dispatchers.IO) {
                        // Resolve the jar via its manifest, NOT file-name
                        // prefixes -- keiyoushi asset names don't contain the
                        // package name, so a prefix match never hits.
                        val jarFile = updateManager.discoverInstalled()
                            .firstOrNull { it.packageName == extension.packageName }?.jarFile
                            ?: throw IllegalStateException(
                                "No cached jar found for ${extension.packageName} -- " +
                                    "reinstall it from the catalog",
                            )
                        ExtensionLoader.load(jarFile).sources
                    }
                }.onSuccess { sources ->
                    when {
                        sources.isEmpty() -> error = Strings.get("catalog_error_no_sources", extension.name)
                        sources.size == 1 -> onSourceSelected(extension, sources.first())
                        else -> sourcePicker = extension to sources
                    }
                }.onFailure {
                    Logger.e(TAG, "Failed to load ${extension.name}: ${it.message}", it)
                    error = Strings.get("error_load_failed", extension.name, it.message ?: it)
                }
            }
            return
        }

        // Download and install
        scope.launch {
            installingPackage = extension.packageName
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val jar = downloader.download(extension)
                    ExtensionLoader.load(jar).sources
                }
            }.onSuccess { sources ->
                // Mark as installed
                installedVersions = installedVersions + (extension.packageName to extension.versionName)
                when {
                    sources.isEmpty() -> error = Strings.get("catalog_error_no_sources", extension.name)
                    sources.size == 1 -> onSourceSelected(extension, sources.first())
                    else -> sourcePicker = extension to sources
                }
            }.onFailure {
                Logger.e(TAG, "Failed to install ${extension.name}: ${it.message}", it)
                error = Strings.get("error_install_failed", extension.name, it.message ?: it)
            }
            installingPackage = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("catalog_title")) },
                navigationIcon = if (showBack) {
                    {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("common_back")) }
                    }
                } else {
                    {}
                },
                actions = {
                    // Re-fetch the catalog and re-check for extension updates
                    // (the pull-to-refresh equivalent for this list).
                    IconButton(
                        onClick = {
                            scope.launch {
                                refreshing = true
                                error = null
                                reload()
                                refreshing = false
                            }
                        },
                        enabled = !refreshing && !loading,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = t("action_refresh"))
                    }
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = t("action_sort"))
                    }
                    IconButton(onClick = { showFilterMenu = true }) {
                        Icon(Icons.Filled.FilterList, contentDescription = t("action_filters"))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Search bar
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                placeholder = { Text(t("catalog_search_hint")) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )

            // Active filters display
            if (selectedLanguage != null || !showNsfw) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    selectedLanguage?.let { lang ->
                        FilterChip(
                            selected = true,
                            onClick = { selectedLanguage = null },
                            label = { Text(t("catalog_filter_lang", lang)) },
                        )
                    }
                    if (!showNsfw) {
                        FilterChip(
                            selected = true,
                            onClick = { showNsfw = true },
                            label = { Text(t("catalog_filter_nsfw_hidden")) },
                        )
                    }
                }
            }

            // Stats
            Text(
                t("catalog_stats", filteredExtensions.size, installedVersions.size) +
                    (pendingUpdates.size.takeIf { it > 0 }?.let { t("catalog_updates_suffix", it) } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                refreshing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> Column(Modifier.fillMaxSize()) {
                    // Failed refresh/install/update: keep the existing list and
                    // surface the error as a banner instead of replacing the
                    // list with an error state.
                    error?.let {
                        Text(
                            t("common_error_prefix", it),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    if (filteredExtensions.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(t("catalog_no_results"))
                        }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(filteredExtensions, key = { it.packageName }) { ext: CatalogExtension ->
                        val busy = installingPackage != null || updatingPackage != null
                        val installed = isInstalled(ext)
                        val langs = ext.sources.map { it.lang }.distinct().joinToString(", ")
                        val pendingUpdate = pendingUpdates.firstOrNull { it.packageName == ext.packageName }
                        val status = updateStatus[ext.packageName]

                        ListItem(
                            headlineContent = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(ext.name)
                                    if (ext.isNsfw) {
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.error,
                                        ) {
                                            Text(t("catalog_nsfw_badge"), style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            },
                            supportingContent = {
                                Column {
                                    Text("${ext.versionName} · $langs")
                                    if (ext.isNsfw) {
                                        Text(
                                            t("catalog_nsfw"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    if (pendingUpdate != null) {
                                        Text(
                                            t(
                                                "catalog_update_available",
                                                installedVersions[ext.packageName] ?: "?",
                                                pendingUpdate.newVersionName,
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    } else if (status != null) {
                                        Text(
                                            status,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            },
                            leadingContent = {
                                AsyncImage(
                                    key = ext.iconUrl,
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    // Catalog-derived URL: https enforced, failure just
                                    // means no icon.
                                    client.newCall(GET(requireHttps(ext.iconUrl))).execute().use { response ->
                                        if (response.isSuccessful) response.body?.bytes() else null
                                    }
                                }
                            },
                            trailingContent = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Language badge
                                    if (langs.isNotBlank()) {
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        ) {
                                            Text(langs, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    when {
                                        installingPackage == ext.packageName -> {
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                        }

                                        updatingPackage == ext.packageName -> {
                                            // Download progress; indeterminate when the
                                            // server doesn't report a content length.
                                            updateProgress?.let { fraction ->
                                                LinearProgressIndicator(
                                                    progress = { fraction.coerceIn(0f, 1f) },
                                                    modifier = Modifier.width(72.dp),
                                                )
                                            } ?: CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                        }

                                        pendingUpdate != null -> {
                                            TextButton(
                                                onClick = { updateExtension(pendingUpdate) },
                                                enabled = !busy,
                                            ) {
                                                Text(t("common_update"))
                                            }
                                        }

                                        installed -> {
                                            Badge { Text(t("catalog_installed")) }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable(enabled = !busy) { install(ext) },
                        )
                    }
                        }
                    }
                }
            }
        }
    }

    // Sort menu
    DropdownMenu(
        expanded = showSortMenu,
        onDismissRequest = { showSortMenu = false },
    ) {
        CatalogSortMode.entries.forEach { mode ->
            DropdownMenuItem(
                text = {
                    Text(
                        t(mode.labelKey),
                        color = if (sortMode == mode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                },
                onClick = {
                    sortMode = mode
                    showSortMenu = false
                },
            )
        }
    }

    // Filter menu
    if (showFilterMenu) {
        AlertDialog(
            onDismissRequest = { showFilterMenu = false },
            title = { Text(t("filters_title")) },
            text = {
                Column {
                    // NSFW toggle
                    Text(t("filters_content"), style = MaterialTheme.typography.titleSmall)
                    ListItem(
                        headlineContent = { Text(t("filters_show_nsfw")) },
                        leadingContent = {
                            Checkbox(
                                checked = showNsfw,
                                onCheckedChange = { showNsfw = it },
                            )
                        },
                    )

                    // Language filter
                    Text(t("filters_language"), style = MaterialTheme.typography.titleSmall)
                    ListItem(
                        headlineContent = { Text(t("filters_all_languages")) },
                        leadingContent = {
                            Checkbox(
                                checked = selectedLanguage == null,
                                onCheckedChange = { if (it) selectedLanguage = null },
                            )
                        },
                    )
                    allLanguages.forEach { lang ->
                        ListItem(
                            headlineContent = { Text(lang) },
                            leadingContent = {
                                Checkbox(
                                    checked = selectedLanguage == lang,
                                    onCheckedChange = { if (it) selectedLanguage = lang },
                                )
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFilterMenu = false }) {
                    Text(t("common_done"))
                }
            },
        )
    }

    sourcePicker?.let { (extension, sources) ->
        AlertDialog(
            onDismissRequest = { sourcePicker = null },
            title = { Text(t("catalog_choose_source", extension.name)) },
            text = {
                Column {
                    sources.forEach { source ->
                        Text(
                            "${source.name} (${source.lang})",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                .clickable {
                                    sourcePicker = null
                                    onSourceSelected(extension, source)
                                },
                        )
                    }
                }
            },
            confirmButton = {},
        )
    }
}
