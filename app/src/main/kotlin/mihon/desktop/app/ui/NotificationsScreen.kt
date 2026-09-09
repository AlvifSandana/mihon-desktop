package mihon.desktop.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mihon.desktop.loader.library.NotificationManager
import mihon.desktop.app.i18n.Strings
import mihon.desktop.app.i18n.t
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen to display all notifications (e.g., background update results).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
) {
    var notifications by remember { mutableStateOf(NotificationManager.notifications) }

    // Stay live while the screen is open: any notify() from background work
    // (library updates, downloads) refreshes the list immediately.
    DisposableEffect(Unit) {
        val listener = NotificationManager.NotificationListener {
            notifications = NotificationManager.notifications
        }
        NotificationManager.addListener(listener)
        onDispose { NotificationManager.removeListener(listener) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("notifications_title")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("common_back"))
                    }
                },
                actions = {
                    if (notifications.isNotEmpty()) {
                        IconButton(onClick = {
                            NotificationManager.clear()
                            notifications = emptyList()
                        }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = t("action_clear_all"))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (notifications.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Notifications,
                            contentDescription = null,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Text(t("notifications_empty"))
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(notifications, key = { it.id }) { notification ->
                        // Subscribe each item to locale changes: key-based
                        // notifications re-resolve on a live language swap.
                        Strings.languageTick.intValue
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                                .clickable {
                                    NotificationManager.markAsRead(notification.id)
                                    notifications = NotificationManager.notifications
                                },
                        ) {
                            ListItem(
                                headlineContent = { Text(NotificationManager.displayTitle(notification)) },
                                supportingContent = {
                                    Column {
                                        Text(NotificationManager.displayMessage(notification))
                                        val date = SimpleDateFormat("HH:mm:ss", Locale.US)
                                            .format(Date(notification.timestamp))
                                        Text(
                                            date,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = if (notification.read) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        else MaterialTheme.colorScheme.primary,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
