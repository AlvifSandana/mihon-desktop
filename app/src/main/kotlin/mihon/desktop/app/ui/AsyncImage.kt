package mihon.desktop.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

/**
 * Loads and decodes an image lazily, off the UI thread, keyed on [key] (typically the URL).
 * Shows a spinner while loading, nothing on failure.
 */
@Composable
fun AsyncImage(key: Any?, modifier: Modifier = Modifier, load: suspend () -> ByteArray?) {
    var bitmap by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(key) { mutableStateOf(false) }

    LaunchedEffect(key) {
        bitmap = null
        failed = false
        val bytes = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() }
        bitmap = bytes?.let { data ->
            withContext(Dispatchers.Default) {
                runCatching { SkiaImage.makeFromEncoded(data).toComposeImageBitmap() }.getOrNull()
            }
        }
        failed = bitmap == null
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else if (!failed) {
            CircularProgressIndicator()
        }
    }
}
