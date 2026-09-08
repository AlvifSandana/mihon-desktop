package mihon.desktop.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.desktop.loader.catalog.CatalogExtension

/** Browses one loaded [Source]: popular manga by default, or search results. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceBrowseScreen(
    extension: CatalogExtension,
    source: Source,
    onMangaSelected: (SManga) -> Unit,
    onBack: () -> Unit,
) {
    val catalogueSource = source as? CatalogueSource
    val scope = rememberCoroutineScope()

    var mangas by remember(source) { mutableStateOf(listOf<SManga>()) }
    var nextPage by remember(source) { mutableStateOf(1) }
    var hasNextPage by remember(source) { mutableStateOf(false) }
    var loading by remember(source) { mutableStateOf(false) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var query by remember(source) { mutableStateOf("") }

    fun load(reset: Boolean) {
        val src = catalogueSource ?: return
        scope.launch {
            loading = true
            error = null
            val targetPage = if (reset) 1 else nextPage
            runCatching {
                // Source catalogue calls do network I/O -- keep them off the UI thread.
                withContext(Dispatchers.IO) {
                    if (query.isBlank()) {
                        src.getPopularManga(targetPage)
                    } else {
                        src.getSearchManga(targetPage, query, src.getFilterList())
                    }
                }
            }.onSuccess { result ->
                mangas = if (reset) result.mangas else mangas + result.mangas
                hasNextPage = result.hasNextPage
                nextPage = targetPage + 1
            }.onFailure {
                error = it.message ?: it.toString()
            }
            loading = false
        }
    }

    LaunchedEffect(source) { load(reset = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(extension.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        if (catalogueSource == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("This source doesn't support browsing")
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search ${extension.name}") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { load(reset = true) }),
                )
                Button(onClick = { load(reset = true) }, modifier = Modifier.padding(start = 8.dp)) {
                    Text("Search")
                }
            }

            if (error != null) {
                Text(
                    "Error: $error",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                contentPadding = PaddingValues(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(mangas) { manga ->
                    MangaGridItem(source = source, manga = manga, onClick = { onMangaSelected(manga) })
                }
                if (hasNextPage || loading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            if (loading) {
                                CircularProgressIndicator()
                            } else {
                                Button(onClick = { load(reset = false) }) { Text("Load more") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MangaGridItem(source: Source, manga: SManga, onClick: () -> Unit) {
    Column(Modifier.padding(4.dp).clickable(onClick = onClick)) {
        AsyncImage(
            key = manga.thumbnail_url,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
        ) {
            manga.thumbnail_url?.let { fetchImageBytes(source, it) }
        }
        Text(
            manga.title,
            maxLines = 2,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun fetchImageBytes(source: Source, url: String): ByteArray? {
    val httpSource = source as? HttpSource ?: return null
    return httpSource.client.newCall(GET(url, httpSource.headers)).execute().use { response ->
        if (response.isSuccessful) response.body.bytes() else null
    }
}
