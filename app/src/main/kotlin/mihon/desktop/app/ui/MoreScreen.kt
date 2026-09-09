package mihon.desktop.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BarChart
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
import mihon.desktop.app.i18n.t
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
    onOpenCategories: () -> Unit,
    onOpenStats: () -> Unit,
) {
    var downloadedOnly by remember {
        mutableStateOf(AppPreferences.getBoolean(AppPreferences.KEY_DOWNLOADED_ONLY, false))
    }
    var incognitoMode by remember {
        mutableStateOf(AppPreferences.getBoolean(AppPreferences.KEY_INCOGNITO_MODE, false))
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(t("more_title")) })

        // ── Toggles ────────────────────────────────────────────────
        ListItem(
            headlineContent = { Text(t("downloaded_only")) },
            supportingContent = { Text(t("downloaded_only_hint")) },
            trailingContent = {
                Switch(checked = downloadedOnly, onCheckedChange = {
                    downloadedOnly = it
                    AppPreferences.setBoolean(AppPreferences.KEY_DOWNLOADED_ONLY, it)
                })
            },
        )
        HorizontalDivider()

        ListItem(
            headlineContent = { Text(t("incognito_mode")) },
            supportingContent = { Text(t("incognito_hint")) },
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
            title = t("menu_global_search"),
            onClick = onSearchGlobally,
        )
        MoreMenuItem(
            icon = Icons.Filled.Notifications,
            title = t("menu_notifications"),
            onClick = onOpenNotifications,
        )
        MoreMenuItem(
            icon = Icons.Filled.Storage,
            title = t("menu_downloads"),
            onClick = onOpenDownloads,
        )
        MoreMenuItem(
            icon = Icons.AutoMirrored.Filled.Label,
            title = t("menu_categories"),
            onClick = onOpenCategories,
        )
        MoreMenuItem(
            icon = Icons.Filled.BarChart,
            title = t("menu_statistics"),
            onClick = onOpenStats,
        )
        MoreMenuItem(
            icon = Icons.Filled.Apps,
            title = t("menu_extensions"),
            onClick = onOpenExtensions,
        )
        MoreMenuItem(
            icon = Icons.Filled.Settings,
            title = t("menu_settings"),
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
