package mihon.desktop.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewHeadline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.desktop.loader.download.DownloadManager
import mihon.desktop.loader.library.LibraryRepository
import mihon.desktop.loader.log.Logger
import mihon.desktop.loader.prefs.AppPreferences
import org.jetbrains.skia.Image as SkiaImage

/**
 * Reader with two modes:
 * - **Page mode** (default): one page at a time with zoom/pan, next/prev buttons,
 *   keyboard navigation (←/→ or PgUp/PgDn), and zoom via scroll wheel or +/- keys.
 * - **Webtoon mode**: vertical scrolling strip of all pages in a chapter, like a
 *   webtoon / vertical scroll manga.
 *
 * Pages are loaded from disk if the chapter has been downloaded (offline reading),
 * otherwise fetched from the source's network API.
 */

private const val TAG = "ReaderScreen"
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    source: Source,
    manga: SManga,
    chapters: List<SChapter>,
    initialChapterIndex: Int,
    initialPageIndex: Int = 0,
    readingDirection: String = "ltr",
    onBack: () -> Unit,
) {
    val httpSource = source as? HttpSource
    val repository = remember { LibraryRepository() }
    val downloadManager = remember { DownloadManager() }
    val scope = rememberCoroutineScope()

    var chapterIndex by remember { mutableStateOf(initialChapterIndex) }
    var pages by remember(chapterIndex) { mutableStateOf(listOf<Page>()) }
    var pageIndex by remember(chapterIndex) {
        mutableStateOf(if (chapterIndex == initialChapterIndex) initialPageIndex else 0)
    }
    var loading by remember(chapterIndex) { mutableStateOf(true) }
    var error by remember(chapterIndex) { mutableStateOf<String?>(null) }
    var webtoonMode by remember { mutableStateOf(false) }
    var isOffline by remember(chapterIndex) { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var prefsLoaded by remember { mutableStateOf(false) }
    val incognitoMode = remember {
        AppPreferences.getBoolean(AppPreferences.KEY_INCOGNITO_MODE, false)
    }

    val chapter = chapters.getOrNull(chapterIndex)

    // Load reader preferences on mount
    LaunchedEffect(manga) {
        webtoonMode = repository.getWebtoonMode(source.id, manga.url)
        prefsLoaded = true
    }

    fun toggleWebtoonMode() {
        webtoonMode = !webtoonMode
        // In incognito mode, don't persist the per-manga preference: writing it
        // would leave a trace of which manga was opened.
        if (prefsLoaded && !incognitoMode) {
            scope.launch {
                repository.setWebtoonMode(source.id, manga.url, webtoonMode)
            }
        }
    }

    fun loadChapter() {
        scope.launch {
            loading = true
            error = null
            isOffline = false
            val ch = chapter
            if (ch == null) {
                error = "No such chapter"
            } else {
                val downloaded = downloadManager.isChapterDownloaded(source.id, ch.url)
                if (downloaded) {
                    isOffline = true
                    val count = downloadManager.downloadedCount(source.id, manga.url)
                    val offlinePages = (0 until count.coerceAtLeast(1)).map { i ->
                        Page(i, url = "$i", imageUrl = "$i")
                    }
                    pages = offlinePages
                } else {
                    runCatching {
                        // getPageList does network I/O -- keep it off the UI thread.
                        withContext(Dispatchers.IO) { source.getPageList(ch) }
                    }
                        .onSuccess { pages = it }
                        .onFailure {
                            Logger.e(TAG, "Failed to load pages: ${it.message}", it)
                            error = it.message ?: it.toString()
                        }
                }
            }
            loading = false
        }
    }

    LaunchedEffect(chapterIndex) {
        loadChapter()
    }

    LaunchedEffect(chapterIndex, pageIndex) {
        // Skip while the chapter is still loading: saving pageIndex 0 on open
        // would clobber previously saved progress. Also skip in incognito mode.
        if (incognitoMode || loading) return@LaunchedEffect
        val ch = chapter ?: return@LaunchedEffect
        repository.saveProgress(
            sourceId = source.id,
            mangaUrl = manga.url,
            chapterUrl = ch.url,
            chapterName = ch.name,
            pageIndex = pageIndex,
        )
    }

    // Preload next 2 pages in background
    LaunchedEffect(chapterIndex, pageIndex, pages) {
        if (pages.isEmpty() || isOffline) return@LaunchedEffect
        val preloadRange = (pageIndex + 1)..(pageIndex + 2).coerceAtMost(pages.lastIndex)
        for (i in preloadRange) {
            val page = pages[i]
            val pageKey = page.url + page.index
            // Trigger preload by accessing the image URL
            withContext(Dispatchers.IO) {
                runCatching {
                    if (page.imageUrl == null) {
                        page.imageUrl = httpSource?.getImageUrl(page)
                    }
                    httpSource?.getImage(page)?.close()
                }
            }
        }
    }

    fun goToNextPage() {
        when {
            pageIndex < pages.lastIndex -> pageIndex++
            chapterIndex < chapters.lastIndex -> {
                // Mark current chapter as read before advancing
                val ch = chapter
                if (ch != null && !incognitoMode) {
                    scope.launch {
                        repository.markAsRead(source.id, manga.url, ch.url, ch.name)
                    }
                }
                chapterIndex++
            }
            pageIndex == pages.lastIndex -> {
                // Last page of last chapter — mark as read
                val ch = chapter
                if (ch != null && !incognitoMode) {
                    scope.launch {
                        repository.markAsRead(source.id, manga.url, ch.url, ch.name)
                    }
                }
            }
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
            if (showControls) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(chapter?.name ?: manga.title)
                            if (isOffline) {
                                Text(
                                    " (offline)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    },
                actions = {
                    IconButton(onClick = ::toggleWebtoonMode) {
                        Icon(
                            Icons.Filled.ViewHeadline,
                            contentDescription = if (webtoonMode) "Page mode" else "Webtoon mode",
                        )
                    }
                },
                )
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).background(Color.Black)
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        val isRtl = readingDirection == "rtl"
                        when (event.key) {
                            Key.DirectionRight, Key.PageDown -> {
                                if (isRtl) goToPreviousPage() else goToNextPage()
                                true
                            }
                            Key.DirectionLeft, Key.PageUp -> {
                                if (isRtl) goToNextPage() else goToPreviousPage()
                                true
                            }
                            Key.Escape -> {
                                onBack()
                                true
                            }
                            else -> false
                        }
                    } else false
                }
                .focusable(),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    loading -> CircularProgressIndicator()
                    error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Error: $error", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = ::loadChapter) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Text("Retry")
                        }
                    }
                    pages.isEmpty() -> Text("No pages", color = Color.White)
                    webtoonMode -> {
                        WebtoonView(
                            pages = pages,
                            httpSource = httpSource,
                            downloadManager = downloadManager,
                            sourceId = source.id,
                            isOffline = isOffline,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    else -> {
                        val page = pages[pageIndex.coerceIn(pages.indices)]
                        val pageKey = page.url + page.index
                        AsyncImageZoomable(
                            key = pageKey,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            loadPageBytes(downloadManager, httpSource, page, source.id, isOffline)
                        }
                    }
                }
            }

            if (!webtoonMode && showControls) {
                val isRtl = readingDirection == "rtl"
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    // Page slider
                    if (pages.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${pageIndex + 1}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Slider(
                                value = pageIndex.toFloat(),
                                onValueChange = { newValue -> pageIndex = newValue.toInt() },
                                valueRange = 0f..pages.lastIndex.toFloat().coerceAtLeast(0f),
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            )
                            Text(
                                "${pages.size}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    // Navigation buttons
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val prevLabel = if (isRtl) "Next" else "Previous"
                        val nextLabel = if (isRtl) "Previous" else "Next"
                        Button(
                            onClick = { if (isRtl) goToNextPage() else goToPreviousPage() },
                            enabled = chapterIndex > 0 || pageIndex > 0,
                        ) {
                            Text(prevLabel)
                        }
                        Text(
                            "Ch. ${chapterIndex + 1}/${chapters.size}",
                            color = Color.White,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                        Button(
                            onClick = { if (isRtl) goToPreviousPage() else goToNextPage() },
                            enabled = chapterIndex < chapters.lastIndex || pageIndex < pages.lastIndex,
                        ) {
                            Text(nextLabel)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Webtoon / vertical-scroll mode: all pages stacked vertically in a [LazyColumn],
 * each rendered at full width. No zoom — the user scrolls naturally.
 */
@Composable
private fun WebtoonView(
    pages: List<Page>,
    httpSource: HttpSource?,
    downloadManager: DownloadManager,
    sourceId: Long,
    isOffline: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = modifier) {
        itemsIndexed(pages) { index, page ->
            AsyncImage(
                key = page.url + page.index,
                modifier = Modifier.fillMaxWidth(),
            ) {
                loadPageBytes(downloadManager, httpSource, page, sourceId, isOffline)
            }
        }
    }
}

/**
 * Wraps [AsyncImage] with [ZoomableImage] — loads bytes off-thread, decodes to
 * [ImageBitmap], and shows a zoomable/pannable version.
 */
@Composable
private fun AsyncImageZoomable(
    key: Any?,
    modifier: Modifier = Modifier,
    load: suspend () -> ByteArray?,
) {
    var bitmap by remember(key) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var failed by remember(key) { mutableStateOf(false) }

    LaunchedEffect(key) {
        bitmap = null
        failed = false
        val bytes = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() }
        bitmap = bytes?.let { data ->
            withContext(Dispatchers.Default) {
                runCatching { SkiaImage.makeFromEncoded(data).toComposeImageBitmap() }.getOrNull()
            }
        }
        failed = bitmap == null
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            ZoomableImage(
                modifier = Modifier.fillMaxSize(),
                key = key,
            ) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else if (!failed) {
            CircularProgressIndicator()
        }
    }
}

/**
 * Load page bytes: from disk if offline, otherwise from the source's network API.
 */
private suspend fun loadPageBytes(
    downloadManager: DownloadManager,
    httpSource: HttpSource?,
    page: Page,
    sourceId: Long,
    isOffline: Boolean,
): ByteArray? {
    if (isOffline) {
        val pageIndex = page.url.toIntOrNull() ?: page.index
        return downloadManager.readPage(sourceId, page.url, pageIndex)
    }
    return fetchPageBytes(httpSource, page)
}

private suspend fun fetchPageBytes(httpSource: HttpSource?, page: Page): ByteArray? {
    if (httpSource == null) return null
    if (page.imageUrl == null) {
        page.imageUrl = runCatching { httpSource.getImageUrl(page) }.getOrNull()
    }
    return runCatching { httpSource.getImage(page).use { it.body.bytes() } }.getOrNull()
}
