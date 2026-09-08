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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.desktop.loader.ExtensionLoader
import mihon.desktop.loader.download.DownloadManager
import mihon.desktop.loader.library.LibraryManga
import mihon.desktop.loader.library.LibraryRepository
import mihon.desktop.loader.prefs.AppPreferences

private enum class SortMode(val label: String) {
    TITLE("Title (A-Z)"),
    TITLE_DESC("Title (Z-A)"),
    RECENTLY_ADDED("Recently added"),
    CHAPTER_COUNT("Chapter count"),
}

/**
 * Shows every manga the user has added to their library. Reopening one re-loads its
 * extension jar from the local cache (no network/catalog fetch) and reconstructs an
 * [SManga] from the saved fields -- see `docs/ARCHITECTURE.md` for why that's enough.
 *
 * A refresh button checks each manga's source for new chapters and shows a badge
 * when updates are found.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenManga: (ExtensionRef, Source, SManga) -> Unit,
    onBrowseExtensions: () -> Unit,
) {
    val repository = remember { LibraryRepository() }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(listOf<LibraryManga>()) }
    var sourcesByJar by remember { mutableStateOf(mapOf<String, List<Source>>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.TITLE) }
    var showSortMenu by remember { mutableStateOf(false) }
    var downloadedOnly by remember {
        mutableStateOf(AppPreferences.getBoolean(AppPreferences.KEY_DOWNLOADED_ONLY, false))
    }
    // Track which manga have new chapters available: key = "sourceId:mangaUrl"
    val updatesMap = remember { mutableStateMapOf<String, Int>() }

    LaunchedEffect(Unit) {
        loading = true
        error = null
        runCatching {
            val libraryEntries = repository.all()
            val loadedSources = withContext(Dispatchers.IO) {
                libraryEntries.map { it.jarFileName }.distinct().associateWith { jarFileName ->
                    runCatching {
                        ExtensionLoader.loadCached(jarFileName).sources
                    }.getOrDefault(emptyList())
                }
            }
            libraryEntries to loadedSources
        }.onSuccess { (libraryEntries, loadedSources) ->
            entries = libraryEntries
            sourcesByJar = loadedSources
        }.onFailure {
            if (it is CancellationException) throw it
            error = it.message ?: it.toString()
        }
        loading = false
    }

    fun refresh() {
        scope.launch {
            refreshing = true
            updatesMap.clear()
            for (entry in entries) {
                val source = sourcesByJar[entry.jarFileName]?.firstOrNull { it.id == entry.sourceId }
                if (source !is CatalogueSource) continue
                runCatching {
                    val manga = SManga.create().apply {
                        url = entry.mangaUrl
                        title = entry.title
                        thumbnail_url = entry.thumbnailUrl
                    }
                    // getMangaUpdate does network I/O -- keep it off the UI thread.
                    val update = withContext(Dispatchers.IO) {
                        source.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)
                    }
                    // recordNewChapters persists only never-seen chapters and
                    // returns them, so the badge counts *new* chapters (and the
                    // Updates tab stays in sync with manual refreshes).
                    repository.recordNewChapters(
                        sourceId = entry.sourceId,
                        mangaUrl = entry.mangaUrl,
                        mangaTitle = entry.title,
                        thumbnailUrl = entry.thumbnailUrl,
                        packageName = entry.packageName,
                        jarFileName = entry.jarFileName,
                        chapters = update.chapters,
                    )
                }.onSuccess { fresh ->
                    if (fresh.isNotEmpty()) {
                        updatesMap["${entry.sourceId}:${entry.mangaUrl}"] = fresh.size
                    }
                }
            }
            refreshing = false
        }
    }

    // Keys ("sourceId:mangaUrl") of manga with at least one downloaded chapter,
    // computed off the UI thread for the downloadedOnly filter.
    val downloadManager = remember { DownloadManager() }
    var downloadedKeys by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(entries, downloadedOnly) {
        downloadedKeys = if (!downloadedOnly) {
            null
        } else {
            entries
                .filter { downloadManager.downloadedChapters(it.sourceId, it.mangaUrl).isNotEmpty() }
                .map { "${it.sourceId}:${it.mangaUrl}" }
                .toSet()
        }
    }

    // updatesMap is a SnapshotStateMap whose instance never changes, so we key the
    // sort computation on its size to pick up refresh results.
    val updatesTick = updatesMap.size
    val filteredEntries = remember(entries, searchQuery, sortMode, updatesTick, downloadedKeys) {
        entries
            .filter { entry ->
                searchQuery.isBlank() || entry.title.contains(searchQuery, ignoreCase = true)
            }
            .filter { entry ->
                val keys = downloadedKeys ?: return@filter true
                "${entry.sourceId}:${entry.mangaUrl}" in keys
            }
            .let { list ->
                when (sortMode) {
                    SortMode.TITLE -> list.sortedBy { it.title.lowercase() }
                    SortMode.TITLE_DESC -> list.sortedByDescending { it.title.lowercase() }
                    SortMode.RECENTLY_ADDED -> list.sortedByDescending { it.addedAt }
                    SortMode.CHAPTER_COUNT -> list.sortedByDescending { entry ->
                        updatesMap["${entry.sourceId}:${entry.mangaUrl}"] ?: 0
                    }
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = ::refresh, enabled = entries.isNotEmpty() && !refreshing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Check for updates")
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
                else -> Column(Modifier.fillMaxSize()) {
                    // Search bar
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search library...") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        Spacer(Modifier.width(8.dp))
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                SortMode.entries.forEach { mode ->
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
                        }
                    }

                    if (filteredEntries.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No matches found")
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 140.dp),
                            contentPadding = PaddingValues(8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(filteredEntries, key = { it.sourceId to it.mangaUrl }) { entry ->
                                val source = sourcesByJar[entry.jarFileName]?.firstOrNull { it.id == entry.sourceId }
                                val updateKey = "${entry.sourceId}:${entry.mangaUrl}"
                                val chapterCount = updatesMap[updateKey]
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
                                    if (chapterCount != null) {
                                        BadgedBox(
                                            badge = {
                                                Badge { Text("$chapterCount") }
                                            },
                                        ) {
                                            AsyncImage(
                                                key = entry.thumbnailUrl,
                                                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).height(180.dp),
                                            ) {
                                                val url = entry.thumbnailUrl ?: return@AsyncImage null
                                                fetchThumbnailBytes(source, url)
                                            }
                                        }
                                    } else {
                                        AsyncImage(
                                            key = entry.thumbnailUrl,
                                            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).height(180.dp),
                                        ) {
                                            val url = entry.thumbnailUrl ?: return@AsyncImage null
                                            fetchThumbnailBytes(source, url)
                                        }
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

            if (refreshing) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
