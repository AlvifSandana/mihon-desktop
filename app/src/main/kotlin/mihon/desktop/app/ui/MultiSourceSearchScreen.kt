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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import mihon.desktop.loader.ExtensionLoader
import mihon.desktop.app.i18n.t

private val extensionCacheDir = ExtensionLoader.extensionCacheDir

/** Search results for one source, kept under the source's id. */
private data class SourceSearchResults(val sourceName: String, val mangas: List<SManga>)

/**
 * Search across all installed catalogue sources simultaneously.
 * Shows results grouped by source name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiSourceSearchScreen(
    onMangaSelected: (ExtensionRef, Source, SManga) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    // Keyed by source id: display names can collide across extensions, ids cannot.
    var results by remember { mutableStateOf(mapOf<Long, SourceSearchResults>()) }
    var allSources by remember { mutableStateOf(listOf<CatalogueSource>()) }
    // Enough info to reopen a result's source later (see [ExtensionRef]),
    // keyed by source id like the results themselves.
    var sourceRefs by remember { mutableStateOf(mapOf<Long, ExtensionRef>()) }

    // Load all installed catalogue sources (jar parsing is heavy -- keep it off
    // the composition thread).
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val cacheDir = extensionCacheDir
            if (!cacheDir.exists()) {
                allSources = emptyList()
                return@withContext
            }
            val refs = mutableMapOf<Long, ExtensionRef>()
            allSources = cacheDir.listFiles()
                ?.filter { it.isFile && it.extension == "jar" }
                ?.flatMap { jar ->
                    runCatching {
                        val loaded = ExtensionLoader.load(jar)
                        for (source in loaded.sources) {
                            refs[source.id] = ExtensionRef(
                                packageName = loaded.metadata.packageName,
                                jarFileName = jar.name,
                                displayName = loaded.metadata.name ?: loaded.metadata.packageName,
                            )
                        }
                        loaded.sources
                    }.getOrDefault(emptyList())
                }
                ?.filterIsInstance<CatalogueSource>()
                ?: emptyList()
            sourceRefs = refs
        }
    }

    fun search() {
        if (query.isBlank() || allSources.isEmpty()) return
        scope.launch {
            searching = true
            // Query sources in parallel, capped at 4 concurrent requests so a
            // large catalog doesn't hammer the network. A failed source yields
            // null and is simply skipped; it never fails the whole search.
            val semaphore = Semaphore(4)
            val searchResults = coroutineScope {
                allSources.map { source ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            runCatching {
                                source.getSearchManga(1, query.trim(), FilterList())
                            }.getOrNull()
                        }
                    }
                }.awaitAll()
            }
            results = buildMap {
                allSources.forEachIndexed { index, source ->
                    val mangas = searchResults[index]?.mangas
                    if (!mangas.isNullOrEmpty()) {
                        put(source.id, SourceSearchResults(source.name, mangas))
                    }
                }
            }
            searching = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("global_search_title")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("common_back"))
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
                    placeholder = { Text(t("global_search_hint")) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = ::search) {
                    Icon(Icons.Filled.Search, contentDescription = t("common_search"))
                }
            }

            Text(
                t("global_sources_installed", allSources.size),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            when {
                searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                results.isEmpty() && query.isNotBlank() && !searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(t("global_no_results"))
                }
                results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(t("global_type_hint"))
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    results.forEach { (sourceId, group) ->
                        item {
                            Text(
                                group.sourceName,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        items(group.mangas, key = { "$sourceId:${it.url}" }) { manga ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                                    .clickable {
                                        val source = allSources.firstOrNull { it.id == sourceId }
                                        val ref = sourceRefs[sourceId]
                                        if (source != null && ref != null) {
                                            onMangaSelected(ref, source, manga)
                                        }
                                    },
                            ) {
                                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(
                                        key = manga.thumbnail_url,
                                        modifier = Modifier.width(60.dp).height(80.dp).aspectRatio(2f / 3f),
                                    ) {
                                        val url = manga.thumbnail_url ?: return@AsyncImage null
                                        val httpSource = allSources.firstOrNull { it.id == sourceId }
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
