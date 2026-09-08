package mihon.desktop.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import mihon.desktop.loader.prefs.AppPreferences
import androidx.compose.ui.unit.dp

/**
 * More tab: menu items for settings, downloads, extensions, etc.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenExtensions: () -> Unit,
    onSearchGlobally: () -> Unit,
    onOpenNotifications: () -> Unit,
) {
    var downloadedOnly by remember {
        mutableStateOf(AppPreferences.getBoolean(AppPreferences.KEY_DOWNLOADED_ONLY, false))
    }
    var incognitoMode by remember {
        mutableStateOf(AppPreferences.getBoolean(AppPreferences.KEY_INCOGNITO_MODE, false))
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("More") })

        // ── Toggles ────────────────────────────────────────────────
        ListItem(
            headlineContent = { Text("Downloaded only") },
            supportingContent = { Text("Only show downloaded manga in library") },
            trailingContent = {
                Switch(checked = downloadedOnly, onCheckedChange = {
                    downloadedOnly = it
                    AppPreferences.setBoolean(AppPreferences.KEY_DOWNLOADED_ONLY, it)
                })
            },
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text("Incognito mode") },
            supportingContent = { Text("Don't record reading history") },
            trailingContent = {
                Switch(checked = incognitoMode, onCheckedChange = {
                    incognitoMode = it
                    AppPreferences.setBoolean(AppPreferences.KEY_INCOGNITO_MODE, it)
                })
            },
        )
        HorizontalDivider()

        // ── Menu items ─────────────────────────────────────────────
        MoreMenuItem(
            icon = Icons.Filled.Search,
            title = "Global search",
            onClick = onSearchGlobally,
        )
        MoreMenuItem(
            icon = Icons.Filled.Notifications,
            title = "Notifications",
            onClick = onOpenNotifications,
        )
        MoreMenuItem(
            icon = Icons.Filled.Storage,
            title = "Downloads",
            onClick = onOpenDownloads,
        )
        MoreMenuItem(
            icon = Icons.Filled.Apps,
            title = "Extensions",
            onClick = onOpenExtensions,
        )
        MoreMenuItem(
            icon = Icons.Filled.Settings,
            title = "Settings",
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun MoreMenuItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
}
