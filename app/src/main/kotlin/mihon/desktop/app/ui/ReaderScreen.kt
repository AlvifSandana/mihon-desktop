package mihon.desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import mihon.desktop.loader.library.LibraryRepository

/**
 * A minimal single-page reader: fetches the current chapter's pages, shows one page at a
 * time with next/prev navigation across pages and (at the ends) across chapters. No
 * zoom/pan/webtoon mode yet -- see docs/ROADMAP.md, this is a first cut.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    source: Source,
    manga: SManga,
    chapters: List<SChapter>,
    initialChapterIndex: Int,
    initialPageIndex: Int = 0,
    onBack: () -> Unit,
) {
    val httpSource = source as? HttpSource
    val repository = remember { LibraryRepository() }

    var chapterIndex by remember { mutableStateOf(initialChapterIndex) }
    var pages by remember(chapterIndex) { mutableStateOf(listOf<Page>()) }
    var pageIndex by remember(chapterIndex) {
        mutableStateOf(if (chapterIndex == initialChapterIndex) initialPageIndex else 0)
    }
    var loading by remember(chapterIndex) { mutableStateOf(true) }
    var error by remember(chapterIndex) { mutableStateOf<String?>(null) }

    val chapter = chapters.getOrNull(chapterIndex)

    LaunchedEffect(chapterIndex) {
        loading = true
        error = null
        val ch = chapter
        if (ch == null) {
            error = "No such chapter"
        } else {
            runCatching { source.getPageList(ch) }
                .onSuccess { pages = it }
                .onFailure { error = it.message ?: it.toString() }
        }
        loading = false
    }

    LaunchedEffect(chapterIndex, pageIndex) {
        val ch = chapter ?: return@LaunchedEffect
        repository.saveProgress(
            sourceId = source.id,
            mangaUrl = manga.url,
            chapterUrl = ch.url,
            chapterName = ch.name,
            pageIndex = pageIndex,
        )
    }

    fun goToNextPage() {
        when {
            pageIndex < pages.lastIndex -> pageIndex++
            chapterIndex < chapters.lastIndex -> chapterIndex++
        }
    }

    fun goToPreviousPage() {
        when {
            pageIndex > 0 -> pageIndex--
            chapterIndex > 0 -> chapterIndex--
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(chapter?.name ?: manga.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(Color.Black)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    loading -> CircularProgressIndicator()
                    error != null -> Text("Error: $error", color = MaterialTheme.colorScheme.error)
                    pages.isEmpty() -> Text("No pages", color = Color.White)
                    else -> {
                        val page = pages[pageIndex.coerceIn(pages.indices)]
                        AsyncImage(key = page.url + page.index, modifier = Modifier.fillMaxSize()) {
                            fetchPageBytes(httpSource, page)
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Button(onClick = { goToPreviousPage() }, enabled = chapterIndex > 0 || pageIndex > 0) {
                    Text("Previous")
                }
                Text(
                    "Page ${pageIndex + 1}/${pages.size.coerceAtLeast(1)} · Ch. ${chapterIndex + 1}/${chapters.size}",
                    color = Color.White,
                )
                Button(
                    onClick = { goToNextPage() },
                    enabled = chapterIndex < chapters.lastIndex || pageIndex < pages.lastIndex,
                ) {
                    Text("Next")
                }
            }
        }
    }
}

private suspend fun fetchPageBytes(httpSource: HttpSource?, page: Page): ByteArray? {
    if (httpSource == null) return null
    if (page.imageUrl == null) {
        page.imageUrl = runCatching { httpSource.getImageUrl(page) }.getOrNull()
    }
    return runCatching { httpSource.getImage(page).use { it.body.bytes() } }.getOrNull()
}
