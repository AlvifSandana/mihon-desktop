package mihon.desktop.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.desktop.loader.ExtensionLoader
import mihon.desktop.loader.library.LibraryManga
import mihon.desktop.loader.library.LibraryRepository
import mihon.desktop.loader.migration.MigrationCandidate
import mihon.desktop.loader.migration.MigrationEngine
import mihon.desktop.loader.migration.MigrationOptions
import mihon.desktop.loader.migration.MigrationResult
import mihon.desktop.loader.migration.MigrationStatus
import mihon.desktop.loader.migration.MigrationTarget
import mihon.desktop.app.i18n.t

private val extensionCacheDir = ExtensionLoader.extensionCacheDir

private const val STEP_PICK_MANGA = 0
private const val STEP_PICK_TARGET = 1
private const val STEP_OPTIONS = 2
private const val STEP_RUN = 3

/** One line in the progress list; null status = still working on it. */
private data class MigrationItemUi(val title: String, val status: MigrationStatus?)

/** Manual-match prompt: candidates plus the deferred the run loop awaits. */
private class ManualMatchPrompt(
    val manga: LibraryManga,
    val candidates: List<MigrationCandidate>,
    val choice: CompletableDeferred<SManga?>,
)

@Composable
private fun statusLabel(status: MigrationStatus?): String = when (status) {
    null -> t("migrate_status_searching")
    is MigrationStatus.Migrated -> t("migrate_status_migrated", status.toTitle)
    MigrationStatus.NoMatch -> t("migrate_status_no_match")
    MigrationStatus.NeedsManualMatch -> t("migrate_status_manual")
    MigrationStatus.Skipped -> t("migrate_status_skipped")
    is MigrationStatus.Failed -> t("migrate_status_failed", status.error)
}

/**
 * Source-to-source migration wizard (Mihon-style, simplified):
 * 1. pick manga to migrate from one source's library,
 * 2. pick the target source,
 * 3. pick options + auto/manual matching,
 * 4. run with per-manga progress, then a summary.
 *
 * Cancel aborts the batch between manga; DB writes already in flight are
 * finished by the engine (NonCancellable), so no half-written migrations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrateMangaScreen(
    sourceId: Long,
    sourceName: String,
    onBack: () -> Unit,
    onFinished: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val repository = remember { LibraryRepository() }
    val engine = remember { MigrationEngine() }

    var step by remember { mutableIntStateOf(STEP_PICK_MANGA) }

    // Step 1 state
    var mangaList by remember { mutableStateOf<List<LibraryManga>>(emptyList()) }
    var loadingManga by remember { mutableStateOf(true) }
    val selectedUrls = remember { mutableStateMapOf<String, Boolean>() }

    // Step 2 state
    var targets by remember { mutableStateOf<List<MigrationTarget>>(emptyList()) }
    var loadingTargets by remember { mutableStateOf(true) }
    var selectedTarget by remember { mutableStateOf<MigrationTarget?>(null) }

    // Step 3 state
    var includeCategories by remember { mutableStateOf(true) }
    var includeReadChapters by remember { mutableStateOf(true) }
    var downloadChapters by remember { mutableStateOf(false) }
    var deleteFromLibrary by remember { mutableStateOf(true) }
    var searchAutomatically by remember { mutableStateOf(true) }

    // Step 4 state
    var job by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf(false) }
    var cancelled by remember { mutableStateOf(false) }
    val items = remember { mutableStateListOf<MigrationItemUi>() }
    var progressLabel by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MigrationResult>>(emptyList()) }
    var manualPrompt by remember { mutableStateOf<ManualMatchPrompt?>(null) }

    LaunchedEffect(sourceId) {
        loadingManga = true
        mangaList = repository.all().filter { it.sourceId == sourceId }
        loadingManga = false
    }

    LaunchedEffect(Unit) {
        loadingTargets = true
        // Same jar-scan pattern as SourcesTab: build MigrationTargets from the
        // installed extension cache, minus the source we are migrating from.
        // Only catalogue sources can be searched, so they are the only valid targets.
        targets = withContext(Dispatchers.IO) {
            val jars = extensionCacheDir.listFiles()
                ?.filter { it.isFile && it.extension == "jar" }
                ?: emptyList()
            jars.flatMap { jar ->
                runCatching {
                    val loaded = ExtensionLoader.load(jar)
                    loaded.sources
                        .filterIsInstance<CatalogueSource>()
                        .map { source ->
                            MigrationTarget(
                                source = source,
                                packageName = loaded.metadata.packageName,
                                jarFileName = jar.name,
                                extensionName = loaded.metadata.name ?: loaded.metadata.packageName,
                            )
                        }
                }.getOrDefault(emptyList())
            }.filter { it.source.id != sourceId }
        }
        loadingTargets = false
    }

    val selectedEntries = mangaList.filter { selectedUrls[it.mangaUrl] == true }
    val options = MigrationOptions(
        includeCategories = includeCategories,
        includeReadChapters = includeReadChapters,
        downloadChapters = downloadChapters,
        deleteFromLibrary = deleteFromLibrary,
    )

    fun startRun() {
        val target = selectedTarget ?: return
        if (selectedEntries.isEmpty()) return
        job?.cancel()
        cancelled = false
        running = true
        items.clear()
        results = emptyList()
        job = scope.launch {
            try {
                if (searchAutomatically) {
                    results = engine.migrateBatch(
                        entries = selectedEntries,
                        target = target,
                        options = options,
                        onItemStart = { index, total, manga ->
                            items += MigrationItemUi(manga.title, null)
                            progressLabel = "${index + 1}/$total"
                        },
                        onItemResult = { index, _, result ->
                            items[index] = MigrationItemUi(result.manga.title, result.status)
                        },
                    )
                } else {
                    val out = mutableListOf<MigrationResult>()
                    selectedEntries.forEachIndexed { index, entry ->
                        items += MigrationItemUi(entry.title, null)
                        progressLabel = "${index + 1}/${selectedEntries.size}"
                        val status = try {
                            val candidates = engine.findCandidates(target.source, entry.title)
                            if (candidates.isEmpty()) {
                                MigrationStatus.NoMatch
                            } else {
                                val prompt = ManualMatchPrompt(entry, candidates, CompletableDeferred())
                                manualPrompt = prompt
                                val picked = prompt.choice.await()
                                manualPrompt = null
                                if (picked == null) {
                                    MigrationStatus.Skipped
                                } else {
                                    engine.migrateManga(entry, target, picked, options)
                                }
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            MigrationStatus.Failed(e.message ?: e.toString())
                        }
                        items[index] = MigrationItemUi(entry.title, status)
                        out += MigrationResult(entry, status)
                    }
                    results = out
                }
            } catch (ce: CancellationException) {
                cancelled = true
            } finally {
                manualPrompt = null
                running = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("migrate_title", sourceName)) },
                navigationIcon = {
                    IconButton(onClick = { if (!running) onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("common_back"))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                when (step) {
                STEP_PICK_MANGA -> {
                    when {
                        loadingManga -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        mangaList.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(t("migrate_no_manga"))
                        }
                        else -> {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val allSelected = selectedEntries.size == mangaList.size
                                TextButton(onClick = {
                                    val select = !allSelected
                                    for (m in mangaList) selectedUrls[m.mangaUrl] = select
                                }) {
                                    Text(if (allSelected) t("action_select_none") else t("action_select_all"))
                                }
                                Text(
                                    t("common_selected_count", selectedEntries.size),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            LazyColumn(Modifier.fillMaxSize().weight(1f)) {
                                items(mangaList, key = { it.mangaUrl }) { manga ->
                                    val checked = selectedUrls[manga.mangaUrl] == true
                                    ListItem(
                                        headlineContent = { Text(manga.title) },
                                        leadingContent = {
                                            Checkbox(
                                                checked = checked,
                                                onCheckedChange = { selectedUrls[manga.mangaUrl] = it },
                                            )
                                        },
                                        modifier = Modifier.clickable {
                                            selectedUrls[manga.mangaUrl] = !checked
                                        },
                                    )
                                    HorizontalDivider()
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Button(
                                    enabled = selectedEntries.isNotEmpty(),
                                    onClick = { step = STEP_PICK_TARGET },
                                ) {
                                    Text(t("common_next"))
                                }
                            }
                        }
                    }
                }

                STEP_PICK_TARGET -> {
                    when {
                        loadingTargets -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        targets.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(t("migrate_no_targets"))
                                Text(
                                    t("migrate_no_targets_hint"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        else -> {
                            LazyColumn(Modifier.fillMaxSize().weight(1f)) {
                                item(key = "header") {
                                    Text(
                                        t("migrate_pick_target"),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                                items(targets, key = { it.source.id }) { target ->
                                    ListItem(
                                        headlineContent = { Text(target.source.name) },
                                        supportingContent = { Text(target.extensionName) },
                                        leadingContent = {
                                            RadioButton(
                                                selected = selectedTarget == target,
                                                onClick = { selectedTarget = target },
                                            )
                                        },
                                        modifier = Modifier.clickable { selectedTarget = target },
                                    )
                                    HorizontalDivider()
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                OutlinedButton(onClick = { step = STEP_PICK_MANGA }) {
                                    Text(t("common_back"))
                                }
                                Button(
                                    enabled = selectedTarget != null,
                                    onClick = { step = STEP_OPTIONS },
                                    modifier = Modifier.padding(start = 8.dp),
                                ) {
                                    Text(t("common_next"))
                                }
                            }
                        }
                    }
                }

                STEP_OPTIONS -> {
                    Column(Modifier.fillMaxSize()) {
                        Text(
                            t("migrate_options"),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        OptionToggle(t("migrate_option_categories"), includeCategories) { includeCategories = it }
                        OptionToggle(t("migrate_option_read"), includeReadChapters) { includeReadChapters = it }
                        OptionToggle(t("migrate_option_download"), downloadChapters) { downloadChapters = it }
                        OptionToggle(t("migrate_option_delete"), deleteFromLibrary) { deleteFromLibrary = it }

                        Text(
                            t("migrate_matching"),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        ListItem(
                            headlineContent = { Text(t("migrate_search_auto")) },
                            supportingContent = { Text(t("migrate_search_auto_hint")) },
                            leadingContent = {
                                RadioButton(
                                    selected = searchAutomatically,
                                    onClick = { searchAutomatically = true },
                                )
                            },
                            modifier = Modifier.clickable { searchAutomatically = true },
                        )
                        ListItem(
                            headlineContent = { Text(t("migrate_match_manual")) },
                            supportingContent = { Text(t("migrate_match_manual_hint")) },
                            leadingContent = {
                                RadioButton(
                                    selected = !searchAutomatically,
                                    onClick = { searchAutomatically = false },
                                )
                            },
                            modifier = Modifier.clickable { searchAutomatically = false },
                        )

                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            OutlinedButton(onClick = { step = STEP_PICK_TARGET }) {
                                Text(t("common_back"))
                            }
                            Button(
                                onClick = {
                                    step = STEP_RUN
                                    startRun()
                                },
                                modifier = Modifier.padding(start = 8.dp),
                            ) {
                                Text(t("action_start_migration"))
                            }
                        }
                    }
                }

                STEP_RUN -> {
                    Column(Modifier.fillMaxSize()) {
                        if (running) {
                            val total = selectedEntries.size.coerceAtLeast(1)
                            val done = items.count { it.status != null }
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(
                                    items.lastOrNull { it.status == null }?.title
                                        ?: items.lastOrNull()?.title
                                        ?: "",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    "$progressLabel   ·   " + t("migrate_done_count", done),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                LinearProgressIndicator(
                                    progress = { done.toFloat() / total },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                )
                            }
                        } else {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(
                                    if (cancelled) t("migrate_cancelled") else t("migrate_finished"),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                // Count from per-item statuses: `results` is only
                                // assigned on normal completion, so a cancelled
                                // run would otherwise always report 0 migrated.
                                val migrated = items.count { it.status is MigrationStatus.Migrated }
                                Text(
                                    if (cancelled) t("migrate_result_count_cancelled", migrated, items.size)
                                    else t("migrate_result_count", migrated, items.size),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        LazyColumn(Modifier.fillMaxSize().weight(1f)) {
                            itemsIndexed(items) { _, item ->
                                ListItem(
                                    headlineContent = { Text(item.title) },
                                    supportingContent = {
                                        Text(
                                            statusLabel(item.status),
                                            color = when (item.status) {
                                                is MigrationStatus.Migrated -> MaterialTheme.colorScheme.primary
                                                is MigrationStatus.Failed -> MaterialTheme.colorScheme.error
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    },
                                )
                                HorizontalDivider()
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            if (running) {
                                OutlinedButton(onClick = { job?.cancel() }) {
                                    Text(t("action_cancel"))
                                }
                            } else {
                                OutlinedButton(onClick = onBack) {
                                    Text(t("migrate_back_to_browse"))
                                }
                                Button(
                                    onClick = onFinished,
                                    modifier = Modifier.padding(start = 8.dp),
                                ) {
                                    Text(t("common_done"))
                                }
                            }
                        }
                    }
                }
            }
            }

            // ── Manual match prompt (bottom overlay on the run step) ───────
            // Aligned modifier is built in BoxScope directly: Modifier.align
            // is a BoxScope member extension and can't be resolved inside let.
            val promptAlignment = Modifier.align(Alignment.BottomCenter)
            manualPrompt?.let { prompt ->
                Surface(
                    shadowElevation = 8.dp,
                    modifier = promptAlignment.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            t("migrate_match_title", prompt.manga.title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        LazyColumn(Modifier.heightIn(max = 260.dp)) {
                            items(prompt.candidates, key = { it.manga.url }) { candidate ->
                                ListItem(
                                    headlineContent = {
                                        Text(candidate.manga.title + if (candidate.exactMatch) " ✓" else "")
                                    },
                                    modifier = Modifier.clickable { prompt.choice.complete(candidate.manga) },
                                )
                                HorizontalDivider()
                            }
                        }
                        TextButton(onClick = { prompt.choice.complete(null) }) {
                            Text(t("migrate_skip"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
            )
        },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}
