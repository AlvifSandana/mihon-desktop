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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import java.text.DateFormat
import java.util.Date

/** Fetches full details + chapter list for one manga and lets the user pick a chapter to read. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaDetailScreen(
    source: Source,
    manga: SManga,
    onChapterSelected: (List<SChapter>, Int) -> Unit,
    onBack: () -> Unit,
) {
    var detail by remember(manga) { mutableStateOf(manga) }
    var chapters by remember(manga) { mutableStateOf(listOf<SChapter>()) }
    var loading by remember(manga) { mutableStateOf(true) }
    var error by remember(manga) { mutableStateOf<String?>(null) }

    LaunchedEffect(manga) {
        loading = true
        error = null
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
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
                            modifier = Modifier.clickable { onChapterSelected(chapters, index) },
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
