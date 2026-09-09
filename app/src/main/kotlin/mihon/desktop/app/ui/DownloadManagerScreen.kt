package mihon.desktop.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mihon.desktop.loader.download.DownloadJob
import mihon.desktop.loader.download.DownloadJobState
import mihon.desktop.loader.download.DownloadManager
import mihon.desktop.loader.download.DownloadQueue
import mihon.desktop.loader.library.DownloadedChapters
import mihon.desktop.app.i18n.t
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live view of the shared [DownloadQueue] plus the downloaded-chapter library
 * on disk.
 *
 * Queue section (reactive via the queue's StateFlows): running jobs with a
 * per-chapter page progress bar, queued jobs with their position, failed jobs
 * with the error and a retry button, and a collapsed completed count. Global
 * controls: pause/resume, cancel all, clear completed.
 *
 * Below it, the on-disk downloads (DB records) with disk usage and deletion,
 * refreshed automatically as queued chapters finish.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagerScreen(
    onBack: () -> Unit,
) {
    val queue = remember { DownloadQueue.shared }
    val jobs by queue.jobs.collectAsState()
    val paused by queue.isPaused.collectAsState()
    val downloadManager = remember { DownloadManager() }
    val scope = rememberCoroutineScope()
    var downloads by remember { mutableStateOf(listOf<DownloadedChapters>()) }
    var loading by remember { mutableStateOf(true) }
    var totalSize by remember { mutableStateOf(0L) }
    var showClearAllDialog by remember { mutableStateOf(false) }

    suspend fun reloadFromDb() {
        downloads = downloadManager.allDownloads()
        totalSize = downloadManager.totalDiskUsage()
    }

    LaunchedEffect(Unit) {
        loading = true
        reloadFromDb()
        loading = false
    }

    // Queue completions write new DB rows -- refresh the on-disk list whenever
    // the number of completed queue jobs changes. Initial value 0 (not -1) so
    // the first emission of an empty queue doesn't trigger a redundant reload
    // on top of the LaunchedEffect(Unit) load above.
    var lastCompletedCount by remember { mutableStateOf(0) }
    LaunchedEffect(jobs) {
        val completed = jobs.count { it.state == DownloadJobState.COMPLETED }
        if (completed != lastCompletedCount) {
            lastCompletedCount = completed
            reloadFromDb()
        }
    }

    val runningJobs = jobs.filter { it.state == DownloadJobState.RUNNING }
    val queuedJobs = jobs.filter { it.state == DownloadJobState.QUEUED }
    val failedJobs = jobs.filter { it.state == DownloadJobState.FAILED }
    val completedCount = jobs.count { it.state == DownloadJobState.COMPLETED }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("downloads_title")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("common_back"))
                    }
                },
                actions = {
                    if (jobs.isNotEmpty()) {
                        IconButton(onClick = { scope.launch { if (paused) queue.resume() else queue.pause() } }) {
                            Icon(
                                if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                contentDescription = if (paused) t("action_resume_queue") else t("action_pause_queue"),
                            )
                        }
                        if (completedCount > 0) {
                            IconButton(onClick = { scope.launch { queue.clearCompleted() } }) {
                                Icon(Icons.Filled.DeleteSweep, contentDescription = t("action_clear_completed"))
                            }
                        }
                        if (downloads.isNotEmpty()) {
                            IconButton(onClick = { showClearAllDialog = true }) {
                                Icon(Icons.Filled.Delete, contentDescription = t("action_delete_all_downloads"))
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            // ── Queue ────────────────────────────────────────────────────
            if (jobs.isNotEmpty()) {
                item(key = "queue-header") {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            t("queue_title"),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { scope.launch { queue.cancelAll() } }) {
                            Text(t("action_cancel_all"))
                        }
                    }
                }

                items(runningJobs, key = { it.id }) { job ->
                    QueueJobCard(
                        job = job,
                        positionLabel = t("queue_downloading"),
                        showProgress = true,
                        onCancel = { scope.launch { queue.cancel(job) } },
                        onRetry = null,
                    )
                }

                itemsIndexed(queuedJobs, key = { _, job -> job.id }) { index, job ->
                    QueueJobCard(
                        job = job,
                        positionLabel = t("queue_queued_position", index + 1),
                        showProgress = false,
                        onCancel = { scope.launch { queue.cancel(job) } },
                        onRetry = null,
                    )
                }

                items(failedJobs, key = { it.id }) { job ->
                    QueueJobCard(
                        job = job,
                        positionLabel = t("queue_failed_attempts", job.attempts),
                        showProgress = false,
                        onCancel = { scope.launch { queue.cancel(job) } },
                        onRetry = { scope.launch { queue.retry(job) } },
                    )
                }

                if (completedCount > 0) {
                    item(key = "queue-completed") {
                        Card(Modifier.fillMaxWidth()) {
                            Text(
                                t("queue_completed_count", completedCount),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }

            // ── On-disk library ──────────────────────────────────────────
            item(key = "disk-usage") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            t("disk_usage"),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            formatBytes(totalSize),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            t("downloads_chapters_count", downloads.size),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (downloads.isEmpty() && jobs.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.Storage,
                                contentDescription = null,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                            Text(t("downloads_empty"))
                        }
                    }
                }
            } else {
                items(downloads, key = { "${it.sourceId}:${it.chapterUrl}" }) { record ->
                    DownloadItem(
                        record = record,
                        onDelete = {
                            scope.launch {
                                downloadManager.deleteChapter(record.sourceId, record.chapterUrl)
                                reloadFromDb()
                            }
                        },
                    )
                }
            }
        }
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text(t("downloads_clear_title")) },
            text = { Text(t("downloads_clear_text")) },
            confirmButton = {
                TextButton(onClick = {
                    showClearAllDialog = false
                    scope.launch {
                        downloadManager.deleteAllDownloads()
                        reloadFromDb()
                    }
                }) {
                    Text(t("action_delete_all"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text(t("common_cancel"))
                }
            },
        )
    }
}

/** One queue entry: title, status/position, page progress (when running), cancel, retry (when failed). */
@Composable
private fun QueueJobCard(
    job: DownloadJob,
    positionLabel: String,
    showProgress: Boolean,
    onCancel: () -> Unit,
    onRetry: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${job.mangaTitle} · ${job.chapterName}",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    positionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (job.state == DownloadJobState.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showProgress && job.pagesTotal > 0) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { job.pagesDone.toFloat() / job.pagesTotal.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        t("common_pages_progress", job.pagesDone, job.pagesTotal),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                job.error?.let { err ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        err,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (onRetry != null) {
                IconButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = t("common_retry"))
                }
            }
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = t("action_cancel"),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun DownloadItem(
    record: DownloadedChapters,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    record.chapterName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${record.pageCount} pages · ${formatBytes(record.pageCount * 100_000L)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    .format(Date(record.downloadedAt))
                Text(
                    date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = t("common_delete"),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
    }
}
