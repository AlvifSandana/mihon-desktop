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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
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
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.desktop.loader.download.DownloadManager
import mihon.desktop.loader.library.LibraryRepository
import mihon.desktop.loader.library.ReadingProgress
import mihon.desktop.loader.log.Logger
import java.text.DateFormat
import java.util.Date

private enum class ChapterSortMode(val label: String) {
    SOURCE_ORDER("Source order"),
    NAME_ASC("Name (A-Z)"),
    NAME_DESC("Name (Z-A)"),
    DATE_NEWEST("Newest first"),
    DATE_OLDEST("Oldest first"),
}

private const val TAG = "MangaDetailScreen"

/** Extracts the chapter number from a chapter name (e.g. "Chapter 12.5" -> 12.5, "Ch 1" -> 1). */
private fun SChapter.chapterNumber(): Double? {
    val name = this.name
    // Match "Chapter 12.5", "Ch 12.5", "Ch. 12.5", etc.
    val match = Regex("""[Cc]h(?:apter|\.)?\s*([\d.]+)""").find(name)
        ?: Regex("""\b([\d]+(?:\.[\d]+)?)\b""").find(name)
    return match?.groupValues?.get(1)?.toDoubleOrNull()
}

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
    val downloadManager = remember { DownloadManager() }

    var detail by remember(manga) { mutableStateOf(manga) }
    var chapters by remember(manga) { mutableStateOf(listOf<SChapter>()) }
    var loading by remember(manga) { mutableStateOf(true) }
    var error by remember(manga) { mutableStateOf<String?>(null) }
    var isFavorite by remember(manga) { mutableStateOf(false) }
    var progress by remember(manga) { mutableStateOf<ReadingProgress?>(null) }
    var sortMode by remember(manga) { mutableStateOf(ChapterSortMode.SOURCE_ORDER) }
    var showSortMenu by remember(manga) { mutableStateOf(false) }
    var selectionMode by remember(manga) { mutableStateOf(false) }
    var filterUnreadOnly by remember(manga) { mutableStateOf(false) }
    var filterDownloadedOnly by remember(manga) { mutableStateOf(false) }
    val selectedChapters = remember { mutableStateMapOf<String, Boolean>() }

    // Chapter number filter
    var filterMinChapter by remember(manga) { mutableStateOf("") }
    var filterMaxChapter by remember(manga) { mutableStateOf("") }

    // Go-to-chapter dialog
    var showGoToDialog by remember(manga) { mutableStateOf(false) }

    // Track download state per chapter URL
    val downloadedMap = remember { mutableStateMapOf<String, Boolean>() }
    val downloadingSet = remember { mutableStateMapOf<String, Boolean>() }
    val downloadProgress = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
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

    val sortedChapters = remember(chapters, sortMode, filterUnreadOnly, filterDownloadedOnly, filterMinChapter, filterMaxChapter, readMap, downloadedMap) {
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
                        val num = ch.chapterNumber()
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

    fun downloadChapter(chapter: SChapter) {
        scope.launch {
            downloadingSet[chapter.url] = true
            downloadProgress.remove(chapter.url)
            runCatching {
                downloadManager.downloadChapter(source, manga.url, chapter) { current, total ->
                    downloadProgress[chapter.url] = current to total
                }
            }.onSuccess {
                downloadedMap[chapter.url] = true
            }.onFailure {
                Logger.e(TAG, "Download failed: ${it.message}", it)
                error = "Download failed: ${it.message ?: it}"
            }
            downloadingSet.remove(chapter.url)
            downloadProgress.remove(chapter.url)
        }
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
            }
        }
    }

    fun markAllAsRead() {
        scope.launch {
            repository.markAllAsRead(source.id, manga.url, chapters)
            for (ch in chapters) readMap[ch.url] = true
        }
    }

    fun downloadSelected() {
        scope.launch {
            for (ch in sortedChapters) {
                if (selectedChapters[ch.url] == true && downloadedMap[ch.url] != true) {
                    downloadChapter(ch)
                }
            }
            selectionMode = false
            selectedChapters.clear()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selectionMode) {
                        val count = selectedChapters.count { it.value }
                        Text("$count selected")
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                            Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
                        }
                        IconButton(onClick = { downloadSelected() }) {
                            Icon(Icons.Filled.CloudDownload, contentDescription = "Download selected")
                        }
                    } else {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                        }
                        IconButton(onClick = { selectionMode = true }) {
                            Icon(Icons.Filled.Check, contentDescription = "Select chapters")
                        }
                        IconButton(onClick = ::toggleFavorite) {
                            Icon(
                                if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = if (isFavorite) "Remove from library" else "Add to library",
                            )
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
                                mode.label,
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
                            label = { Text("Unread") },
                        )
                        FilterChip(
                            selected = filterDownloadedOnly,
                            onClick = { filterDownloadedOnly = !filterDownloadedOnly },
                            label = { Text("Downloaded") },
                        )
                        if (filterUnreadOnly || filterDownloadedOnly) {
                            TextButton(onClick = {
                                filterUnreadOnly = false
                                filterDownloadedOnly = false
                            }) {
                                Text("Clear", style = MaterialTheme.typography.labelSmall)
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
                            label = { Text("Ch. min") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        Text("—", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = filterMaxChapter,
                            onValueChange = { filterMaxChapter = it.filter { c -> c.isDigit() || c == '.' } },
                            modifier = Modifier.weight(1f),
                            label = { Text("Ch. max") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        TextButton(onClick = { showGoToDialog = true }) {
                            Text("Go to ch.")
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
                                "${sortedChapters.size} of ${chapters.size} chapters",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = {
                                filterMinChapter = ""
                                filterMaxChapter = ""
                            }) {
                                Text("Clear ch. filter", style = MaterialTheme.typography.labelSmall)
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
                                Text("Mark all as read", style = MaterialTheme.typography.labelSmall)
                            }
                        }
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
                    items(sortedChapters, key = { it.url }) { chapter ->
                        val originalIndex = chapters.indexOf(chapter)
                        val isDownloaded = downloadedMap[chapter.url] == true
                        val isDownloading = downloadingSet[chapter.url] == true
                        val isRead = readMap[chapter.url] == true
                        val isSelected = selectedChapters[chapter.url] == true
                        val prog = downloadProgress[chapter.url]

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
                                                " · Downloaded",
                                                color = MaterialTheme.colorScheme.primary,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                        if (isRead) {
                                            Text(
                                                " · Read",
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                    }
                                    if (isDownloading && prog != null) {
                                        val (current, total) = prog
                                        LinearProgressIndicator(
                                            progress = { current.toFloat() / total.coerceAtLeast(1).toFloat() },
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        )
                                        Text(
                                            "$current / $total pages",
                                            style = MaterialTheme.typography.labelSmall,
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
                                        Icon(Icons.Filled.PlayArrow, contentDescription = "Read")
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
                                                contentDescription = if (isRead) "Mark unread" else "Mark read",
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
                                                    contentDescription = "Delete download",
                                                )
                                            }
                                        } else {
                                            IconButton(onClick = { downloadChapter(chapter) }) {
                                                Icon(
                                                    Icons.Filled.CloudDownload,
                                                    contentDescription = "Download",
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
            title = { Text("Go to chapter") },
            text = {
                Column {
                    Text(
                        "Enter chapter number (1-${chapters.size})",
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
                        goToError = "Invalid number"
                        return@TextButton
                    }
                    // Find the chapter with the closest chapter number
                    val match = sortedChapters.minByOrNull { ch ->
                        val chNum = ch.chapterNumber() ?: Double.MAX_VALUE
                        kotlin.math.abs(chNum - num)
                    }
                    if (match == null) {
                        goToError = "No chapters found"
                        return@TextButton
                    }
                    val chNum = match.chapterNumber()
                    if (chNum == null || chNum != num) {
                        goToError = "Chapter $num not found. Closest: Ch. $chNum"
                        return@TextButton
                    }
                    val idx = sortedChapters.indexOf(match)
                    val originalIdx = chapters.indexOf(match)
                    showGoToDialog = false
                    goToError = null
                    onChapterSelected(chapters, originalIdx, 0)
                }) {
                    Text("Go")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showGoToDialog = false
                    goToError = null
                }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun fetchThumbnailBytes(source: Source, url: String): ByteArray? {
    val httpSource = source as? HttpSource ?: return null
    return httpSource.client.newCall(GET(url, httpSource.headers)).execute().use { response ->
        if (response.isSuccessful) response.body.bytes() else null
    }
}
