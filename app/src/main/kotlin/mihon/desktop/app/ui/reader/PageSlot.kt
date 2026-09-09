package mihon.desktop.app.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import mihon.desktop.app.i18n.t
import mihon.desktop.app.ui.ZoomableImage

/**
 * One half (or the whole) of the paged display: preloaded bitmap with
 * zoom/pan, a spinner while loading, or an error placeholder with a Retry
 * button when the page failed to load.
 */
@Composable
internal fun PageSlot(
    index: Int,
    bitmap: ImageBitmap?,
    failed: Boolean,
    zoomKey: Any?,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> ZoomableImage(
                modifier = Modifier.fillMaxSize(),
                key = zoomKey,
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    t("reader_page_failed", index + 1),
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Text(t("common_retry"))
                }
            }
            else -> CircularProgressIndicator()
        }
    }
}
