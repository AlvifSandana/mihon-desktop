package mihon.desktop.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mihon.desktop.app.i18n.t
import java.util.concurrent.ConcurrentHashMap

/**
 * Edits a [ConfigurableSource]'s persisted preferences.
 *
 * Extensions declare their settings UI through `setupPreferenceScreen` using the
 * Android androidx.preference framework, which has no desktop counterpart. Rather
 * than drive that, this screen edits the [android.content.SharedPreferences] store
 * the source itself reads at runtime ([ConfigurableSource.getSourcePreferences],
 * backed by platform-compat's file-based implementation). Entries are rendered
 * generically by stored value type; unknown types are skipped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceSettingsScreen(
    source: Source,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val configurable = source as? ConfigurableSource

    var entries by remember { mutableStateOf<List<Pair<String, Any?>>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var writeError by remember { mutableStateOf<String?>(null) }

    // Serialize disk writes and drop stale ones: rapid edits (e.g. typing in a
    // text field) queue several writes per key, and only the latest must land.
    val writeMutex = remember { Mutex() }
    val writeVersions = remember { ConcurrentHashMap<String, Long>() }

    LaunchedEffect(source) {
        if (configurable == null) return@LaunchedEffect
        // getSourcePreferences() reads the properties file on first access --
        // keep it off the UI thread.
        entries = withContext(Dispatchers.IO) {
            configurable.getSourcePreferences().getAll()
                .entries
                .sortedBy { it.key }
                .map { it.key to it.value }
        }
        loaded = true
    }

    fun put(key: String, value: Any?) {
        val prefs = configurable?.getSourcePreferences() ?: return
        val version = writeVersions.merge(key, 1L, Long::plus)
        scope.launch(Dispatchers.IO) {
            writeMutex.withLock {
                // A newer edit for this key superseded this one while it queued.
                if (writeVersions[key] != version) return@withLock
                runCatching {
                    val editor = prefs.edit()
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is String -> editor.putString(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toMutableSet())
                    }
                    editor.apply()
                }.onSuccess {
                    // Clear any stale error from a previous failed write.
                    writeError = null
                }.onFailure { writeError = it.message ?: it.toString() }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(source.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("common_back")) }
                },
            )
        },
    ) { padding ->
        when {
            configurable == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(t("source_settings_none"))
            }
            !loaded -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            entries.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t("source_settings_empty"))
                    Text(
                        t("source_settings_empty_hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                if (writeError != null) {
                    item {
                        Text(
                            t("common_error_prefix", writeError),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(entries, key = { it.first }) { (key, value) ->
                    when (value) {
                        is Boolean -> SwitchRow(key, value) { put(key, it) }
                        is String -> TextRow(key, value) { put(key, it) }
                        is Int -> NumberRow(key, value, String::toIntOrNull) { put(key, it) }
                        is Long -> NumberRow(key, value, String::toLongOrNull) { put(key, it) }
                        is Set<*> -> SetRow(key, value.filterIsInstance<String>().toSet()) { put(key, it) }
                        // Unknown preference type -- skip gracefully.
                        else -> {}
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(key: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var checked by remember(key) { mutableStateOf(initial) }
    ListItem(
        headlineContent = { Text(key) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = { checked = it; onChange(it) })
        },
    )
}

@Composable
private fun TextRow(key: String, initial: String, onChange: (String) -> Unit) {
    var text by remember(key) { mutableStateOf(initial) }
    ListItem(
        headlineContent = { Text(key) },
        supportingContent = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    onChange(it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(t("field_text")) },
                singleLine = true,
            )
        },
    )
}

@Composable
private fun <T> NumberRow(key: String, initial: Number, parse: (String) -> T?, onChange: (T) -> Unit) {
    var text by remember(key) { mutableStateOf(initial.toString()) }
    ListItem(
        headlineContent = { Text(key) },
        supportingContent = {
            OutlinedTextField(
                value = text,
                onValueChange = { newText ->
                    text = newText
                    parse(newText)?.let(onChange)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(t("field_number")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
    )
}

@Composable
private fun SetRow(key: String, initial: Set<String>, onChange: (Set<String>) -> Unit) {
    var text by remember(key) { mutableStateOf(initial.joinToString(", ")) }
    ListItem(
        headlineContent = { Text(key) },
        supportingContent = {
            OutlinedTextField(
                value = text,
                onValueChange = { newText ->
                    text = newText
                    onChange(newText.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(t("field_comma_values")) },
                singleLine = true,
            )
        },
    )
}
