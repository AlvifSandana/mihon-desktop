package mihon.desktop.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import mihon.desktop.loader.library.StatsRepository
import mihon.desktop.app.i18n.t

/**
 * Library statistics: overview numbers, recent reading activity, per-source
 * and per-category breakdowns. Text + progress bars only -- no charts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val repository = remember { StatsRepository() }
    var stats by remember { mutableStateOf<StatsRepository.LibraryStats?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { repository.snapshot() }
            .onSuccess { stats = it }
            .onFailure {
                if (it is CancellationException) throw it
                error = it.message ?: it.toString()
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("stats_title")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("common_back"))
                    }
                },
            )
        },
    ) { padding ->
        val snapshot = stats
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(t("common_error_prefix", error), color = MaterialTheme.colorScheme.error)
                }

                snapshot == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                snapshot.totalManga == 0L -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(t("stats_empty"))
                }

                else -> StatsContent(snapshot)
            }
        }
    }
}

@Composable
private fun StatsContent(stats: StatsRepository.LibraryStats) {
    val maxCategoryCount = maxOf(
        stats.mangaWithNoCategory,
        stats.mangaPerCategory.maxOfOrNull { it.mangaCount } ?: 0L,
    )

    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        // ── Overview ────────────────────────────────────────────────────
        item { SectionHeader(t("stats_overview")) }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatCard("${stats.totalManga}", t("stats_in_library"), Modifier.weight(1f))
                StatCard("${stats.totalReadChapters}", t("stats_read"), Modifier.weight(1f))
                StatCard("${stats.totalDownloadedChapters}", t("stats_downloaded"), Modifier.weight(1f))
                StatCard("${stats.categoryCount}", t("stats_categories"), Modifier.weight(1f))
            }
        }
        item {
            Text(
                t("stats_avg_chapters", "%.1f".format(stats.averageChaptersPerManga)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // ── Recent activity ─────────────────────────────────────────────
        item { SectionHeader(t("stats_recent_activity")) }
        item {
            StatBarRow(
                label = t("stats_read_7"),
                value = stats.readChaptersLast7Days,
                max = stats.readChaptersLast30Days,
            )
        }
        item {
            StatBarRow(
                label = t("stats_read_30"),
                value = stats.readChaptersLast30Days,
                max = stats.readChaptersLast30Days,
            )
        }

        // ── Manga by source ─────────────────────────────────────────────
        if (stats.mangaPerSource.isNotEmpty()) {
            item { SectionHeader(t("stats_manga_by_source")) }
            val maxSourceManga = stats.mangaPerSource.maxOf { it.mangaCount }
            items(stats.mangaPerSource, key = { "manga_${it.extensionName}" }) { row ->
                StatBarRow(row.extensionName, row.mangaCount, maxSourceManga)
            }
        }

        // ── Read chapters by source ─────────────────────────────────────
        if (stats.readChaptersPerSource.isNotEmpty()) {
            item { SectionHeader(t("stats_read_by_source")) }
            val maxSourceRead = stats.readChaptersPerSource.maxOf { it.chapterCount }
            items(stats.readChaptersPerSource, key = { "read_${it.extensionName}" }) { row ->
                StatBarRow(row.extensionName, row.chapterCount, maxSourceRead)
            }
        }

        // ── Categories ──────────────────────────────────────────────────
        item { SectionHeader(t("stats_categories")) }
        items(stats.mangaPerCategory, key = { "cat_${it.id}" }) { row ->
            StatBarRow(row.name, row.mangaCount, maxCategoryCount)
        }
        item { StatBarRow(t("stats_default"), stats.mangaWithNoCategory, maxCategoryCount) }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatBarRow(label: String, value: Long, max: Long) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$value",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { if (max > 0) value.toFloat() / max else 0f },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}
