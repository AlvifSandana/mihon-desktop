package mihon.desktop.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.desktop.loader.catalog.CatalogExtension
import mihon.desktop.loader.filters.FilterItem
import mihon.desktop.loader.filters.FilterUiState
import mihon.desktop.loader.log.Logger
import mihon.desktop.app.i18n.t

private const val TAG = "SourceBrowseScreen"

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
    // Back navigation (single-slot nav) keeps these two: typed-but-unsubmitted
    // query text and the panel toggle. The manga list and filter values are
    // per-visit — they reload when the screen is re-entered.
    var query by rememberSaveable(source) { mutableStateOf("") }
    var filterPanelOpen by rememberSaveable(source) { mutableStateOf(false) }
    // Guard against parallel fetches racing each other into mangas/nextPage.
    var loadJob by remember(source) { mutableStateOf<Job?>(null) }

    // Filter state is built once per source and lives for this screen visit
    // only (back navigation drops it; nothing is persisted). Controls mutate
    // the live Filter instances in place; the same list is handed to
    // getSearchManga, as upstream Mihon does.
    //
    // getFilterList() runs here at composition (once per source) rather than
    // in a LaunchedEffect so the filter icon/panel render on the first
    // frame; it is pure list construction for essentially every source. If
    // some source proves expensive, move it behind a LaunchedEffect with
    // nullable state.
    val (filterState, fallbackFilterList) = remember(source) {
        val list = catalogueSource?.let { src ->
            runCatching { src.getFilterList() }
                .onFailure { Logger.w(TAG, "getFilterList() failed for '${src.name}': $it") }
                .getOrNull()
        } ?: FilterList()
        FilterUiState(list).takeIf { it.items.isNotEmpty() } to list
    }
    // Filter.state is plain mutable state, invisible to Compose — bump this on
    // every edit so the badge recomputes. appliedRevision records the revision
    // the displayed list was loaded with: a mismatch means uncommitted edits,
    // and Apply / Load more must reload from page 1 instead of appending pages
    // fetched under different filters.
    var filterRevision by remember(source) { mutableIntStateOf(0) }
    var appliedRevision by remember(source) { mutableIntStateOf(0) }
    val activeFilterCount = remember(filterRevision) { filterState?.activeCount() ?: 0 }

    fun load(reset: Boolean) {
        val src = catalogueSource ?: return
        // One fetch at a time: cancel any in-flight load so a stale result can
        // never race a newer one into mangas/nextPage.
        loadJob?.cancel()
        val revisionAtLoad = filterRevision
        val trimmedQuery = query.trim()
        loadJob = scope.launch {
            loading = true
            error = null
            val targetPage = if (reset) 1 else nextPage
            try {
                // Source catalogue calls do network I/O -- keep them off the UI thread.
                val result = withContext(Dispatchers.IO) {
                    // Upstream semantics: active filters route browse through the
                    // search endpoint even when the text query is empty.
                    if (trimmedQuery.isNotBlank() || (filterState?.activeCount() ?: 0) > 0) {
                        src.getSearchManga(targetPage, trimmedQuery, filterState?.filterList ?: fallbackFilterList)
                    } else {
                        src.getPopularManga(targetPage)
                    }
                }
                mangas = if (reset) result.mangas else mangas + result.mangas
                hasNextPage = result.hasNextPage
                nextPage = targetPage + 1
                appliedRevision = revisionAtLoad
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                // A cancelled job must not clobber the flag of the load that
                // replaced it.
                if (isActive) loading = false
            }
        }
    }

    LaunchedEffect(source) { load(reset = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(extension.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("common_back")) }
                },
                actions = {
                    if (filterState != null) {
                        IconButton(onClick = { filterPanelOpen = !filterPanelOpen }) {
                            BadgedBox(
                                badge = {
                                    if (activeFilterCount > 0) {
                                        Badge { Text(if (activeFilterCount > 99) "99+" else activeFilterCount.toString()) }
                                    }
                                },
                            ) {
                                Icon(Icons.Default.FilterList, contentDescription = t("action_filters"))
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (catalogueSource == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(t("source_browse_unsupported"))
            }
            return@Scaffold
        }

        Row(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth().padding(8.dp)) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(t("source_search_hint", extension.name)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { load(reset = true) }),
                    )
                    Button(onClick = { load(reset = true) }, modifier = Modifier.padding(start = 8.dp)) {
                        Text(t("common_search"))
                    }
                }

                if (error != null) {
                    Text(
                        t("common_error_prefix", error),
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
                                    Button(
                                        onClick = {
                                            // Uncommitted filter edits: appending would splice
                                            // pages fetched under different filters — restart
                                            // from page 1 instead.
                                            if (filterRevision != appliedRevision) load(reset = true) else load(reset = false)
                                        },
                                    ) { Text(t("action_load_more")) }
                                }
                            }
                        }
                    }
                }
            }

            filterState?.let { fs ->
                AnimatedVisibility(
                    visible = filterPanelOpen,
                    enter = slideInHorizontally { it } + fadeIn(),
                    exit = slideOutHorizontally { it } + fadeOut(),
                ) {
                    FilterPanel(
                        state = fs,
                        applyEnabled = !loading,
                        onChanged = { filterRevision++ },
                        onApply = {
                            filterPanelOpen = false
                            // Nothing changed since the list was loaded — skip the reload.
                            if (filterRevision != appliedRevision) load(reset = true)
                        },
                        onReset = {
                            // Only reload when something actually changes.
                            val hadFilters = fs.activeCount() > 0
                            fs.reset()
                            filterRevision++
                            if (hadFilters) load(reset = true)
                        },
                        onClose = { filterPanelOpen = false },
                    )
                }
            }
        }
    }
}

/**
 * Side panel listing all of a source's filters. Desktop-friendly (no bottom sheet).
 *
 * Performance note: every edit recomposes this whole panel — Filter.state is
 * plain mutable state invisible to Compose, so scoping recomposition per
 * filter would require an observable projection (bigger rework, deliberately
 * skipped for now).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(
    state: FilterUiState,
    applyEnabled: Boolean,
    onChanged: () -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxHeight().width(320.dp),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.fillMaxHeight()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
            ) {
                Text(
                    t("filters_title"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = t("action_close_filters"))
                }
            }
            HorizontalDivider()

            // Consecutive checkbox/tri-state filters (genres etc.) wrap into rows.
            val entries = remember(state) { groupChipRuns(state.items) }
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilterEntryList(entries, onChanged)
            }

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onApply, enabled = applyEnabled, modifier = Modifier.weight(1f)) { Text(t("common_apply")) }
                OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) { Text(t("common_reset")) }
            }
        }
    }
}

/** Panel entry: either a standalone filter, or a run of chip-style filters to wrap. */
private sealed interface PanelEntry {
    data class Single(val item: FilterItem) : PanelEntry
    data class ChipRun(val items: List<FilterItem>) : PanelEntry
}

/** Renders panel entries; shared by the top-level panel and group sections. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterEntryList(entries: List<PanelEntry>, onChanged: () -> Unit) {
    entries.forEachIndexed { index, entry ->
        key(index) {
            when (entry) {
                is PanelEntry.Single -> FilterItemView(entry.item, onChanged)
                is PanelEntry.ChipRun -> FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    entry.items.forEachIndexed { chipIndex, item ->
                        key(chipIndex) { FilterItemView(item, onChanged) }
                    }
                }
            }
        }
    }
}

private fun groupChipRuns(items: List<FilterItem>): List<PanelEntry> {
    val entries = mutableListOf<PanelEntry>()
    var i = 0
    while (i < items.size) {
        val item = items[i]
        if (item is FilterItem.CheckBox || item is FilterItem.TriState) {
            val run = mutableListOf(item)
            while (i + 1 < items.size && (items[i + 1] is FilterItem.CheckBox || items[i + 1] is FilterItem.TriState)) {
                i++
                run.add(items[i])
            }
            entries.add(PanelEntry.ChipRun(run))
        } else {
            entries.add(PanelEntry.Single(item))
        }
        i++
    }
    return entries
}

@Composable
private fun FilterItemView(item: FilterItem, onChanged: () -> Unit) {
    when (item) {
        is FilterItem.Header -> Text(
            item.filter.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        is FilterItem.Separator -> HorizontalDivider()
        is FilterItem.Text -> TextFilterField(item.filter, onChanged)
        is FilterItem.Select -> SelectFilterField(item.filter, onChanged)
        is FilterItem.CheckBox -> CheckBoxChip(item.filter, onChanged)
        is FilterItem.TriState -> TriStateChip(item.filter, onChanged)
        is FilterItem.Sort -> SortFilterField(item.filter, onChanged)
        is FilterItem.Group -> GroupSection(item, onChanged)
    }
}

/** Collapsible section for a [Filter.Group] (nested filters, e.g. genres). */
@Composable
private fun GroupSection(item: FilterItem.Group, onChanged: () -> Unit) {
    var expanded by remember(item.filter) { mutableStateOf(true) }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
        ) {
            Text(
                item.filter.name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) {
                    t("action_collapse", item.filter.name)
                } else {
                    t("action_expand", item.filter.name)
                },
            )
        }
        if (expanded) {
            // Groups often hold genres — same chip-run wrapping as the top level.
            val entries = remember(item) { groupChipRuns(item.children) }
            Column(
                modifier = Modifier.padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilterEntryList(entries, onChanged)
            }
        }
    }
}

@Composable
private fun TextFilterField(filter: Filter.Text, onChanged: () -> Unit) {
    OutlinedTextField(
        value = filter.state,
        onValueChange = {
            filter.state = it
            onChanged()
        },
        label = { Text(filter.name) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectFilterField(filter: Filter.Select<*>, onChanged: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = filter.values.getOrNull(filter.state)?.toString() ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(filter.name) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            filter.values.forEachIndexed { index, value ->
                DropdownMenuItem(
                    text = { Text(value.toString()) },
                    onClick = {
                        filter.state = index
                        onChanged()
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Single checkbox filter, rendered as a chip (e.g. "Hide already in library"). */
@Composable
private fun CheckBoxChip(filter: Filter.CheckBox, onChanged: () -> Unit) {
    FilterChip(
        selected = filter.state,
        onClick = {
            filter.state = !filter.state
            onChanged()
        },
        label = { Text(filter.name) },
        leadingIcon = if (filter.state) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
    )
}

/**
 * Tri-state filter chip cycling IGNORE -> INCLUDE -> EXCLUDE -> IGNORE.
 * Include: filled with primary container + "+". Exclude: error container + "-".
 */
@Composable
private fun TriStateChip(filter: Filter.TriState, onChanged: () -> Unit) {
    val isExcluded = filter.state == Filter.TriState.STATE_EXCLUDE
    FilterChip(
        selected = filter.state != Filter.TriState.STATE_IGNORE,
        onClick = {
            filter.state = when (filter.state) {
                Filter.TriState.STATE_IGNORE -> Filter.TriState.STATE_INCLUDE
                Filter.TriState.STATE_INCLUDE -> Filter.TriState.STATE_EXCLUDE
                else -> Filter.TriState.STATE_IGNORE
            }
            onChanged()
        },
        label = { Text(filter.name) },
        leadingIcon = when (filter.state) {
            Filter.TriState.STATE_INCLUDE -> {
                { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
            }
            Filter.TriState.STATE_EXCLUDE -> {
                { Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
            }
            else -> null
        },
        colors = if (isExcluded) {
            FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            FilterChipDefaults.filterChipColors()
        },
    )
}

/** Sort filter: value dropdown + ascending/descending arrow toggle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortFilterField(filter: Filter.Sort, onChanged: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selection = filter.state
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = selection?.let { filter.values.getOrNull(it.index) } ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text(filter.name) },
                placeholder = if (selection == null) ({ Text(t("common_none")) }) else null,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                filter.values.forEachIndexed { index, value ->
                    DropdownMenuItem(
                        text = { Text(value) },
                        onClick = {
                            filter.state = Filter.Sort.Selection(index, selection?.ascending ?: true)
                            onChanged()
                            expanded = false
                        },
                    )
                }
            }
        }
        IconButton(
            onClick = {
                val current = filter.state ?: return@IconButton
                filter.state = current.copy(ascending = !current.ascending)
                onChanged()
            },
            enabled = selection != null,
        ) {
            Icon(
                if (selection?.ascending == true) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = if (selection?.ascending == true) t("action_sort_ascending") else t("action_sort_descending"),
            )
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
