package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import java.io.File

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * Full-screen photo: pinch to zoom (1x..5x, accumulated across gestures), drag to pan while zoomed, double tap toggles.
 * Coil decodes the file once, off the main thread, sampled to the view size; gestures only change a graphics layer.
 */
@Composable
fun ZoomableImage(file: File, contentDescription: String?, modifier: Modifier = Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    /** Zooms by [zoom] around [focus] (view coordinates) and pans by [pan]; keeps the picture covering the view. */
    fun transform(focus: Offset, pan: Offset, zoom: Float) {
        val next = (scale * zoom).coerceIn(1f, MAX_ZOOM)
        val factor = next / scale
        val centre = Offset(size.width / 2f, size.height / 2f)
        val moved = (focus - centre) - (focus - centre - offset) * factor + pan
        val maxX = size.width * (next - 1f) / 2f
        val maxY = size.height * (next - 1f) / 2f
        scale = next
        offset = Offset(moved.x.coerceIn(-maxX, maxX), moved.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) { detectTransformGestures { centroid, pan, zoom, _ -> transform(centroid, pan, zoom) } }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1f) transform(tap, Offset.Zero, 1f / scale) else transform(tap, Offset.Zero, DOUBLE_TAP_ZOOM)
                })
            },
    ) {
        AsyncImage(
            model = file,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.matchParentSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            },
        )
    }
}
