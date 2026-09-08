package mihon.desktop.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import kotlin.math.abs
import kotlin.math.max

/**
 * An image composable with built-in zoom and pan support.
 *
 * - Scroll wheel zooms in/out centered on cursor position
 * - Click and drag pans when zoomed in
 * - Single click resets zoom when zoomed in
 * - Pinch-to-zoom on trackpad (via [detectTransformGestures])
 * - Resets zoom/pan when [key] changes (e.g. page change)
 */
@Composable
fun ZoomableImage(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    key: Any? = null,
    minZoom: Float = 0.5f,
    maxZoom: Float = 4f,
) {
    var scale by remember(key) { mutableFloatStateOf(1f) }
    var offset by remember(key) { mutableStateOf(Offset.Zero) }

    // Reset on key change
    remember(key) {
        scale = 1f
        offset = Offset.Zero
    }

    Box(
        modifier = modifier
            .pointerInput(key) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val oldScale = scale
                    scale = (scale * zoom).coerceIn(minZoom, maxZoom)

                    // Zoom toward centroid
                    val centroidOnImage = (centroid - offset) / oldScale
                    offset = centroid - centroidOnImage * scale + pan * scale

                    // Clamp offset so image doesn't drift too far
                    val maxOffsetX = max(0f, (size.width * (scale - 1)) / 2)
                    val maxOffsetY = max(0f, (size.height * (scale - 1)) / 2)
                    offset = Offset(
                        offset.x.coerceIn(-maxOffsetX, maxOffsetX),
                        offset.y.coerceIn(-maxOffsetY, maxOffsetY),
                    )
                }
            }
            .pointerInput(key) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val delta = event.changes.first().scrollDelta
                            // Scroll up = zoom in, scroll down = zoom out
                            val zoomFactor = if (delta.y < 0) 1.1f else 0.9f
                            val oldScale = scale
                            scale = (scale * zoomFactor).coerceIn(minZoom, maxZoom)

                            // Zoom toward cursor position
                            val centroid = event.changes.first().position
                            val centroidOnImage = (centroid - offset) / oldScale
                            offset = centroid - centroidOnImage * scale

                            val maxOffsetX = max(0f, (size.width * (scale - 1)) / 2)
                            val maxOffsetY = max(0f, (size.height * (scale - 1)) / 2)
                            offset = Offset(
                                offset.x.coerceIn(-maxOffsetX, maxOffsetX),
                                offset.y.coerceIn(-maxOffsetY, maxOffsetY),
                            )

                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .pointerInput(key) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press) {
                            val pressPos = event.changes.first().position
                            val releaseEvent = awaitPointerEvent()
                            val releasePos = releaseEvent.changes.first().position
                            if (abs(pressPos.x - releasePos.x) < 5f && abs(pressPos.y - releasePos.y) < 5f) {
                                if (scale > 1.1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * A variant of [ZoomableImage] that accepts a composable [imageContent] lambda instead of
 * a [Painter], useful when the image is loaded asynchronously (e.g. via Skia).
 */
@Composable
fun ZoomableImage(
    modifier: Modifier = Modifier,
    key: Any? = null,
    minZoom: Float = 0.5f,
    maxZoom: Float = 4f,
    imageContent: @Composable () -> Unit,
) {
    var scale by remember(key) { mutableFloatStateOf(1f) }
    var offset by remember(key) { mutableStateOf(Offset.Zero) }

    remember(key) {
        scale = 1f
        offset = Offset.Zero
    }

    Box(
        modifier = modifier
            .pointerInput(key) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val oldScale = scale
                    scale = (scale * zoom).coerceIn(minZoom, maxZoom)

                    val centroidOnImage = (centroid - offset) / oldScale
                    offset = centroid - centroidOnImage * scale + pan * scale

                    val maxOffsetX = max(0f, (size.width * (scale - 1)) / 2)
                    val maxOffsetY = max(0f, (size.height * (scale - 1)) / 2)
                    offset = Offset(
                        offset.x.coerceIn(-maxOffsetX, maxOffsetX),
                        offset.y.coerceIn(-maxOffsetY, maxOffsetY),
                    )
                }
            }
            .pointerInput(key) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val delta = event.changes.first().scrollDelta
                            val zoomFactor = if (delta.y < 0) 1.1f else 0.9f
                            val oldScale = scale
                            scale = (scale * zoomFactor).coerceIn(minZoom, maxZoom)

                            val centroid = event.changes.first().position
                            val centroidOnImage = (centroid - offset) / oldScale
                            offset = centroid - centroidOnImage * scale

                            val maxOffsetX = max(0f, (size.width * (scale - 1)) / 2)
                            val maxOffsetY = max(0f, (size.height * (scale - 1)) / 2)
                            offset = Offset(
                                offset.x.coerceIn(-maxOffsetX, maxOffsetX),
                                offset.y.coerceIn(-maxOffsetY, maxOffsetY),
                            )

                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .pointerInput(key) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press) {
                            val pressPos = event.changes.first().position
                            val releaseEvent = awaitPointerEvent()
                            val releasePos = releaseEvent.changes.first().position
                            if (abs(pressPos.x - releasePos.x) < 5f && abs(pressPos.y - releasePos.y) < 5f) {
                                if (scale > 1.1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentAlignment = Alignment.Center,
        ) {
            imageContent()
        }
    }
}
