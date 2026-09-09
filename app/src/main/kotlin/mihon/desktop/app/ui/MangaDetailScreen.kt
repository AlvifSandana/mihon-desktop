package mihon.desktop.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.desktop.loader.download.DownloadJobState
import mihon.desktop.loader.download.DownloadManager
import mihon.desktop.loader.download.DownloadQueue
import mihon.desktop.loader.library.Category
import mihon.desktop.loader.library.CategoryRepository
import mihon.desktop.loader.library.LibraryRepository
import mihon.desktop.loader.library.ReadingProgress
import mihon.desktop.loader.log.Logger
import mihon.desktop.loader.tracker.TrackEntry
import mihon.desktop.loader.tracker.TrackStatus
import mihon.desktop.loader.tracker.TrackUpdate
import mihon.desktop.loader.tracker.Tracker
import mihon.desktop.loader.tracker.TrackerManager
import mihon.desktop.loader.tracker.TrackerRepository
import mihon.desktop.app.i18n.Strings
import mihon.desktop.app.i18n.t
import java.text.DateFormat
import java.util.Date

private enum class ChapterSortMode(val labelKey: String) {
    SOURCE_ORDER("chapter_sort_source"),
    NAME_ASC("chapter_sort_name_az"),
    NAME_DESC("chapter_sort_name_za"),
    DATE_NEWEST("chapter_sort_newest"),
    DATE_OLDEST("chapter_sort_oldest"),
}

private const val TAG = "MangaDetailScreen"

/** Fetches full details + chapter list for one manga and lets the user pick a chapter to read. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaDetailScreen(
    extensionRef: ExtensionRef,
    source: Source,
    manga: SManga,
    onChapterSelected: (chapters: List<SChapter>, chapterIndex: Int, initialPageIndex: Int) -> Unit,
    onOpenSourceSettings: () -> Unit = {},
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val repository = remember { LibraryRepository() }
    val categoryRepository = remember { CategoryRepository() }
    val downloadManager = remember { DownloadManager() }
    // Chapter downloads go through the shared queue (parallel workers, retry,
    // pause/cancel in the Downloads screen); this screen only observes state.
    val downloadQueue = remember { DownloadQueue.shared }
    val queueJobs by downloadQueue.jobs.collectAsState()

    var detail by remember(manga) { mutableStateOf(manga) }
    var chapters by remember(manga) { mutableStateOf(listOf<SChapter>()) }
    var loading by remember(manga) { mutableStateOf(true) }
    var error by remember(manga) { mutableStateOf<String?>(null) }
    var isFavorite by remember(manga) { mutableStateOf(false) }
    var progress by remember(manga) { mutableStateOf<ReadingProgress?>(null) }
    var sortMode by remember(manga) { mutableStateOf(ChapterSortMode.SOURCE_ORDER) }
    var showSortMenu by remember(manga) { mutableStateOf(false) }
    var showOverflowMenu by remember(manga) { mutableStateOf(false) }
    var selectionMode by remember(manga) { mutableStateOf(false) }
    var filterUnreadOnly by remember(manga) { mutableStateOf(false) }
    var filterDownloadedOnly by remember(manga) { mutableStateOf(false) }
    val selectedChapters = remember { mutableStateMapOf<String, Boolean>() }

    // Chapter number filter
    var filterMinChapter by remember(manga) { mutableStateOf("") }
    var filterMaxChapter by remember(manga) { mutableStateOf("") }

    // Go-to-chapter dialog
    var showGoToDialog by remember(manga) { mutableStateOf(false) }

    // Edit-categories dialog (library manga only)
    var showEditCategories by remember(manga) { mutableStateOf(false) }

    // ── Tracking ──────────────────────────────────────────────────────────
    val trackerManager = remember { TrackerManager.shared() }
    val trackerRepository = remember { TrackerRepository() }
    var trackEntries by remember(manga) { mutableStateOf<List<TrackEntry>>(emptyList()) }
    var trackerLoginStates by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var trackSearchFor by remember { mutableStateOf<Tracker?>(null) }

    // Track download state per chapter URL
    val downloadedMap = remember { mutableStateMapOf<String, Boolean>() }
    // Track read state per chapter URL
    val readMap = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(manga) {
        loading = true
        error = null
        isFavorite = repository.isFavorite(source.id, manga.url)
        progress = repository.progressFor(source.id, manga.url)
        runCatching {
            // getMangaUpdate does network I/O -- keep it off the UI thread.
            withContext(Dispatchers.IO) {
                source.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)
            }
        }.onSuccess { update ->
            detail = update.manga
            chapters = update.chapters
            // Check download and read status for each chapter
            for (ch in chapters) {
                downloadedMap[ch.url] = downloadManager.isChapterDownloaded(source.id, ch.url)
                readMap[ch.url] = repository.isChapterRead(source.id, ch.url)
            }
            // The user may have favorited this manga while chapters were still
            // loading (seed then no-opped on an empty list) -- re-seed now so
            // the backlog isn't recorded as updates on the next refresh.
            // Idempotent: known chapters are skipped (INSERT OR IGNORE).
            if (isFavorite) {
                repository.recordNewChapters(
                    sourceId = source.id,
                    mangaUrl = detail.url,
                    mangaTitle = detail.title,
                    thumbnailUrl = detail.thumbnail_url,
                    packageName = extensionRef.packageName,
                    jarFileName = extensionRef.jarFileName,
                    chapters = chapters,
                    baseline = true,
                )
            }
        }.onFailure {
            Logger.e(TAG, "Failed to load manga details: ${it.message}", it)
            error = it.message ?: it.toString()
        }
        loading = false
    }

    val sortedChapters = remember(
        chapters,
        sortMode,
        filterUnreadOnly,
        filterDownloadedOnly,
        filterMinChapter,
        filterMaxChapter,
        readMap,
        downloadedMap,
    ) {
        chapters
            .let { list ->
                if (filterUnreadOnly) list.filter { readMap[it.url] != true } else list
            }
            .let { list ->
                if (filterDownloadedOnly) list.filter { downloadedMap[it.url] == true } else list
            }
            .let { list ->
                val minCh = filterMinChapter.toDoubleOrNull()
                val maxCh = filterMaxChapter.toDoubleOrNull()
                if (minCh != null || maxCh != null) {
                    list.filter { ch ->
                        val num = ch.parseChapterNameNumber()
                        num != null && (minCh == null || num >= minCh) && (maxCh == null || num <= maxCh)
                    }
                } else {
                    list
                }
            }
            .let { list ->
                when (sortMode) {
                    ChapterSortMode.SOURCE_ORDER -> list
                    ChapterSortMode.NAME_ASC -> list.sortedBy { it.name.lowercase() }
                    ChapterSortMode.NAME_DESC -> list.sortedByDescending { it.name.lowercase() }
                    ChapterSortMode.DATE_NEWEST -> list.sortedByDescending { it.date_upload }
                    ChapterSortMode.DATE_OLDEST -> list.sortedBy { it.date_upload }
                }
            }
    }

    // Queue completions flip this manga's chapters to "Downloaded" live.
    LaunchedEffect(queueJobs) {
        queueJobs.forEach { job ->
            if (job.sourceId == source.id && job.mangaUrl == manga.url && job.state == DownloadJobState.COMPLETED) {
                downloadedMap[job.chapterUrl] = true
            }
        }
    }

    // Tracker bindings (library manga only): load local rows, then silently
    // refresh each from the remote so the card shows the tracker's view.
    LaunchedEffect(manga, isFavorite) {
        trackerLoginStates = trackerManager.trackers.associate { it.name to it.isLoggedIn() }
        if (!isFavorite) {
            trackEntries = emptyList()
        } else {
            val local = trackerRepository.forManga(source.id, manga.url)
            val refreshed = local.map { entry ->
                val tracker = trackerManager.byName(entry.trackerName)
                if (tracker?.isLoggedIn() == true) {
                    runCatching { tracker.refresh(entry) }.getOrNull() ?: entry
                } else {
                    entry
                }
            }
            trackEntries = refreshed
            // Persist rows whose remote state moved (status/score/chapter).
            refreshed.zip(local).forEach { (fresh, old) ->
                if (fresh.status != old.status || fresh.score != old.score ||
                    fresh.lastChapterRead != old.lastChapterRead
                ) {
                    runCatching { trackerRepository.upsert(fresh) }
                }
            }
        }
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
                // Seed the chapters that already exist at add-time as known so
                // the first refresh doesn't record the whole backlog as updates.
                repository.recordNewChapters(
                    sourceId = source.id,
                    mangaUrl = detail.url,
                    mangaTitle = detail.title,
                    thumbnailUrl = detail.thumbnail_url,
                    packageName = extensionRef.packageName,
                    jarFileName = extensionRef.jarFileName,
                    chapters = chapters,
                    baseline = true,
                )
            }
        }
    }

    /**
     * Enqueues one chapter into the shared download queue. The row's queued/
     * downloading state comes from [queueJobs]; completion updates
     * [downloadedMap] below, and the queue posts one summary notification per
     * drained batch (no per-chapter notification from this screen).
     */
    fun enqueueDownload(chapter: SChapter) {
        scope.launch {
            downloadQueue.enqueue(
                source = source,
                mangaUrl = manga.url,
                chapter = chapter,
                mangaTitle = detail.title,
                thumbnailUrl = detail.thumbnail_url,
            )
        }
    }

    /** Enqueues every selected chapter that isn't downloaded yet. */
    fun downloadSelected() {
        val targets = sortedChapters.filter { selectedChapters[it.url] == true && downloadedMap[it.url] != true }
        if (targets.isNotEmpty()) {
            scope.launch {
                downloadQueue.enqueueAll(
                    source = source,
                    mangaUrl = manga.url,
                    chapters = targets,
                    mangaTitle = detail.title,
                    thumbnailUrl = detail.thumbnail_url,
                )
            }
        }
        selectionMode = false
        selectedChapters.clear()
    }

    fun deleteChapter(chapter: SChapter) {
        scope.launch {
            downloadManager.deleteChapter(source.id, chapter.url)
            downloadedMap[chapter.url] = false
        }
    }

    fun toggleRead(chapter: SChapter) {
        scope.launch {
            val isRead = readMap[chapter.url] == true
            if (isRead) {
                repository.markAsUnread(source.id, chapter.url)
                readMap[chapter.url] = false
            } else {
                repository.markAsRead(source.id, manga.url, chapter.url, chapter.name)
                readMap[chapter.url] = true
                // Push progress to bound trackers (fire-and-forget; skips
                // itself in incognito mode and on regressions).
                chapter.chapterNumberOrFallback()?.let { num ->
                    runCatching { trackerManager.pushChapterRead(source.id, manga.url, num) }
                }
            }
        }
    }

    fun markAllAsRead() {
        scope.launch {
            repository.markAllAsRead(source.id, manga.url, chapters)
            for (ch in chapters) readMap[ch.url] = true
            // One push with the highest chapter number covers all trackers.
            chapters.mapNotNull { it.chapterNumberOrFallback() }.maxOrNull()?.let { num ->
                runCatching { trackerManager.pushChapterRead(source.id, manga.url, num) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selectionMode) {
                        val count = selectedChapters.count { it.value }
                        Text(t("common_selected_count", count))
                    } else {
                        Text(detail.title)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectionMode) {
                            selectionMode = false
                            selectedChapters.clear()
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("common_back"))
                    }
                },
                actions = {
                    if (selectionMode) {
                        IconButton(onClick = {
                            // Select/deselect all
                            val allSelected = sortedChapters.all { selectedChapters[it.url] == true }
                            for (ch in sortedChapters) {
                                selectedChapters[ch.url] = !allSelected
                            }
                        }) {
                            Icon(Icons.Filled.SelectAll, contentDescription = t("action_select_all"))
                        }
                        IconButton(onClick = { downloadSelected() }) {
                            Icon(Icons.Filled.CloudDownload, contentDescription = t("action_download_selected"))
                        }
                    } else {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = t("action_sort"))
                        }
                        IconButton(onClick = { selectionMode = true }) {
                            Icon(Icons.Filled.Check, contentDescription = t("action_select_chapters"))
                        }
                        IconButton(onClick = ::toggleFavorite) {
                            Icon(
                                if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = if (isFavorite) t("action_remove_from_library") else t("action_add_to_library"),
                            )
                        }
                        if (source is ConfigurableSource || isFavorite) {
                            Box {
                                IconButton(onClick = { showOverflowMenu = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = t("action_more_options"))
                                }
                                DropdownMenu(
                                    expanded = showOverflowMenu,
                                    onDismissRequest = { showOverflowMenu = false },
                                ) {
                                    if (isFavorite) {
                                        DropdownMenuItem(
                                            text = { Text(t("detail_edit_categories")) },
                                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
                                            onClick = {
                                                showOverflowMenu = false
                                                showEditCategories = true
                                            },
                                        )
                                    }
                                    if (source is ConfigurableSource) {
                                        DropdownMenuItem(
                                            text = { Text(t("action_source_settings")) },
                                            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                            onClick = {
                                                showOverflowMenu = false
                                                onOpenSourceSettings()
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Sort menu
            DropdownMenu(
                expanded = showSortMenu,
                onDismissRequest = { showSortMenu = false },
            ) {
                ChapterSortMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                t(mode.labelKey),
                                color = if (sortMode == mode) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick = {
                            sortMode = mode
                            showSortMenu = false
                        },
                    )
                }
            }

            // Filter chips + chapter number filter
            if (!selectionMode && chapters.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    // Status filter chips
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = filterUnreadOnly,
                            onClick = { filterUnreadOnly = !filterUnreadOnly },
                            label = { Text(t("filter_unread")) },
                        )
                        FilterChip(
                            selected = filterDownloadedOnly,
                            onClick = { filterDownloadedOnly = !filterDownloadedOnly },
                            label = { Text(t("filter_downloaded")) },
                        )
                        if (filterUnreadOnly || filterDownloadedOnly) {
                            TextButton(onClick = {
                                filterUnreadOnly = false
                                filterDownloadedOnly = false
                            }) {
                                Text(t("filter_clear"), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // Chapter number range filter + Go to chapter
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = filterMinChapter,
                            onValueChange = { filterMinChapter = it.filter { c -> c.isDigit() || c == '.' } },
                            modifier = Modifier.weight(1f),
                            label = { Text(t("detail_ch_min")) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        Text("—", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = filterMaxChapter,
                            onValueChange = { filterMaxChapter = it.filter { c -> c.isDigit() || c == '.' } },
                            modifier = Modifier.weight(1f),
                            label = { Text(t("detail_ch_max")) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        TextButton(onClick = { showGoToDialog = true }) {
                            Text(t("detail_go_to_ch"))
                        }
                    }

                    // Active filter info
                    val minCh = filterMinChapter.toDoubleOrNull()
                    val maxCh = filterMaxChapter.toDoubleOrNull()
                    if (minCh != null || maxCh != null) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                t("detail_filter_info", sortedChapters.size, chapters.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = {
                                filterMinChapter = ""
                                filterMaxChapter = ""
                            }) {
                                Text(t("detail_clear_ch_filter"), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // Manga info
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
                    // Mark all as read button
                    if (chapters.isNotEmpty()) {
                        Row(
                            Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            androidx.compose.material3.TextButton(onClick = ::markAllAsRead) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(t("detail_mark_all_read"), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            if (error != null) {
                Text(
                    t("common_error_prefix", error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp),
                )
            }

            val savedProgress = progress
            if (savedProgress != null && !selectionMode) {
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
                                Text(t("detail_continue_reading"), style = MaterialTheme.typography.labelMedium)
                                Text(savedProgress.chapterName, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            // Tracking card: one row per logged-in tracker -- a "Track"
            // button when unbound, an inline editor when bound.
            if (isFavorite && !selectionMode && trackerManager.trackers.any { trackerLoginStates[it.name] == true }) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(t("tracking_title"), style = MaterialTheme.typography.titleSmall)
                        for (tracker in trackerManager.trackers) {
                            if (trackerLoginStates[tracker.name] != true) continue
                            val entry = trackEntries.firstOrNull { it.trackerName == tracker.name }
                            if (entry == null) {
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        prettyTrackerName(tracker.name),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { trackSearchFor = tracker }) { Text(t("track_action")) }
                                }
                            } else {
                                TrackEntryEditor(
                                    tracker = tracker,
                                    entry = entry,
                                    onUpdated = { updated ->
                                        scope.launch {
                                            val stored = trackerRepository.upsert(updated)
                                            trackEntries = trackEntries.map { if (it.id == stored.id) stored else it }
                                        }
                                    },
                                    onRemoved = {
                                        scope.launch {
                                            runCatching { trackerRepository.delete(entry.id) }
                                            trackEntries = trackEntries.filterNot { it.id == entry.id }
                                        }
                                    },
                                )
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
                    items(sortedChapters, key = { it.url }) { chapter ->
                        val originalIndex = chapters.indexOf(chapter)
                        val isDownloaded = downloadedMap[chapter.url] == true
                        // Queue state for this chapter: active job (queued or
                        // running) drives the row; a FAILED job surfaces the
                        // error and lets the download button requeue it.
                        // mangaUrl is part of the match: relative chapter URLs
                        // collide across manga in the shared queue.
                        val activeJob = queueJobs.firstOrNull {
                            it.sourceId == source.id && it.mangaUrl == manga.url &&
                                it.chapterUrl == chapter.url &&
                                (it.state == DownloadJobState.QUEUED || it.state == DownloadJobState.RUNNING)
                        }
                        val failedJob = queueJobs.lastOrNull {
                            it.sourceId == source.id && it.mangaUrl == manga.url &&
                                it.chapterUrl == chapter.url && it.state == DownloadJobState.FAILED
                        }
                        val isDownloading = activeJob != null
                        val isRead = readMap[chapter.url] == true
                        val isSelected = selectedChapters[chapter.url] == true

                        ListItem(
                            headlineContent = {
                                Text(
                                    chapter.name,
                                    color = if (isRead) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            supportingContent = {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (chapter.date_upload > 0) {
                                            Text(DateFormat.getDateInstance().format(Date(chapter.date_upload)))
                                        }
                                        if (isDownloaded) {
                                            Text(
                                                " · " + t("detail_marker_downloaded"),
                                                color = MaterialTheme.colorScheme.primary,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                        if (isRead) {
                                            Text(
                                                " · " + t("detail_marker_read"),
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                        if (activeJob?.state == DownloadJobState.QUEUED) {
                                            Text(
                                                " · " + t("detail_marker_queued"),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                    }
                                    val job = activeJob
                                    if (job?.state == DownloadJobState.RUNNING && job.pagesTotal > 0) {
                                        LinearProgressIndicator(
                                            progress = { job.pagesDone.toFloat() / job.pagesTotal.toFloat() },
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        )
                                        Text(
                                            t("common_pages_progress", job.pagesDone, job.pagesTotal),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                    failedJob?.let {
                                        Text(
                                            t("detail_download_failed", it.error ?: Strings.get("common_unknown_error")),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            },
                            leadingContent = {
                                if (selectionMode) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            selectedChapters[chapter.url] = checked
                                        },
                                    )
                                } else {
                                    IconButton(
                                        onClick = { onChapterSelected(chapters, originalIndex, 0) },
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = t("action_read"))
                                    }
                                }
                            },
                            trailingContent = {
                                if (selectionMode) {
                                    // No trailing content in selection mode
                                } else {
                                    Row {
                                        IconButton(onClick = { toggleRead(chapter) }) {
                                            Icon(
                                                if (isRead) Icons.Filled.CheckCircle else Icons.Filled.Check,
                                                contentDescription = if (isRead) t("action_mark_unread") else t("action_mark_read"),
                                                tint = if (isRead) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                            )
                                        }
                                        if (isDownloading) {
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                        } else if (isDownloaded) {
                                            IconButton(onClick = { deleteChapter(chapter) }) {
                                                Icon(
                                                    Icons.Filled.Delete,
                                                    contentDescription = t("action_delete_download"),
                                                )
                                            }
                                        } else {
                                            IconButton(onClick = { enqueueDownload(chapter) }) {
                                                Icon(
                                                    Icons.Filled.CloudDownload,
                                                    contentDescription = t("action_download"),
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // Go-to-chapter dialog
    if (showGoToDialog) {
        var goToInput by remember { mutableStateOf("") }
        var goToError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = {
                showGoToDialog = false
                goToError = null
            },
            title = { Text(t("goto_title")) },
            text = {
                Column {
                    Text(
                        t("goto_hint", chapters.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = goToInput,
                        onValueChange = {
                            goToInput = it.filter { c -> c.isDigit() || c == '.' }
                            goToError = null
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = goToError != null,
                        supportingText = goToError?.let { err -> { Text(err, color = MaterialTheme.colorScheme.error) } },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val num = goToInput.toDoubleOrNull()
                    if (num == null) {
                        goToError = Strings.get("goto_invalid_number")
                        return@TextButton
                    }
                    // Find the chapter with the closest chapter number
                    val match = sortedChapters.minByOrNull { ch ->
                        val chNum = ch.parseChapterNameNumber() ?: Double.MAX_VALUE
                        kotlin.math.abs(chNum - num)
                    }
                    if (match == null) {
                        goToError = Strings.get("goto_no_chapters")
                        return@TextButton
                    }
                    val chNum = match.parseChapterNameNumber()
                    if (chNum == null || chNum != num) {
                        goToError = Strings.get("goto_not_found", num, chNum)
                        return@TextButton
                    }
                    val idx = sortedChapters.indexOf(match)
                    val originalIdx = chapters.indexOf(match)
                    showGoToDialog = false
                    goToError = null
                    onChapterSelected(chapters, originalIdx, 0)
                }) {
                    Text(t("common_go"))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showGoToDialog = false
                    goToError = null
                }) {
                    Text(t("common_cancel"))
                }
            },
        )
    }
    // Edit categories (library manga only; the overflow item only appears
    // while isFavorite, but guard again in case favorite is toggled while open)
    if (showEditCategories) {
        if (isFavorite) {
            EditCategoriesDialog(
                categoryRepository = categoryRepository,
                sourceId = source.id,
                mangaUrl = manga.url,
                onDismiss = { showEditCategories = false },
            )
        } else {
            showEditCategories = false
        }
    }
    // Track search: bind this manga to a remote entry on the chosen tracker.
    // Seeds the remote with the local reading state (all read -> Completed,
    // otherwise the highest read chapter number).
    trackSearchFor?.let { tracker ->
        if (isFavorite) {
            val readChapters = chapters.filter { readMap[it.url] == true }
            val highestRead = readChapters.mapNotNull { it.parseChapterNameNumber() }.maxOrNull()
            val allRead = chapters.isNotEmpty() && readChapters.size == chapters.size
            TrackSearchDialog(
                tracker = tracker,
                initialQuery = detail.title,
                onDismiss = { trackSearchFor = null },
                onBind = { hit ->
                    val mangaId = trackerRepository.mangaIdFor(source.id, manga.url)
                        ?: error("Manga is not in the library")
                    val bound = tracker.bind(
                        TrackEntry(
                            mangaId = mangaId,
                            trackerName = tracker.name,
                            remoteId = hit.remoteId,
                            title = hit.title,
                        ),
                        TrackUpdate(
                            status = if (allRead) TrackStatus.COMPLETED else TrackStatus.READING,
                            lastChapterRead = highestRead,
                        ),
                    )
                    val stored = trackerRepository.upsert(bound)
                    trackEntries = trackEntries.filterNot {
                        it.trackerName == stored.trackerName && it.mangaId == stored.mangaId
                    } + stored
                },
            )
        } else {
            trackSearchFor = null
        }
    }
}

/**
 * Checkbox list of the user's categories for one library manga, with an inline
 * "create category" row. Save applies the whole checked set atomically
 * (replace, not merge).
 */
@Composable
private fun EditCategoriesDialog(
    categoryRepository: CategoryRepository,
    sourceId: Long,
    mangaUrl: String,
    onDismiss: () -> Unit,
) {
    var allCategories by remember { mutableStateOf<List<Category>?>(null) }
    var checked by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var newCategoryName by remember { mutableStateOf("") }
    var newCategoryError by remember { mutableStateOf<String?>(null) }
    var creatingCategory by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        allCategories = categoryRepository.all()
        checked = categoryRepository.categoriesForManga(sourceId, mangaUrl).map { it.id }.toSet()
    }

    fun toggle(id: Long) {
        checked = if (id in checked) checked - id else checked + id
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("edit_categories_title")) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                val cats = allCategories
                when {
                    cats == null -> Box(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                    cats.isEmpty() -> Text(
                        t("edit_categories_empty"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> cats.forEach { category ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { toggle(category.id) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = category.id in checked,
                                onCheckedChange = { toggle(category.id) },
                            )
                            Text(category.name)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Inline "add new category" -- creates it and checks it right away
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newCategoryName,
                        onValueChange = {
                            newCategoryName = it
                            newCategoryError = null
                        },
                        label = { Text(t("new_category")) },
                        singleLine = true,
                        isError = newCategoryError != null,
                        supportingText = newCategoryError?.let { err ->
                            { Text(err, color = MaterialTheme.colorScheme.error) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            if (creatingCategory) return@IconButton
                            creatingCategory = true
                            scope.launch {
                                try {
                                    val id = categoryRepository.create(newCategoryName)
                                    if (id == null) {
                                        newCategoryError = Strings.get("error_category_name")
                                    } else {
                                        allCategories = categoryRepository.all()
                                        checked = checked + id
                                        newCategoryName = ""
                                    }
                                } finally {
                                    creatingCategory = false
                                }
                            }
                        },
                        enabled = !creatingCategory,
                    ) {
                        if (creatingCategory) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                        } else {
                            Icon(Icons.Filled.Add, contentDescription = t("action_add_category"))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    categoryRepository.setMangaCategories(sourceId, mangaUrl, checked)
                    onDismiss()
                }
            }) {
                Text(t("common_save"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(t("common_cancel"))
            }
        },
    )
}

private fun fetchThumbnailBytes(source: Source, url: String): ByteArray? {
    val httpSource = source as? HttpSource ?: return null
    return httpSource.client.newCall(GET(url, httpSource.headers)).execute().use { response ->
        if (response.isSuccessful) response.body.bytes() else null
    }
}

@Composable
private fun trackStatusLabel(status: TrackStatus): String = when (status) {
    TrackStatus.READING -> t("track_status_reading")
    TrackStatus.COMPLETED -> t("track_status_completed")
    TrackStatus.ON_HOLD -> t("track_status_on_hold")
    TrackStatus.DROPPED -> t("track_status_dropped")
    TrackStatus.PLAN_TO_READ -> t("track_status_plan_to_read")
    TrackStatus.REPEATING -> t("track_status_rereading")
}

/**
 * Inline editor for one bound tracker: status dropdown, score (0-10) and
 * chapter-read fields, Save pushes all three; "Remove tracking" deletes the
 * remote entry (best-effort) and the local row.
 */
@Composable
private fun TrackEntryEditor(
    tracker: Tracker,
    entry: TrackEntry,
    onUpdated: (TrackEntry) -> Unit,
    onRemoved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var status by remember(entry.id) { mutableStateOf(entry.status) }
    var score by remember(entry.id) { mutableStateOf(entry.score?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "") }
    var chapter by remember(entry.id) { mutableStateOf(entry.lastChapterRead?.toInt()?.toString() ?: "") }
    var statusMenuOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        // Reject out-of-range (and unparseable) scores before pushing:
        // remote APIs 400 on them and would leave local/remote out of sync.
        val parsedScore = score.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }
        if (score.isNotBlank() && parsedScore == null) {
            error = Strings.get("track_error_score")
            return
        }
        busy = true
        error = null
        scope.launch {
            runCatching {
                tracker.update(
                    entry,
                    TrackUpdate(
                        status = status,
                        score = parsedScore,
                        lastChapterRead = chapter.toDoubleOrNull(),
                    ),
                )
            }.onSuccess { onUpdated(it) }
                .onFailure { error = it.message ?: it.toString() }
            busy = false
        }
    }

    Column(Modifier.padding(top = 8.dp)) {
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                prettyTrackerName(tracker.name),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    runCatching { tracker.unbind(entry) }
                        .onFailure { Logger.w(TAG, "Remote unbind failed (removed locally anyway): ${it.message}") }
                    busy = false
                    onRemoved()
                }
            }) { Text(t("track_remove")) }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                TextButton(enabled = !busy, onClick = { statusMenuOpen = true }) {
                    Text(trackStatusLabel(status))
                }
                DropdownMenu(expanded = statusMenuOpen, onDismissRequest = { statusMenuOpen = false }) {
                    TrackStatus.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(trackStatusLabel(s)) },
                            onClick = {
                                status = s
                                statusMenuOpen = false
                            },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = score,
                onValueChange = { score = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text(t("track_score")) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            OutlinedTextField(
                value = chapter,
                onValueChange = { chapter = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text(t("track_ch_read")) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !busy, onClick = ::save) { Text(t("common_save")) }
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp))
            }
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
