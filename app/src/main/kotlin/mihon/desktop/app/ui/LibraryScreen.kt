package mihon.desktop.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import mihon.desktop.loader.ExtensionLoader
import mihon.desktop.loader.library.LibraryManga
import mihon.desktop.loader.library.LibraryRepository
import java.io.File

private val extensionCacheDir = File(System.getProperty("user.home"), ".mihon-desktop/extension-cache")

/**
 * Shows every manga the user has added to their library. Reopening one re-loads its
 * extension jar from the local cache (no network/catalog fetch) and reconstructs an
 * [SManga] from the saved fields -- see `docs/ARCHITECTURE.md` for why that's enough.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpenManga: (ExtensionRef, Source, SManga) -> Unit, onBrowseExtensions: () -> Unit) {
    val repository = remember { LibraryRepository() }
    var entries by remember { mutableStateOf(listOf<LibraryManga>()) }
    var sourcesByJar by remember { mutableStateOf(mapOf<String, List<Source>>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        error = null
        runCatching {
            val libraryEntries = repository.all()
            val loadedSources = libraryEntries.map { it.jarFileName }.distinct().associateWith { jarFileName ->
                runCatching {
                    ExtensionLoader.load(File(extensionCacheDir, jarFileName)).sources
                }.getOrDefault(emptyList())
            }
            libraryEntries to loadedSources
        }.onSuccess { (libraryEntries, loadedSources) ->
            entries = libraryEntries
            sourcesByJar = loadedSources
        }.onFailure {
            error = it.message ?: it.toString()
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = onBrowseExtensions) {
                        Icon(Icons.Filled.Add, contentDescription = "Browse extensions")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Error loading library: $error", color = MaterialTheme.colorScheme.error)
                }
                entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Your library is empty")
                        Button(onClick = onBrowseExtensions, modifier = Modifier.padding(top = 8.dp)) {
                            Text("Browse extensions")
                        }
                    }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    contentPadding = PaddingValues(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(entries, key = { it.sourceId to it.mangaUrl }) { entry ->
                        val source = sourcesByJar[entry.jarFileName]?.firstOrNull { it.id == entry.sourceId }
                        Column(
                            Modifier.padding(4.dp).clickable(enabled = source != null) {
                                val src = source ?: return@clickable
                                val manga = SManga.create().apply {
                                    url = entry.mangaUrl
                                    title = entry.title
                                    thumbnail_url = entry.thumbnailUrl
                                    author = entry.author
                                }
                                onOpenManga(
                                    ExtensionRef(entry.packageName, entry.jarFileName, entry.extensionName),
                                    src,
                                    manga,
                                )
                            },
                        ) {
                            AsyncImage(
                                key = entry.thumbnailUrl,
                                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).height(180.dp),
                            ) {
                                val url = entry.thumbnailUrl ?: return@AsyncImage null
                                fetchThumbnailBytes(source, url)
                            }
                            Text(
                                entry.title,
                                maxLines = 2,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            if (source == null) {
                                Text(
                                    "Extension not installed",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fetchThumbnailBytes(source: Source?, url: String): ByteArray? {
    val httpSource = source as? HttpSource ?: return null
    return httpSource.client.newCall(GET(url, httpSource.headers)).execute().use { response ->
        if (response.isSuccessful) response.body.bytes() else null
    }
}
