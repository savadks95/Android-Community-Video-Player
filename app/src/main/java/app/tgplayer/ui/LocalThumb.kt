package app.tgplayer.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.tgplayer.data.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val localCache = LruCache<String, ImageBitmap>(200)
private val noThumb = HashSet<String>() // files we already know have no picture

/** Video frame or embedded album art for a file on the phone; [placeholder] until/unless one exists. */
@Composable
fun LocalThumb(path: String, kind: MediaKind, modifier: Modifier = Modifier, placeholder: @Composable () -> Unit = {}) {
    var bitmap by remember(path) { mutableStateOf(localCache.get(path)) }
    LaunchedEffect(path) {
        if (bitmap == null && path !in noThumb) {
            bitmap = withContext(Dispatchers.IO) { runCatching { load(path, kind) }.getOrNull() }
            bitmap?.let { localCache.put(path, it) } ?: noThumb.add(path)
        }
    }
    Box(modifier) {
        val b = bitmap
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else placeholder()
    }
}

private fun load(path: String, kind: MediaKind): ImageBitmap? {
    val file = File(path)
    val bmp: Bitmap? = if (kind == MediaKind.VIDEO) {
        if (Build.VERSION.SDK_INT >= 29) ThumbnailUtils.createVideoThumbnail(file, Size(480, 270), null)
        else @Suppress("DEPRECATION") ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND)
    } else {
        MediaMetadataRetriever().run {
            try {
                setDataSource(path)
                embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = 2 }) }
            } finally {
                release()
            }
        }
    }
    return bmp?.asImageBitmap()
}
