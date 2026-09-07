package mihon.desktop.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.launch
import mihon.desktop.loader.ExtensionLoader
import mihon.desktop.loader.catalog.CatalogClient
import mihon.desktop.loader.catalog.CatalogExtension
import mihon.desktop.loader.catalog.ExtensionDownloader
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

private val extensionCacheDir = File(System.getProperty("user.home"), ".mihon-desktop/extension-cache")

/** Browse and install extensions from the keiyoushi catalog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(onSourceSelected: (CatalogExtension, Source) -> Unit) {
    val scope = rememberCoroutineScope()
    val client = remember { Injekt.get<NetworkHelper>().client }
    val catalogClient = remember { CatalogClient(client) }
    val downloader = remember { ExtensionDownloader(client, extensionCacheDir) }

    var extensions by remember { mutableStateOf<List<CatalogExtension>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var installingPackage by remember { mutableStateOf<String?>(null) }
    var sourcePicker by remember { mutableStateOf<Pair<CatalogExtension, List<Source>>?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        error = null
        runCatching { catalogClient.fetchCatalog() }
            .onSuccess { extensions = it.sortedBy { ext -> ext.name.lowercase() } }
            .onFailure { error = it.message ?: it.toString() }
        loading = false
    }

    val filtered = remember(extensions, query) {
        if (query.isBlank()) {
            extensions
        } else {
            extensions.filter {
                it.name.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
            }
        }
    }

    fun install(extension: CatalogExtension) {
        scope.launch {
            installingPackage = extension.packageName
            error = null
            runCatching {
                val jar = downloader.download(extension)
                ExtensionLoader.load(jar).sources
            }.onSuccess { sources ->
                when {
                    sources.isEmpty() -> error = "${extension.name} loaded but declared no sources"
                    sources.size == 1 -> onSourceSelected(extension, sources.first())
                    else -> sourcePicker = extension to sources
                }
            }.onFailure {
                error = "Failed to install ${extension.name}: ${it.message ?: it}"
            }
            installingPackage = null
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Extensions") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                placeholder = { Text("Search extensions") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Error: $error", color = MaterialTheme.colorScheme.error)
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(filtered, key = { it.packageName }) { extension ->
                        val busy = installingPackage != null
                        ListItem(
                            headlineContent = { Text(extension.name) },
                            supportingContent = {
                                Text(
                                    "${extension.versionName} · " +
                                        extension.sources.joinToString(", ") { it.lang }.ifBlank { "?" },
                                )
                            },
                            trailingContent = {
                                if (installingPackage == extension.packageName) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                }
                            },
                            modifier = Modifier.clickable(enabled = !busy) { install(extension) },
                        )
                    }
                }
            }
        }
    }

    sourcePicker?.let { (extension, sources) ->
        AlertDialog(
            onDismissRequest = { sourcePicker = null },
            title = { Text("Choose a source (${extension.name})") },
            text = {
                Column {
                    sources.forEach { source ->
                        Text(
                            "${source.name} (${source.lang})",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                .clickable {
                                    sourcePicker = null
                                    onSourceSelected(extension, source)
                                },
                        )
                    }
                }
            },
            confirmButton = {},
        )
    }
}
