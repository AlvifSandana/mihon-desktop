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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.desktop.loader.ExtensionLoader
import mihon.desktop.loader.catalog.CatalogClient
import java.io.File

private val extensionCacheDir = File(System.getProperty("user.home"), ".mihon-desktop/extension-cache")

private data class InstalledExtension(
    val name: String,
    val packageName: String,
    val version: String,
    val jarFile: File,
)

/** Scans the extension cache and loads each jar. Must run off the UI thread. */
private fun scanInstalledExtensions(): List<InstalledExtension> {
    val jars = extensionCacheDir.listFiles()?.filter { it.isFile && it.extension == "jar" } ?: emptyList()
    return jars.mapNotNull { jar ->
        val ext = runCatching { ExtensionLoader.load(jar) }.getOrNull()
        val sources = ext?.sources ?: emptyList()
        if (sources.isNotEmpty()) {
            InstalledExtension(
                name = sources.firstOrNull()?.name ?: jar.nameWithoutExtension,
                packageName = jar.nameWithoutExtension.substringBeforeLast("-v"),
                version = jar.nameWithoutExtension.substringAfterLast("-v", "unknown"),
                jarFile = jar,
            )
        } else null
    }
}

/**
 * Screen to manage installed extensions.
 * Shows installed extensions with options to update or uninstall.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionManagementScreen(
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var installedExtensions by remember { mutableStateOf(listOf<InstalledExtension>()) }
    var loading by remember { mutableStateOf(true) }
    var updating by remember { mutableStateOf(false) }
    var showUninstallDialog by remember { mutableStateOf<InstalledExtension?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        installedExtensions = withContext(Dispatchers.IO) { scanInstalledExtensions() }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Installed extensions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                updating = true
                                // Re-scan extensions
                                installedExtensions = withContext(Dispatchers.IO) { scanInstalledExtensions() }
                                updating = false
                            }
                        },
                        enabled = !updating,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
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
                installedExtensions.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No extensions installed")
                }
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(installedExtensions, key = { it.packageName }) { ext ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            ListItem(
                                headlineContent = { Text(ext.name) },
                                supportingContent = {
                                    Column {
                                        Text("v${ext.version}")
                                        Text(
                                            ext.packageName,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                },
                                trailingContent = {
                                    Row {
                                        IconButton(onClick = { showUninstallDialog = ext }) {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = "Uninstall",
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

            if (updating) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    showUninstallDialog?.let { ext ->
        AlertDialog(
            onDismissRequest = { showUninstallDialog = null },
            title = { Text("Uninstall ${ext.name}?") },
            text = { Text("This will remove the extension and its cached JAR file.") },
            confirmButton = {
                TextButton(onClick = {
                    ext.jarFile.delete()
                    installedExtensions = installedExtensions.filter { it.packageName != ext.packageName }
                    showUninstallDialog = null
                }) {
                    Text("Uninstall")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallDialog = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}
