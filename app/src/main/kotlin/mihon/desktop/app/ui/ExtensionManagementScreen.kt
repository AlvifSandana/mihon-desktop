package mihon.desktop.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
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
import mihon.desktop.loader.ExtensionLoader
import mihon.desktop.loader.catalog.ExtensionUpdateManager
import mihon.desktop.loader.log.Logger
import mihon.desktop.app.i18n.Strings
import mihon.desktop.app.i18n.t

private const val TAG = "ExtensionManagementScreen"

/**
 * Manage installed extensions: shows each cached jar (described by its manifest,
 * so package name and version are accurate regardless of the file name), offers a
 * per-extension "Update" button when the catalog has a newer version, "Update all"
 * when at least one update is pending, plus uninstall and rescan. Applied updates
 * take effect without an app restart -- the loader re-loads the new jar on the next
 * screen entry; open screens keep their already-loaded sources until revisited.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionManagementScreen(
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val updateManager = remember { ExtensionUpdateManager.default }
    val pendingUpdates by updateManager.pendingUpdates.collectAsState()

    var installedExtensions by remember { mutableStateOf(listOf<ExtensionUiState>()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var updatingPackage by remember { mutableStateOf<String?>(null) }
    var updateProgress by remember { mutableStateOf<Float?>(null) }
    var updateAllProgress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showUninstallDialog by remember { mutableStateOf<ExtensionUiState?>(null) }

    suspend fun rescan(checkCatalog: Boolean) {
        if (checkCatalog) {
            // Re-check against the remote catalog; failure just means no update info.
            runCatching { updateManager.checkForUpdates() }
                .onFailure { Logger.w(TAG, "Extension update check failed: ${it.message}") }
        }
        installedExtensions = updateManager.discoverInstalled().map { it.toUiState() }
    }

    LaunchedEffect(Unit) {
        loading = true
        rescan(checkCatalog = true)
        loading = false
    }

    fun updateExtension(update: mihon.desktop.loader.catalog.ExtensionUpdate) {
        scope.launch {
            updatingPackage = update.packageName
            updateProgress = null
            error = null
            runCatching {
                updateManager.applyUpdate(update) { bytes, total ->
                    updateProgress = if (total != null && total > 0) bytes.toFloat() / total else null
                }
            }.onFailure {
                Logger.e(TAG, "Failed to update ${update.packageName}: ${it.message}", it)
                error = Strings.get("error_update_failed", update.installed.name, it.message ?: it)
            }
            updatingPackage = null
            updateProgress = null
            rescan(checkCatalog = false)
        }
    }

    fun updateAll() {
        scope.launch {
            error = null
            val snapshot = pendingUpdates
            val packages = snapshot.map { it.packageName }
            // Single loop in the manager (per-package lock, reactive pending
            // drain); failures are collected here and surfaced joined.
            val results = updateManager.applyAllPending { packageName, bytes, total ->
                updatingPackage = packageName
                updateProgress = if (total != null && total > 0) bytes.toFloat() / total else null
                val index = packages.indexOf(packageName)
                if (index >= 0) {
                    updateAllProgress = Strings.get("extmgmt_updating", index + 1, snapshot.size, snapshot[index].installed.name)
                }
            }
            val failures = results.mapNotNull { (update, result) ->
                result.exceptionOrNull()?.let {
                    Logger.e(TAG, "Failed to update ${update.packageName}: ${it.message}", it)
                    Strings.get("error_update_failed", update.installed.name, it.message ?: it)
                }
            }
            if (failures.isNotEmpty()) {
                error = failures.joinToString("\n")
            }
            updatingPackage = null
            updateProgress = null
            updateAllProgress = null
            rescan(checkCatalog = false)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("extmgmt_title")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("common_back"))
                    }
                },
                actions = {
                    if (pendingUpdates.isNotEmpty()) {
                        TextButton(
                            onClick = { updateAll() },
                            enabled = updatingPackage == null,
                        ) {
                            Text(t("action_update_all", pendingUpdates.size))
                        }
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                refreshing = true
                                rescan(checkCatalog = true)
                                refreshing = false
                            }
                        },
                        enabled = !refreshing && !loading,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = t("action_refresh"))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                updateAllProgress?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    installedExtensions.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(t("extmgmt_empty"))
                    }
                    else -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(installedExtensions, key = { it.packageName }) { ext ->
                            val pendingUpdate = pendingUpdates.firstOrNull { it.packageName == ext.packageName }
                            Card(modifier = Modifier.fillMaxWidth()) {
                                ListItem(
                                    headlineContent = { Text(ext.name) },
                                    supportingContent = {
                                        Column {
                                            Text("v${ext.version}")
                                            if (pendingUpdate != null) {
                                                Text(
                                                    t("extmgmt_update_available", pendingUpdate.newVersionName),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                            Text(
                                                ext.packageName,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                    },
                                    trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            when {
                                                updatingPackage == ext.packageName -> {
                                                    updateProgress?.let { fraction ->
                                                        LinearProgressIndicator(
                                                            progress = { fraction.coerceIn(0f, 1f) },
                                                            modifier = Modifier.width(72.dp),
                                                        )
                                                    } ?: CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                                }

                                                pendingUpdate != null -> {
                                                    TextButton(
                                                        onClick = { updateExtension(pendingUpdate) },
                                                        enabled = updatingPackage == null,
                                                    ) {
                                                        Text(t("common_update"))
                                                    }
                                                }
                                            }
                                            IconButton(
                                                onClick = { showUninstallDialog = ext },
                                                enabled = updatingPackage == null,
                                            ) {
                                                Icon(
                                                    Icons.Filled.Delete,
                                                    contentDescription = t("action_uninstall"),
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (refreshing) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    showUninstallDialog?.let { ext ->
        AlertDialog(
            onDismissRequest = { showUninstallDialog = null },
            title = { Text(t("extmgmt_uninstall_title", ext.name)) },
            text = { Text(t("extmgmt_uninstall_text")) },
            confirmButton = {
                TextButton(onClick = {
                scope.launch {
                    val jarFile = java.io.File(ExtensionLoader.extensionCacheDir, ext.jarFileName)
                        // Drop the cached loader + sidecar first: on Windows the
                        // open jar file handle makes delete() silently fail, and
                        // a stale sidecar would block a re-placed jar.
                        mihon.desktop.loader.ExtensionLoader.removeCachedJar(jarFile)
                        if (!jarFile.delete()) {
                            Logger.e(TAG, "Failed to delete $jarFile")
                        }
                        // Re-scan instead of filtering locally, so the row list
                        // reflects the actual cache contents.
                        installedExtensions = updateManager.discoverInstalled().map { it.toUiState() }
                    }
                    showUninstallDialog = null
                }) {
                    Text(t("action_uninstall"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallDialog = null }) {
                    Text(t("common_cancel"))
                }
            },
        )
    }
}

/** Row model for the installed-extension list. */
private data class ExtensionUiState(
    val name: String,
    val packageName: String,
    val version: String,
    val jarFileName: String,
)

private fun mihon.desktop.loader.catalog.InstalledExtension.toUiState() = ExtensionUiState(
    name = name,
    packageName = packageName,
    version = versionName ?: "unknown",
    jarFileName = jarFile.name,
)
