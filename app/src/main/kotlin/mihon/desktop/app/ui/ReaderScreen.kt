package mihon.desktop.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.ViewHeadline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.desktop.app.ui.reader.PageSlot
import mihon.desktop.app.ui.reader.WebtoonView
import mihon.desktop.app.ui.reader.loadPageBytes
import mihon.desktop.loader.download.DownloadManager
import mihon.desktop.loader.library.LibraryRepository
import mihon.desktop.loader.log.Logger
import mihon.desktop.loader.prefs.AppPreferences
import mihon.desktop.loader.tracker.TrackerManager
import mihon.desktop.app.i18n.Strings
import mihon.desktop.app.i18n.t
import org.jetbrains.skia.Image as SkiaImage

/**
 * Reader with two modes:
 * - **Page mode** (default): one or two pages at a time with zoom/pan,
 *   next/prev buttons, keyboard navigation (←/→ or PgUp/PgDn), and zoom via
 *   scroll wheel or +/- keys.
 *   - **Dual page** (per-manga): shows consecutive page pairs side by side;
 *     in RTL reading the lower-numbered page is placed on the right.
 *     NOTE: Mihon's "wide image shown single" rule is NOT applied -- image
 *     aspect ratio isn't known before decode, so every pair is always shown
 *     as a pair.
 *   - **Page transitions** (per-manga): None (default), Slide (direction
 *     aware, flips for RTL), or Fade -- via [AnimatedContent].
 * - **Webtoon mode**: vertical scrolling strip of all pages in a chapter.
 *   No transitions, no dual page, no preload window (the LazyColumn loads
 *   lazily and shares the disk ImageCache).
 *
 * Other features:
 * - **Fullscreen** (F key or overlay button): hides window decorations
 *   (see Main.kt for the mechanism), hides the navigation rail and reader
 *   chrome. F or Esc restores.
 * - **Brightness filter** (global): black scrim over the reader content.
 *   Purely visual -- does NOT change the system/screen brightness.
 * - **Page preload** (global, 0-10): paged mode keeps decoded bitmaps of the
 *   pages within +/- N of the current spread (pair in dual-page mode) in
 *   memory (and warms the shared disk cache for network pages); everything
 *   outside the window is evicted so memory stays bounded.
 * - **Retry** (R key or button on the failed page): refetches a page that
 *   failed to load.
 *
 * Pages are loaded from disk if the chapter has been downloaded (offline
 * reading), otherwise fetched from the source's network API.
 */

private const val TAG = "ReaderScreen"

/** Valid page transition modes, in cycle order. Persisted per-manga. */
private val PAGE_TRANSITIONS = listOf("none", "slide", "fade")

@Composable
private fun transitionLabel(mode: String): String = when (mode) {
    "slide" -> t("transition_slide")
    "fade" -> t("transition_fade")
    else -> t("transition_none")
}

/**
 * What the paged reader currently shows: the list index of the left/first
 * page, an optional second page (dual-page mode), and a stable key used for
 * [AnimatedContent] targeting and zoom-state resets.
 */
private data class PageSpread(
    val firstIndex: Int,
    val secondIndex: Int?,
    val key: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    source: Source,
    manga: SManga,
    chapters: List<SChapter>,
    initialChapterIndex: Int,
    initialPageIndex: Int = 0,
    readingDirection: String = "ltr",
    fullscreen: Boolean = false,
    onFullscreenChanged: (Boolean) -> Unit = {},
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
    var dualPageMode by remember { mutableStateOf(false) }
    var pageTransition by remember { mutableStateOf("none") }
    var brightnessPanelOpen by remember { mutableStateOf(false) }
    // +1 = navigating forward, -1 = backward. Drives the slide direction.
    var navDirection by remember { mutableStateOf(1) }
    var incognitoMode by remember {
        mutableStateOf(AppPreferences.getBoolean(AppPreferences.KEY_INCOGNITO_MODE, false))
    }

    // Global reader settings, observed live so changes made from the reader
    // overlay and from the Settings screen both apply immediately.
    var brightnessEnabled by remember {
        mutableStateOf(AppPreferences.getBoolean(AppPreferences.KEY_READER_BRIGHTNESS_ENABLED, false))
    }
    var brightness by remember {
        mutableStateOf(
            AppPreferences.getInt(AppPreferences.KEY_READER_BRIGHTNESS, AppPreferences.DEFAULT_READER_BRIGHTNESS)
                .coerceIn(0, 100),
        )
    }
    var preloadCount by remember {
        mutableStateOf(
            AppPreferences.getInt(AppPreferences.KEY_READER_PRELOAD, AppPreferences.DEFAULT_READER_PRELOAD)
                .coerceIn(0, 10),
        )
    }

    // Incognito and the global reader settings can be toggled elsewhere while
    // this screen is open -- observe the preferences instead of reading once.
    DisposableEffect(Unit) {
        val listener: (String) -> Unit = { key ->
            when (key) {
                AppPreferences.KEY_INCOGNITO_MODE ->
                    incognitoMode = AppPreferences.getBoolean(AppPreferences.KEY_INCOGNITO_MODE, false)
                AppPreferences.KEY_READER_BRIGHTNESS_ENABLED ->
                    brightnessEnabled = AppPreferences.getBoolean(AppPreferences.KEY_READER_BRIGHTNESS_ENABLED, false)
                AppPreferences.KEY_READER_BRIGHTNESS ->
                    brightness = AppPreferences.getInt(
                        AppPreferences.KEY_READER_BRIGHTNESS,
                        AppPreferences.DEFAULT_READER_BRIGHTNESS,
                    ).coerceIn(0, 100)
                AppPreferences.KEY_READER_PRELOAD ->
                    preloadCount = AppPreferences.getInt(
                        AppPreferences.KEY_READER_PRELOAD,
                        AppPreferences.DEFAULT_READER_PRELOAD,
                    ).coerceIn(0, 10)
            }
        }
        AppPreferences.addListener(listener)
        onDispose { AppPreferences.removeListener(listener) }
    }

    // In-memory decoded pages of the current chapter, keyed by list index,
    // plus a mark for pages whose load failed. Recreated per chapter; entries
    // outside the preload window are evicted (see the preload LaunchedEffect).
    val pageBitmaps = remember(chapterIndex) { mutableStateMapOf<Int, ImageBitmap>() }
    val pageFailures = remember(chapterIndex) { mutableStateMapOf<Int, Boolean>() }

    val chapter = chapters.getOrNull(chapterIndex)

    // Load reader preferences on mount
    LaunchedEffect(manga) {
        webtoonMode = repository.getWebtoonMode(source.id, manga.url)
        dualPageMode = repository.getDualPageMode(source.id, manga.url)
        pageTransition = repository.getPageTransition(source.id, manga.url)
        prefsLoaded = true
    }

    // In incognito mode, don't persist per-manga preferences: writing them
    // would leave a trace of which manga was opened.
    fun saveReaderPrefs() {
        if (prefsLoaded && !incognitoMode) {
            scope.launch {
                repository.setReaderPrefs(source.id, manga.url, webtoonMode, dualPageMode, pageTransition)
            }
        }
    }

    fun toggleWebtoonMode() {
        webtoonMode = !webtoonMode
        saveReaderPrefs()
    }

    fun toggleDualPageMode() {
        // Dual page only makes sense in paged mode.
        if (webtoonMode) return
        dualPageMode = !dualPageMode
        if (dualPageMode) {
            // Snap to the start of the current pair so the indicator and
            // saved progress stay pair-aligned.
            pageIndex = (pageIndex.coerceIn(0, pages.lastIndex.coerceAtLeast(0)) / 2) * 2
        }
        saveReaderPrefs()
    }

    fun cyclePageTransition() {
        val next = (PAGE_TRANSITIONS.indexOf(pageTransition).coerceAtLeast(0) + 1) % PAGE_TRANSITIONS.size
        pageTransition = PAGE_TRANSITIONS[next]
        saveReaderPrefs()
    }

    fun loadChapter() {
        scope.launch {
            loading = true
            error = null
            isOffline = false
            val ch = chapter
            if (ch == null) {
                error = Strings.get("reader_no_chapter")
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

    /** Loads and decodes one page into the in-memory cache; marks failures. */
    suspend fun loadPage(index: Int) {
        val page = pages.getOrNull(index) ?: return
        if (pageBitmaps.containsKey(index)) return
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                loadPageBytes(downloadManager, httpSource, page, source.id, isOffline, cache = sharedImageCache)
            }.getOrNull()
        }
        val bitmap = bytes?.let { data ->
            withContext(Dispatchers.Default) {
                runCatching { SkiaImage.makeFromEncoded(data).toComposeImageBitmap() }.getOrNull()
            }
        }
        if (bitmap != null) {
            pageBitmaps[index] = bitmap
            pageFailures.remove(index)
        } else if (!pageBitmaps.containsKey(index)) {
            pageFailures[index] = true
        }
    }

    // Preload window: warm the pages within +/- preloadCount of the current
    // spread, evicting everything outside the window to keep memory bounded.
    // In dual-page mode the window is centered on the pair (not pageIndex
    // alone) so the visible spread mate is always inside the window -- at
    // preload 0 it would otherwise be evicted while still on screen.
    // Spread first, then outward by distance. Paged mode only.
    LaunchedEffect(chapterIndex, pageIndex, pages, preloadCount, webtoonMode, dualPageMode) {
        if (webtoonMode || pages.isEmpty()) return@LaunchedEffect
        val pairStart = if (dualPageMode) (pageIndex / 2) * 2 else pageIndex
        val spreadEnd = if (dualPageMode) minOf(pairStart + 1, pages.lastIndex) else pairStart
        val order = buildList {
            for (d in 0..preloadCount) {
                add(pairStart + d)
                if (d > 0) add(pairStart - d)
                if (spreadEnd > pairStart) {
                    add(spreadEnd + d)
                    if (d > 0) add(spreadEnd - d)
                }
            }
        }.filter { it in pages.indices }.distinct()
        for (i in order) {
            if (pageFailures[i] != true) loadPage(i)
        }
        // Let the outgoing page's exit transition (200ms slide / 150ms fade)
        // finish before evicting its bitmap, or AnimatedContent flashes a
        // spinner over the exiting content.
        delay(300)
        val keep = (maxOf(0, pairStart - preloadCount)..minOf(pages.lastIndex, spreadEnd + preloadCount)).toSet()
        pageBitmaps.keys.filterNot { it in keep }.forEach { pageBitmaps.remove(it) }
    }

    fun retryPage(index: Int) {
        pageFailures.remove(index)
        pageBitmaps.remove(index)
        scope.launch { loadPage(index) }
    }

    fun retryCurrent() {
        when {
            // Chapter-level failure -> reload the whole chapter.
            error != null -> loadChapter()
            // Page-level failure in paged mode -> refetch the current page.
            !webtoonMode && pageFailures[pageIndex] == true -> retryPage(pageIndex)
        }
    }

    val dualActive = dualPageMode && !webtoonMode && pages.isNotEmpty()

    fun markCurrentChapterRead() {
        val ch = chapter
        if (ch != null && !incognitoMode) {
            scope.launch {
                repository.markAsRead(source.id, manga.url, ch.url, ch.name)
                // Push the chapter number to every bound tracker. Fire-and-
                // forget: failures are logged inside, never block reading.
                // Same number derivation as MangaDetail's mark-as-read push.
                ch.chapterNumberOrFallback()?.let { num ->
                    runCatching {
                        TrackerManager.shared().pushChapterRead(source.id, manga.url, num)
                    }
                }
            }
        }
    }

    fun goToNextPage() {
        navDirection = 1
        if (dualActive && pageIndex + 1 < pages.lastIndex) {
            // Advance a whole pair; the odd tail page of a chapter is its own pair.
            pageIndex = minOf(pageIndex + 2, pages.lastIndex)
            return
        }
        when {
            dualActive -> {
                markCurrentChapterRead()
                if (chapterIndex < chapters.lastIndex) chapterIndex++
            }
            pageIndex < pages.lastIndex -> pageIndex++
            chapterIndex < chapters.lastIndex -> {
                markCurrentChapterRead()
                chapterIndex++
            }
            else -> markCurrentChapterRead() // last page of last chapter
        }
    }

    fun goToPreviousPage() {
        navDirection = -1
        when {
            dualActive && pageIndex > 0 -> pageIndex = maxOf(pageIndex - 2, 0)
            dualActive && chapterIndex > 0 -> chapterIndex--
            pageIndex > 0 -> pageIndex--
            chapterIndex > 0 -> chapterIndex--
        }
    }

    // What the paged reader shows right now (single page or dual-page pair).
    val display: PageSpread? = if (!webtoonMode && !loading && error == null && pages.isNotEmpty()) {
        val safeIndex = pageIndex.coerceIn(pages.indices)
        if (dualPageMode) {
            val first = (safeIndex / 2) * 2
            val second = first + 1
            PageSpread(
                firstIndex = first,
                secondIndex = second.takeIf { it <= pages.lastIndex },
                key = "$chapterIndex:$first:${second.takeIf { it <= pages.lastIndex } ?: -1}",
            )
        } else {
            PageSpread(firstIndex = safeIndex, secondIndex = null, key = "$chapterIndex:$safeIndex")
        }
    } else {
        null
    }

    val isRtl = readingDirection == "rtl"

    Scaffold(
        topBar = {
            // Reader chrome auto-hides in fullscreen; F/Esc or the floating
            // exit button restores the window.
            if (showControls && !fullscreen) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(chapter?.name ?: manga.title)
                            if (isOffline) {
                                Text(
                                    " " + t("reader_offline_badge"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("common_back")) }
                    },
                    actions = {
                        IconButton(onClick = ::toggleWebtoonMode) {
                            Icon(
                                Icons.Filled.ViewHeadline,
                                contentDescription = if (webtoonMode) t("reader_mode_paged") else t("reader_mode_webtoon"),
                            )
                        }
                        IconButton(onClick = ::toggleDualPageMode, enabled = !webtoonMode) {
                            Icon(
                                Icons.Filled.ViewColumn,
                                contentDescription = if (dualPageMode) t("reader_single_page") else t("reader_dual_page"),
                                tint = if (dualPageMode) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                        IconButton(onClick = ::cyclePageTransition, enabled = !webtoonMode) {
                            Icon(
                                Icons.Filled.Animation,
                                contentDescription = t("reader_transition", transitionLabel(pageTransition)),
                                tint = if (pageTransition != "none") MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                        IconButton(onClick = { brightnessPanelOpen = !brightnessPanelOpen }) {
                            Icon(
                                Icons.Filled.Brightness6,
                                contentDescription = t("reader_brightness"),
                                tint = if (brightnessEnabled && brightness < 100) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                        IconButton(onClick = { onFullscreenChanged(!fullscreen) }) {
                            Icon(
                                if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                contentDescription = if (fullscreen) t("action_exit_fullscreen") else t("action_fullscreen"),
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
                        when (event.key) {
                            Key.DirectionRight, Key.PageDown -> {
                                if (isRtl) goToPreviousPage() else goToNextPage()
                                true
                            }
                            Key.DirectionLeft, Key.PageUp -> {
                                if (isRtl) goToNextPage() else goToPreviousPage()
                                true
                            }
                            Key.F -> {
                                onFullscreenChanged(!fullscreen)
                                true
                            }
                            Key.R -> {
                                retryCurrent()
                                true
                            }
                            Key.Escape -> {
                                // Esc closes the brightness panel first,
                                // then leaves fullscreen, then the reader.
                                when {
                                    brightnessPanelOpen -> brightnessPanelOpen = false
                                    fullscreen -> onFullscreenChanged(false)
                                    else -> onBack()
                                }
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
                        Text(t("common_error_prefix", error), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = ::loadChapter) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Text(t("common_retry"))
                        }
                    }
                    pages.isEmpty() -> Text(t("reader_no_pages"), color = Color.White)
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
                        val spread = display
                        if (spread != null) {
                            AnimatedContent(
                                targetState = spread,
                                transitionSpec = {
                                    when (pageTransition) {
                                        "slide" -> {
                                            // Forward LTR: the new page enters from the right.
                                            // Flip for backward nav and for RTL reading.
                                            val dir = if ((navDirection >= 0) != isRtl) 1 else -1
                                            (slideInHorizontally(tween(200)) { it * dir }) togetherWith
                                                (slideOutHorizontally(tween(200)) { -it * dir })
                                        }
                                        "fade" -> fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                                        else -> EnterTransition.None togetherWith ExitTransition.None
                                    }
                                },
                                label = "pageTransition",
                            ) { target ->
                                val second = target.secondIndex
                                if (second != null) {
                                    Row(Modifier.fillMaxSize()) {
                                        // RTL pairs read right-to-left: the lower-numbered
                                        // page sits on the right.
                                        val (leftIndex, rightIndex) =
                                            if (isRtl) second to target.firstIndex else target.firstIndex to second
                                        PageSlot(
                                            index = leftIndex,
                                            bitmap = pageBitmaps[leftIndex],
                                            failed = pageFailures[leftIndex] == true,
                                            zoomKey = "${target.key}:$leftIndex",
                                            modifier = Modifier.weight(1f).fillMaxHeight(),
                                            onRetry = { retryPage(leftIndex) },
                                        )
                                        PageSlot(
                                            index = rightIndex,
                                            bitmap = pageBitmaps[rightIndex],
                                            failed = pageFailures[rightIndex] == true,
                                            zoomKey = "${target.key}:$rightIndex",
                                            modifier = Modifier.weight(1f).fillMaxHeight(),
                                            onRetry = { retryPage(rightIndex) },
                                        )
                                    }
                                } else {
                                    PageSlot(
                                        index = target.firstIndex,
                                        bitmap = pageBitmaps[target.firstIndex],
                                        failed = pageFailures[target.firstIndex] == true,
                                        zoomKey = target.key,
                                        modifier = Modifier.fillMaxSize(),
                                        onRetry = { retryPage(target.firstIndex) },
                                    )
                                }
                            }
                        }
                    }
                }

                // Brightness scrim: a pure black overlay over the reader
                // content only (controls stay outside this Box's content).
                // Visual dimming only -- system brightness is untouched.
                if (brightnessEnabled && brightness < 100) {
                    Box(
                        Modifier.fillMaxSize().background(
                            Color.Black.copy(alpha = (100 - brightness) / 100f),
                        ),
                    )
                }

                if (fullscreen) {
                    // Always-visible escape hatch for mouse users; F/Esc also exit.
                    IconButton(
                        onClick = { onFullscreenChanged(false) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) { Icon(Icons.Filled.FullscreenExit, contentDescription = t("action_exit_fullscreen")) }
                }

                if (brightnessPanelOpen) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        shape = RoundedCornerShape(8.dp),
                        tonalElevation = 4.dp,
                    ) {
                        Column(Modifier.padding(12.dp).width(280.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Brightness6, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(t("reader_brightness"))
                                Spacer(Modifier.weight(1f))
                                Switch(
                                    checked = brightnessEnabled,
                                    onCheckedChange = { enabled ->
                                        brightnessEnabled = enabled
                                        AppPreferences.setBoolean(AppPreferences.KEY_READER_BRIGHTNESS_ENABLED, enabled)
                                    },
                                )
                            }
                            Slider(
                                value = brightness.toFloat(),
                                onValueChange = { value ->
                                    brightness = value.toInt().coerceIn(0, 100)
                                },
                                // Persist once per drag, not per tick -- the
                                // in-memory state drives the scrim live.
                                onValueChangeFinished = {
                                    AppPreferences.setInt(AppPreferences.KEY_READER_BRIGHTNESS, brightness)
                                },
                                valueRange = 0f..100f,
                                enabled = brightnessEnabled,
                            )
                            Text(
                                "$brightness%",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.align(Alignment.End),
                            )
                        }
                    }
                }
            }

            if (!webtoonMode && showControls && !fullscreen) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    // Page slider + indicator. Dual-page pairs count as one
                    // step visually ("3-4 / 20"); the slider stays page-granular.
                    if (pages.isNotEmpty()) {
                        val indicator = display?.let { spread ->
                            val second = spread.secondIndex
                            if (second != null) {
                                "${spread.firstIndex + 1}-${second + 1} / ${pages.size}"
                            } else {
                                "${spread.firstIndex + 1} / ${pages.size}"
                            }
                        } ?: "${pageIndex + 1} / ${pages.size}"
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                indicator,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Slider(
                                value = pageIndex.toFloat(),
                                onValueChange = { newValue ->
                                    val target = newValue.toInt()
                                    // Direction-aware slide transition for
                                    // slider-driven jumps too.
                                    navDirection = if (target >= pageIndex) 1 else -1
                                    pageIndex = target
                                },
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
                        val prevLabel = if (isRtl) t("common_next") else t("common_previous")
                        val nextLabel = if (isRtl) t("common_previous") else t("common_next")
                        Button(
                            onClick = { if (isRtl) goToNextPage() else goToPreviousPage() },
                            enabled = chapterIndex > 0 || pageIndex > 0,
                        ) {
                            Text(prevLabel)
                        }
                        Text(
                            t("reader_ch_indicator", chapterIndex + 1, chapters.size),
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
