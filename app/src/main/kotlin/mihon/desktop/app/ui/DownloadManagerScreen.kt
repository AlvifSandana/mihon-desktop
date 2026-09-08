package mihon.desktop.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mihon.desktop.loader.download.DownloadManager
import mihon.desktop.loader.library.DownloadedChapters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen to manage all downloaded chapters across the library.
 * Shows disk usage, lists downloads, and allows deletion.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagerScreen(
    onBack: () -> Unit,
) {
    val downloadManager = remember { DownloadManager() }
    val scope = rememberCoroutineScope()
    var downloads by remember { mutableStateOf(listOf<DownloadedChapters>()) }
    var loading by remember { mutableStateOf(true) }
    var totalSize by remember { mutableStateOf(0L) }
    var showClearAllDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        loading = true
        downloads = downloadManager.allDownloads()
        totalSize = downloadManager.totalDiskUsage()
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (downloads.isNotEmpty()) {
                        IconButton(onClick = { showClearAllDialog = true }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear all downloads")
                        }
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
                downloads.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Storage,
                            contentDescription = null,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Text("No downloads")
                    }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    // Disk usage header
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Disk usage",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                formatBytes(totalSize),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "${downloads.size} chapters",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    // Downloads list
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    ) {
                        items(downloads, key = { "${it.sourceId}:${it.chapterUrl}" }) { record ->
                            DownloadItem(
                                record = record,
                                onDelete = {
                                    scope.launch {
                                        downloadManager.deleteChapter(record.sourceId, record.chapterUrl)
                                        downloads = downloadManager.allDownloads()
                                        totalSize = downloadManager.totalDiskUsage()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Clear all downloads?") },
            text = { Text("This will delete all downloaded chapters. This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearAllDialog = false
                    scope.launch {
                        downloadManager.deleteAllDownloads()
                        downloads = emptyList()
                        totalSize = 0L
                    }
                }) {
                    Text("Delete all")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text("Cancel")
                }
            },
        )
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
                    contentDescription = "Delete",
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
