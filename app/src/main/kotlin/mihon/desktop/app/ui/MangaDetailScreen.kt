package mihon.desktop.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.launch
import mihon.desktop.loader.library.LibraryRepository
import mihon.desktop.loader.library.ReadingProgress
import java.text.DateFormat
import java.util.Date

/** Fetches full details + chapter list for one manga and lets the user pick a chapter to read. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaDetailScreen(
    extensionRef: ExtensionRef,
    source: Source,
    manga: SManga,
    onChapterSelected: (chapters: List<SChapter>, chapterIndex: Int, initialPageIndex: Int) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val repository = remember { LibraryRepository() }

    var detail by remember(manga) { mutableStateOf(manga) }
    var chapters by remember(manga) { mutableStateOf(listOf<SChapter>()) }
    var loading by remember(manga) { mutableStateOf(true) }
    var error by remember(manga) { mutableStateOf<String?>(null) }
    var isFavorite by remember(manga) { mutableStateOf(false) }
    var progress by remember(manga) { mutableStateOf<ReadingProgress?>(null) }

    LaunchedEffect(manga) {
        loading = true
        error = null
        isFavorite = repository.isFavorite(source.id, manga.url)
        progress = repository.progressFor(source.id, manga.url)
        runCatching {
            source.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)
        }.onSuccess { update ->
            detail = update.manga
            chapters = update.chapters
        }.onFailure {
            error = it.message ?: it.toString()
        }
        loading = false
    }

    fun toggleFavorite() {
        scope.launch {
            if (isFavorite) {
                repository.remove(source.id, manga.url)
                isFavorite = false
            } else {
                repository.add(
                    sourceId = source.id,
                    packageName = extensionRef.packageName,
                    jarFileName = extensionRef.jarFileName,
                    extensionName = extensionRef.displayName,
                    mangaUrl = detail.url,
                    title = detail.title,
                    thumbnailUrl = detail.thumbnail_url,
                    author = detail.author,
                )
                isFavorite = true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = ::toggleFavorite) {
                        Icon(
                            if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (isFavorite) "Remove from library" else "Add to library",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                AsyncImage(
                    key = detail.thumbnail_url,
                    modifier = Modifier.width(100.dp).height(140.dp),
                ) {
                    detail.thumbnail_url?.let { fetchThumbnailBytes(source, it) }
                }
                Column(Modifier.padding(start = 8.dp)) {
                    detail.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    detail.description?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 6)
                    }
                }
            }

            if (error != null) {
                Text(
                    "Error: $error",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp),
                )
            }

            val savedProgress = progress
            if (savedProgress != null) {
                val chapterIndex = chapters.indexOfFirst { it.url == savedProgress.chapterUrl }
                if (chapterIndex >= 0) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(8.dp)
                            .clickable {
                                onChapterSelected(chapters, chapterIndex, savedProgress.pageIndex.toInt())
                            },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text("Continue reading", style = MaterialTheme.typography.labelMedium)
                                Text(savedProgress.chapterName, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(chapters, key = { it.url }) { chapter ->
                        val index = chapters.indexOf(chapter)
                        ListItem(
                            headlineContent = { Text(chapter.name) },
                            supportingContent = {
                                if (chapter.date_upload > 0) {
                                    Text(DateFormat.getDateInstance().format(Date(chapter.date_upload)))
                                }
                            },
                            modifier = Modifier.clickable { onChapterSelected(chapters, index, 0) },
                        )
                    }
                }
            }
        }
    }
}

private fun fetchThumbnailBytes(source: Source, url: String): ByteArray? {
    val httpSource = source as? HttpSource ?: return null
    return httpSource.client.newCall(GET(url, httpSource.headers)).execute().use { response ->
        if (response.isSuccessful) response.body.bytes() else null
    }
}
