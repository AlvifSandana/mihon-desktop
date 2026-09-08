package mihon.desktop.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Simple error/success notification that auto-dismisses after [durationMs].
 * Shows at the top of the screen.
 */
@Composable
fun ErrorNotification(
    message: String?,
    durationMs: Long = 4000,
    onDismiss: () -> Unit = {},
) {
    if (message == null) return

    LaunchedEffect(message) {
        delay(durationMs)
        onDismiss()
    }

    Box(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Snackbar(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Text(message)
        }
    }
}

/**
 * A dismissible notification bar that shows at the bottom of the screen.
 */
@Composable
fun InfoNotification(
    message: String?,
    durationMs: Long = 3000,
    onDismiss: () -> Unit = {},
) {
    if (message == null) return

    LaunchedEffect(message) {
        delay(durationMs)
        onDismiss()
    }

    Box(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Snackbar(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Text(message)
        }
    }
}
