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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.desktop.loader.ExtensionLoader
import mihon.desktop.loader.library.LibraryRepository
import mihon.desktop.loader.library.SelectAllWithManga

/**
 * Shows reading history grouped by date. Each item shows manga thumbnail, title,
 * chapter name, and relative time. Clicking opens manga detail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onOpenManga: (ExtensionRef, Source, SManga) -> Unit,
) {
    val repository = remember { LibraryRepository() }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(listOf<SelectAllWithManga>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var sourcesByJar by remember { mutableStateOf(mapOf<String, List<Source>>()) }

    LaunchedEffect(Unit) {
        loading = true
        error = null
        runCatching {
            val result = repository.historyEntries()
            val loaded = withContext(Dispatchers.IO) {
                result.map { it.jarFileName }.distinct().associateWith { jar ->
                    runCatching { ExtensionLoader.loadCached(jar).sources }.getOrDefault(emptyList())
                }
            }
            result to loaded
        }.onSuccess { (result, loaded) ->
            entries = result
            sourcesByJar = loaded
        }.onFailure {
            if (it is CancellationException) throw it
            error = it.message ?: it.toString()
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                actions = {
                    if (entries.isNotEmpty()) {
                        IconButton(onClick = {
                            scope.launch {
                                repository.clearAllHistory()
                                entries = emptyList()
                            }
                        }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear all")
                        }
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
                    Text("Error: $error", color = MaterialTheme.colorScheme.error)
                }
                entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No reading history")
                }
                else -> {
                    val grouped = remember(entries) {
                        entries.groupBy { entry -> formatDateGroupLabel(entry.readAt) }
                    }

                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                        grouped.forEach { (dateLabel, items) ->
                            item(key = "header_$dateLabel") {
                                Text(
                                    dateLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            items(items, key = { "${it.sourceId}:${it.chapterUrl}" }) { entry ->
                                val source = sourcesByJar[entry.jarFileName]?.firstOrNull { it.id == entry.sourceId }
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable(enabled = source != null) {
                                            val src = source ?: return@clickable
                                            val manga = SManga.create().apply {
                                                url = entry.mangaUrl
                                                title = entry.mangaTitle
                                                thumbnail_url = entry.thumbnailUrl
                                            }
                                            onOpenManga(
                                                ExtensionRef(entry.packageName, entry.jarFileName, entry.extensionName),
                                                src,
                                                manga,
                                            )
                                        }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (entry.thumbnailUrl != null) {
                                        AsyncImage(
                                            key = entry.thumbnailUrl,
                                            modifier = Modifier.height(48.dp).aspectRatio(2f / 3f),
                                        ) {
                                            val url = entry.thumbnailUrl ?: return@AsyncImage null
                                            fetchThumbnailBytes(source, url)
                                        }
                                        Spacer(Modifier.width(12.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            entry.mangaTitle,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            entry.chapterUrl.substringAfterLast("/"),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        formatRelativeTime(entry.readAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}
