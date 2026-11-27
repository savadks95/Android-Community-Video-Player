package app.tgplayer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale

/**
 * The shared skeleton of the single-bitmap image loaders ([WebImage], [LocalThumb], [NetThumb]):
 * seed synchronously from a cache, decode off the main thread once per [key], and draw the result or
 * a [placeholder]. Each loader keeps its own cache, disk/network fetch, concurrency gate and
 * miss-tracking inside [load] — only this render + state wiring was duplicated.
 *
 * [TdImage] is deliberately NOT built on this: it draws a blurred inline mini-thumb beneath the full
 * image, a two-layer render that only it needs.
 */
@Composable
fun <K : Any> ThumbBox(
    key: K?,
    seed: (K) -> ImageBitmap?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: @Composable () -> Unit = {},
    load: suspend (K) -> ImageBitmap?,
) {
    var bitmap by remember(key) { mutableStateOf(key?.let(seed)) }
    LaunchedEffect(key) {
        if (bitmap == null && key != null) bitmap = load(key)
    }
    Box(modifier) {
        val b = bitmap
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = contentScale) else placeholder()
    }
}
