package mihon.desktop.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.launch
import mihon.desktop.loader.ExtensionLoader
import java.io.File

private val extensionCacheDir = File(System.getProperty("user.home"), ".mihon-desktop/extension-cache")

/**
 * Search across all installed catalogue sources simultaneously.
 * Shows results grouped by source name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiSourceSearchScreen(
    onMangaSelected: (Source, SManga) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(mapOf<String, List<SManga>>()) }
    var error by remember { mutableStateOf<String?>(null) }

    // Load all installed catalogue sources
    val allSources = remember {
        val cacheDir = extensionCacheDir
        if (!cacheDir.exists()) return@remember emptyList()
        cacheDir.listFiles()
            ?.filter { it.extension == ".jar" }
            ?.flatMap { jar ->
                runCatching { ExtensionLoader.load(jar).sources }.getOrDefault(emptyList())
            }
            ?.filterIsInstance<CatalogueSource>()
            ?: emptyList()
    }

    fun search() {
        if (query.isBlank() || allSources.isEmpty()) return
        scope.launch {
            searching = true
            error = null
            val grouped = mutableMapOf<String, List<SManga>>()
            for (source in allSources) {
                runCatching {
                    val result = source.getSearchManga(1, query.trim(), FilterList())
                    if (result.mangas.isNotEmpty()) {
                        grouped[source.name] = result.mangas
                    }
                }
            }
            results = grouped
            searching = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search everywhere") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Search bar
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search all sources...") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = ::search) {
                    Icon(Icons.Filled.Search, contentDescription = "Search")
                }
            }

            Text(
                "${allSources.size} sources installed",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            when {
                searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Error: $error", color = MaterialTheme.colorScheme.error)
                }
                results.isEmpty() && query.isNotBlank() && !searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No results found")
                }
                results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Type to search across all sources")
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    results.forEach { (sourceName, mangas) ->
                        item {
                            Text(
                                sourceName,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        items(mangas, key = { "${sourceName}:${it.url}" }) { manga ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                                    .clickable {
                                        val source = allSources.firstOrNull { it.name == sourceName }
                                        if (source != null) {
                                            onMangaSelected(source, manga)
                                        }
                                    },
                            ) {
                                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(
                                        key = manga.thumbnail_url,
                                        modifier = Modifier.width(60.dp).height(80.dp).aspectRatio(2f / 3f),
                                    ) {
                                        val url = manga.thumbnail_url ?: return@AsyncImage null
                                        val httpSource = allSources.firstOrNull { it.name == sourceName }
                                            as? eu.kanade.tachiyomi.source.online.HttpSource
                                        httpSource?.let { src ->
                                            eu.kanade.tachiyomi.network.GET(url, src.headers).let { req ->
                                                src.client.newCall(req).execute().use { resp ->
                                                    if (resp.isSuccessful) resp.body.bytes() else null
                                                }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(manga.title, style = MaterialTheme.typography.bodyMedium)
                                        manga.author?.let {
                                            Text(it, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
