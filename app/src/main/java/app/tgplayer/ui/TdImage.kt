package app.tgplayer.ui

import android.graphics.BitmapFactory
import android.util.LruCache
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.tgplayer.data.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

private val cache = LruCache<Int, ImageBitmap>(120)

/**
 * A Telegram image: shows the tiny inline [mini] thumbnail (blurred) at once, then the real [file]
 * after TDLib downloads it. [placeholder] draws when there is neither.
 */
@Composable
fun TdImage(
    file: TdApi.File?,
    mini: ByteArray?,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit = {},
) {
    val miniBitmap = remember(mini) { mini?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    var full by remember(file?.id) { mutableStateOf(file?.id?.let { cache.get(it) }) }
    LaunchedEffect(file?.id) {
        if (full == null && file != null) full = load(file)
    }
    Box(modifier) {
        val shown = full
        when {
            shown != null -> Image(shown, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            miniBitmap != null -> Image(miniBitmap, null, Modifier.fillMaxSize().blur(12.dp), contentScale = ContentScale.Crop)
            else -> placeholder()
        }
    }
}

private suspend fun load(file: TdApi.File): ImageBitmap? = runCatching {
    val path = Library.download(file).local.path.takeIf { it.isNotEmpty() } ?: return null
    withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 480) sample *= 2 // thumbnails never need more than ~480px
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }?.also { cache.put(file.id, it) }
}.getOrNull()
