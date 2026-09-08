package mihon.desktop.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.desktop.loader.ExtensionLoader
import mihon.desktop.loader.catalog.CatalogExtension
import mihon.desktop.loader.library.LibraryManga
import mihon.desktop.loader.library.LibraryRepository
import java.io.File

private val extensionCacheDir = File(System.getProperty("user.home"), ".mihon-desktop/extension-cache")

/**
 * Browse tab with sub-tabs: Sources, Extensions, Migration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    onSourceSelected: (CatalogExtension, Source) -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Sources", "Extensions", "Migration")

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Browse") })

        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) },
                )
            }
        }

        when (selectedTab) {
            0 -> SourcesTab(onSourceSelected = onSourceSelected)
            1 -> CatalogScreen(
                onSourceSelected = onSourceSelected,
                onBack = { selectedTab = 0 },
                showBack = false,
            )
            2 -> MigrationTab()
        }
    }
}

/**
 * Sources sub-tab: scans installed extension jars, loads their sources,
 * and displays them grouped by language.
 */
@Composable
private fun SourcesTab(onSourceSelected: (CatalogExtension, Source) -> Unit) {
    var sourcesByLanguage by remember { mutableStateOf<Map<String, List<Pair<CatalogExtension, Source>>>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        sourcesByLanguage = withContext(Dispatchers.IO) {
            val jars = extensionCacheDir.listFiles()
                ?.filter { it.isFile && it.extension == "jar" }
                ?: emptyList()

            val result = mutableMapOf<String, MutableList<Pair<CatalogExtension, Source>>>()
            for (jar in jars) {
                runCatching {
                    val loaded = ExtensionLoader.load(jar)
                    val catalogExt = CatalogExtension(
                        name = loaded.metadata.name ?: loaded.metadata.packageName,
                        packageName = loaded.metadata.packageName,
                        versionName = loaded.metadata.versionName ?: "unknown",
                        versionCode = 0L,
                        extensionLibVersion = loaded.metadata.extensionLibVersion ?: "",
                        isNsfw = loaded.metadata.isNsfw,
                        // apkUrl is synthesized so that CatalogExtension.jarFileName
                        // resolves back to this jar's real cache file name -- library
                        // entries saved from this flow re-load the jar via that name.
                        apkUrl = jar.name.removeSuffix(".jar") + ".apk",
                        iconUrl = "",
                        sources = loaded.sources.map {
                            CatalogExtension.Source(
                                id = it.id,
                                name = it.name,
                                lang = it.lang,
                                homeUrl = "",
                            )
                        },
                    )
                    for (source in loaded.sources) {
                        val lang = source.lang.ifBlank { "Other" }
                        result.getOrPut(lang) { mutableListOf() }.add(catalogExt to source)
                    }
                }
            }
            result.mapValues { (_, list) -> list.sortedBy { (_, src) -> src.name } }
        }
        loading = false
    }

    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        sourcesByLanguage.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No sources installed")
                Text(
                    "Install extensions from the Extensions tab",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> {
            val languages = sourcesByLanguage.keys.sorted()
            LazyColumn(Modifier.fillMaxSize()) {
                languages.forEach { lang ->
                    item(key = "header_$lang") {
                        Text(
                            lang,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(
                        sourcesByLanguage[lang] ?: emptyList(),
                        key = { "${it.second.id}" },
                    ) { (ext, source) ->
                        ListItem(
                            headlineContent = { Text(source.name) },
                            supportingContent = { Text(ext.name) },
                            modifier = Modifier.clickable {
                                onSourceSelected(ext, source)
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/**
 * Migration tab: shows installed sources that have library manga.
 * Users can select a source to migrate its manga to another source.
 */
@Composable
private fun MigrationTab() {
    val repository = remember { LibraryRepository() }
    var libraryBySource by remember { mutableStateOf<Map<Long, List<LibraryManga>>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        libraryBySource = repository.all().groupBy { it.sourceId }
        loading = false
    }

    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        libraryBySource.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No library manga to migrate")
                Text(
                    "Add manga to your library first",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> {
            val sources = libraryBySource.keys.sorted()
            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "header") {
                    Text(
                        "Select a source to migrate from",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(sources, key = { it }) { sourceId ->
                    val manga = libraryBySource[sourceId].orEmpty()
                    ListItem(
                        headlineContent = { Text(manga.firstOrNull()?.extensionName ?: "Unknown source") },
                        supportingContent = { Text("${manga.size} manga in library") },
                        modifier = Modifier.clickable {
                            // TODO: open migration flow for this source
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
