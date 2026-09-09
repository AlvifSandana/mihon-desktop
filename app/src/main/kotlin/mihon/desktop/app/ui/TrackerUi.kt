package mihon.desktop.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mihon.desktop.loader.tracker.CredentialStyle
import mihon.desktop.loader.tracker.RemoteManga
import mihon.desktop.loader.tracker.Tracker
import mihon.desktop.app.i18n.Strings
import mihon.desktop.app.i18n.t
import java.awt.Desktop
import java.net.URI

/** "anilist" -> "AniList", "myanimelist" -> "MyAnimeList". */
internal fun prettyTrackerName(name: String): String = when (name) {
    "anilist" -> "AniList"
    "myanimelist" -> "MyAnimeList"
    else -> name.replaceFirstChar { it.uppercase() }
}

/** Opens [url] in the system browser; false when unsupported/failed. */
internal fun openInBrowser(url: String): Boolean = runCatching {
    if (!Desktop.isDesktopSupported()) return false
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.BROWSE)) return false
    desktop.browse(URI(url))
    true
}.getOrDefault(false)

/**
 * Token-paste login dialog shared by all trackers: shows the authorize URL
 * (with an "open in browser" button), a paste field for whatever the flow
 * yields (AniList: access token; MAL: redirect URL or auth code), and
 * completes via [Tracker.login].
 */
@Composable
internal fun TrackerLoginDialog(
    tracker: Tracker,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // One authorize URL per dialog: MAL's PKCE verifier is bound to it.
    val loginRequest = remember { tracker.prepareLogin() }
    var paste by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var browserFailed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(t("track_login_title", prettyTrackerName(tracker.name))) },
        text = {
            Column {
                Text(loginRequest.instructions, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    loginRequest.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = {
                    if (!openInBrowser(loginRequest.url)) browserFailed = true
                }) {
                    Text(if (browserFailed) t("track_browser_failed") else t("track_open_browser"))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = paste,
                    onValueChange = { paste = it },
                    label = {
                        Text(
                            when (loginRequest.credentialStyle) {
                                CredentialStyle.ACCESS_TOKEN -> t("track_field_access_token")
                                CredentialStyle.AUTH_CODE -> t("track_field_auth_code")
                            },
                        )
                    },
                    singleLine = false,
                    enabled = !busy,
                    isError = error != null,
                    supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                )
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && paste.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        runCatching { tracker.login(paste) }
                            .onSuccess { onSuccess() }
                            .onFailure { error = it.message ?: it.toString() }
                        busy = false
                    }
                },
            ) { Text(t("action_log_in")) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(t("common_cancel")) }
        },
    )
}

/**
 * Remote search dialog for binding a manga: query pre-filled with the local
 * title, results list, click a hit to bind via [onBind] (which performs the
 * tracker bind + local upsert and throws on failure to surface an error).
 */
@Composable
internal fun TrackSearchDialog(
    tracker: Tracker,
    initialQuery: String,
    onDismiss: () -> Unit,
    onBind: suspend (RemoteManga) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<RemoteManga>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var binding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!searching && !binding) onDismiss() },
        title = { Text(t("track_on_title", prettyTrackerName(tracker.name))) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(t("field_title")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        enabled = !searching && !binding,
                    )
                    TextButton(
                        enabled = !searching && !binding && query.isNotBlank(),
                        onClick = {
                            scope.launch {
                                searching = true
                                error = null
                                runCatching { tracker.search(query) }
                                    .onSuccess {
                                        results = it
                                        if (it.isEmpty()) error = Strings.get("track_no_results")
                                    }
                                    .onFailure { error = it.message ?: it.toString() }
                                searching = false
                            }
                        },
                    ) {
                        if (searching) {
                            CircularProgressIndicator(Modifier.size(16.dp))
                        } else {
                            Text(t("common_search"))
                        }
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(results, key = { it.remoteId }) { hit ->
                        ListItem(
                            headlineContent = { Text(hit.title) },
                            supportingContent = {
                                val details = listOfNotNull(
                                    if (hit.totalChapters > 0) t("track_chapters_count", hit.totalChapters) else null,
                                    hit.score?.let { "★ $it" },
                                    hit.publishingStatus.takeIf { it.isNotBlank() },
                                )
                                if (details.isNotEmpty()) Text(details.joinToString("  ·  "))
                            },
                            modifier = Modifier.clickable(enabled = !binding) {
                                scope.launch {
                                    binding = true
                                    error = null
                                    runCatching { onBind(hit) }
                                        .onSuccess { onDismiss() }
                                        .onFailure { error = it.message ?: it.toString() }
                                    binding = false
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !searching && !binding, onClick = onDismiss) { Text(t("common_cancel")) }
        },
    )
}
