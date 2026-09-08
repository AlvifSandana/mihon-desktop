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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Sort
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.launch
import mihon.desktop.loader.ExtensionLoader
import mihon.desktop.loader.catalog.CatalogClient
import mihon.desktop.loader.catalog.CatalogExtension
import mihon.desktop.loader.catalog.ExtensionDownloader
import mihon.desktop.loader.log.Logger
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

private val extensionCacheDir = File(System.getProperty("user.home"), ".mihon-desktop/extension-cache")
private const val TAG = "CatalogScreen"

private enum class CatalogSortMode(val label: String) {
    NAME_ASC("Name (A-Z)"),
    NAME_DESC("Name (Z-A)"),
    INSTALLED_FIRST("Installed first"),
    LANGUAGE("By language"),
    VERSION("By version"),
}

/** Browse and install extensions from the keiyoushi catalog. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CatalogScreen(onSourceSelected: (CatalogExtension, Source) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val client = remember { Injekt.get<NetworkHelper>().client }
    val catalogClient = remember { CatalogClient(client) }
    val downloader = remember { ExtensionDownloader(client, extensionCacheDir) }

    var extensions by remember { mutableStateOf<List<CatalogExtension>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var installingPackage by remember { mutableStateOf<String?>(null) }
    var sourcePicker by remember { mutableStateOf<Pair<CatalogExtension, List<Source>>?>(null) }

    // Filters
    var selectedLanguage by remember { mutableStateOf<String?>(null) }
    var showNsfw by remember { mutableStateOf(true) }
    var sortMode by remember { mutableStateOf(CatalogSortMode.INSTALLED_FIRST) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }

    // Track which extensions are already installed
    var installedPackages by remember { mutableStateOf(setOf<String>()) }

    // Get all unique languages from extensions
    val allLanguages = remember(extensions) {
        extensions.flatMap { ext -> ext.sources.map { it.lang } }.distinct().sorted()
    }

    // Check installed extensions on mount and when catalog loads
    LaunchedEffect(Unit) {
        loading = true
        error = null
        runCatching { catalogClient.fetchCatalog() }
            .onSuccess { catalog ->
                extensions = catalog
                // Check which extensions are already installed
                installedPackages = catalog.filter { ext ->
                    extensionCacheDir.listFiles()?.any { file ->
                        file.extension == "jar" && file.name.startsWith(ext.packageName)
                    } == true
                }.map { it.packageName }.toSet()
            }
            .onFailure {
                Logger.e(TAG, "Failed to fetch catalog: ${it.message}", it)
                error = it.message ?: it.toString()
            }
        loading = false
    }

    // Filter and sort extensions
    val filteredExtensions = remember(extensions, installedPackages, query, selectedLanguage, showNsfw, sortMode) {
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
                        compareByDescending<CatalogExtension> { it.packageName in installedPackages }
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
        return extension.packageName in installedPackages
    }

    fun install(extension: CatalogExtension) {
        // Skip if already installed - just load from cache
        if (isInstalled(extension)) {
            scope.launch {
                error = null
                runCatching {
                    val jarFile = extensionCacheDir.listFiles()?.firstOrNull { file ->
                        file.extension == "jar" && file.name.startsWith(extension.packageName)
                    } ?: throw IllegalStateException("Extension jar not found in cache")
                    ExtensionLoader.load(jarFile).sources
                }.onSuccess { sources ->
                    when {
                        sources.isEmpty() -> error = "${extension.name} loaded but declared no sources"
                        sources.size == 1 -> onSourceSelected(extension, sources.first())
                        else -> sourcePicker = extension to sources
                    }
                }.onFailure {
                    Logger.e(TAG, "Failed to load ${extension.name}: ${it.message}", it)
                    error = "Failed to load ${extension.name}: ${it.message ?: it}"
                }
            }
            return
        }

        // Download and install
        scope.launch {
            installingPackage = extension.packageName
            error = null
            runCatching {
                val jar = downloader.download(extension)
                ExtensionLoader.load(jar).sources
            }.onSuccess { sources ->
                // Mark as installed
                installedPackages = installedPackages + extension.packageName
                when {
                    sources.isEmpty() -> error = "${extension.name} loaded but declared no sources"
                    sources.size == 1 -> onSourceSelected(extension, sources.first())
                    else -> sourcePicker = extension to sources
                }
            }.onFailure {
                Logger.e(TAG, "Failed to install ${extension.name}: ${it.message}", it)
                error = "Failed to install ${extension.name}: ${it.message ?: it}"
            }
            installingPackage = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Extensions") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                    }
                    IconButton(onClick = { showFilterMenu = true }) {
                        Icon(Icons.Filled.FilterList, contentDescription = "Filters")
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
                placeholder = { Text("Search extensions") },
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
                            label = { Text("Lang: $lang") },
                        )
                    }
                    if (!showNsfw) {
                        FilterChip(
                            selected = true,
                            onClick = { showNsfw = true },
                            label = { Text("18+ hidden") },
                        )
                    }
                }
            }

            // Stats
            Text(
                "${filteredExtensions.size} extensions · ${installedPackages.size} installed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Error: $error", color = MaterialTheme.colorScheme.error)
                }
                filteredExtensions.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No extensions found")
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(filteredExtensions, key = { it.packageName }) { ext: CatalogExtension ->
                        val busy = installingPackage != null
                        val installed = isInstalled(ext)
                        val langs = ext.sources.map { it.lang }.distinct().joinToString(", ")

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
                                            Text("18+", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            },
                            supportingContent = {
                                Column {
                                    Text("${ext.versionName} · $langs")
                                    if (ext.isNsfw) {
                                        Text(
                                            "NSFW",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            },
                            leadingContent = {
                                if (installed) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = "Installed",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
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
                                    if (installingPackage == ext.packageName) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                    } else if (installed) {
                                        Badge {
                                            Text("Installed")
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

    // Sort menu
    DropdownMenu(
        expanded = showSortMenu,
        onDismissRequest = { showSortMenu = false },
    ) {
        CatalogSortMode.entries.forEach { mode ->
            DropdownMenuItem(
                text = {
                    Text(
                        mode.label,
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
            title = { Text("Filters") },
            text = {
                Column {
                    // NSFW toggle
                    Text("Content", style = MaterialTheme.typography.titleSmall)
                    ListItem(
                        headlineContent = { Text("Show 18+ extensions") },
                        leadingContent = {
                            Checkbox(
                                checked = showNsfw,
                                onCheckedChange = { showNsfw = it },
                            )
                        },
                    )

                    // Language filter
                    Text("Language", style = MaterialTheme.typography.titleSmall)
                    ListItem(
                        headlineContent = { Text("All languages") },
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
                    Text("Done")
                }
            },
        )
    }

    sourcePicker?.let { (extension, sources) ->
        AlertDialog(
            onDismissRequest = { sourcePicker = null },
            title = { Text("Choose a source (${extension.name})") },
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
