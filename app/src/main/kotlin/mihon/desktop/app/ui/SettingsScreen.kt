package mihon.desktop.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mihon.desktop.loader.backup.BackupManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings screen with theme, reading direction, backup/restore, and update interval.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onUpdateIntervalChanged: (Long) -> Unit,
    currentIntervalMinutes: Long,
    currentTheme: String = "system",
    onThemeChanged: (String) -> Unit = {},
    currentReadingDirection: String = "ltr",
    onReadingDirectionChanged: (String) -> Unit = {},
    onManageExtensions: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val backupManager = remember { BackupManager() }

    var backupInProgress by remember { mutableStateOf(false) }
    var importInProgress by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<String?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var backups by remember { mutableStateOf(backupManager.listBackups()) }
    var intervalMinutes by remember { mutableFloatStateOf(currentIntervalMinutes.toFloat()) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showDirectionDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            // Appearance section
            item {
                Text(
                    "Appearance",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item {
                val themeLabel = when (currentTheme) {
                    "dark" -> "Dark"
                    "light" -> "Light"
                    else -> "System default"
                }
                ListItem(
                    headlineContent = { Text("Theme") },
                    supportingContent = { Text(themeLabel) },
                    leadingContent = { Icon(Icons.Filled.DarkMode, contentDescription = null) },
                    modifier = Modifier.clickable { showThemeDialog = true },
                )
            }
            item {
                val dirLabel = when (currentReadingDirection) {
                    "rtl" -> "Right to left (manga)"
                    else -> "Left to right"
                }
                ListItem(
                    headlineContent = { Text("Reading direction") },
                    supportingContent = { Text(dirLabel) },
                    leadingContent = { Icon(Icons.Filled.TextFields, contentDescription = null) },
                    modifier = Modifier.clickable { showDirectionDialog = true },
                )
            }

            // Background updates section
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Background Updates",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Check interval: ${intervalMinutes.toInt()} minutes") },
                    supportingContent = {
                        Column {
                            Slider(
                                value = intervalMinutes,
                                onValueChange = { intervalMinutes = it },
                                onValueChangeFinished = {
                                    onUpdateIntervalChanged(intervalMinutes.toLong())
                                },
                                valueRange = 15f..240f,
                                steps = 14,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            androidx.compose.foundation.layout.Row(
                                Modifier.fillMaxWidth(),
                            ) {
                                Text("15 min", style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.weight(1f))
                                Text("4 hours", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                )
            }

            // Extensions section
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Extensions",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Manage extensions") },
                    supportingContent = { Text("View installed extensions, uninstall") },
                    leadingContent = { Icon(Icons.Filled.Extension, contentDescription = null) },
                    modifier = Modifier.clickable { onManageExtensions() },
                )
            }

            // Storage section
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Storage",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item {
                val downloadDir = java.io.File(System.getProperty("user.home"), ".mihon-desktop/downloads")
                val cacheDir = java.io.File(System.getProperty("user.home"), ".mihon-desktop/extension-cache")
                val imageCacheDir = java.io.File(System.getProperty("user.home"), ".mihon-desktop/image-cache")
                val downloadSize = if (downloadDir.exists()) downloadDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
                val cacheSize = if (cacheDir.exists()) cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
                val imageSize = if (imageCacheDir.exists()) imageCacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
                ListItem(
                    headlineContent = { Text("Downloads") },
                    supportingContent = { Text(formatBytes(downloadSize)) },
                )
                ListItem(
                    headlineContent = { Text("Extension cache") },
                    supportingContent = { Text(formatBytes(cacheSize)) },
                )
                ListItem(
                    headlineContent = { Text("Image cache") },
                    supportingContent = { Text(formatBytes(imageSize)) },
                )
                ListItem(
                    headlineContent = { Text("Total") },
                    supportingContent = { Text(formatBytes(downloadSize + cacheSize + imageSize)) },
                )
            }

            // Backup section
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Backup & Restore",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Create backup") },
                    supportingContent = { Text("Export library and reading progress to JSON") },
                    leadingContent = { Icon(Icons.Filled.Backup, contentDescription = null) },
                    modifier = Modifier.clickable(enabled = !backupInProgress) {
                        scope.launch {
                            backupInProgress = true
                            runCatching { backupManager.exportBackup() }
                                .onSuccess { backups = backupManager.listBackups() }
                                .onFailure { importResult = "Backup failed: ${it.message}" }
                            backupInProgress = false
                        }
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Restore from backup") },
                    supportingContent = { Text("Import library from a previous backup") },
                    leadingContent = { Icon(Icons.Filled.Restore, contentDescription = null) },
                    modifier = Modifier.clickable(enabled = !importInProgress) {
                        showImportDialog = true
                    },
                )
            }

            if (importResult != null) {
                item {
                    ListItem(
                        headlineContent = {
                            Text(
                                importResult ?: "",
                                color = if (importResult!!.startsWith("Imported"))
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null)
                        },
                    )
                }
            }

            // List existing backups
            if (backups.isNotEmpty()) {
                item {
                    Text(
                        "Available backups",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(backups) { file ->
                    val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(Date(file.lastModified()))
                    val sizeKb = file.length() / 1024
                    ListItem(
                        headlineContent = { Text(file.name) },
                        supportingContent = { Text("$date · ${sizeKb} KB") },
                        modifier = Modifier.clickable {
                            scope.launch {
                                importInProgress = true
                                runCatching { backupManager.importBackup(file) }
                                    .onSuccess { count -> importResult = "Imported $count manga entries" }
                                    .onFailure { err -> importResult = "Import failed: ${err.message}" }
                                importInProgress = false
                            }
                        },
                    )
                }
            }
        }
    }

    // Theme dialog
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Theme") },
            text = {
                Column {
                    listOf(
                        "system" to "System default",
                        "light" to "Light",
                        "dark" to "Dark",
                    ).forEach { (value, label) ->
                        Text(
                            label,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                                .clickable {
                                    onThemeChanged(value)
                                    showThemeDialog = false
                                },
                            color = if (currentTheme == value) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("Cancel") }
            },
        )
    }

    // Reading direction dialog
    if (showDirectionDialog) {
        AlertDialog(
            onDismissRequest = { showDirectionDialog = false },
            title = { Text("Reading direction") },
            text = {
                Column {
                    listOf(
                        "ltr" to "Left to right",
                        "rtl" to "Right to left (manga)",
                    ).forEach { (value, label) ->
                        Text(
                            label,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                                .clickable {
                                    onReadingDirectionChanged(value)
                                    showDirectionDialog = false
                                },
                            color = if (currentReadingDirection == value) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDirectionDialog = false }) { Text("Cancel") }
            },
        )
    }

    // Import dialog
    if (showImportDialog) {
        val availableBackups = backupManager.listBackups()
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Select a backup") },
            text = {
                if (availableBackups.isEmpty()) {
                    Text("No backups found")
                } else {
                    Column {
                        for (file in availableBackups) {
                            val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                                .format(Date(file.lastModified()))
                            Text(
                                "${file.name}\n$date",
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                    .clickable {
                                        showImportDialog = false
                                        scope.launch {
                                            importInProgress = true
                                            runCatching { backupManager.importBackup(file) }
                                                .onSuccess { count -> importResult = "Imported $count manga entries" }
                                                .onFailure { err -> importResult = "Import failed: ${err.message}" }
                                            importInProgress = false
                                        }
                                    },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Cancel") }
            },
        )
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
