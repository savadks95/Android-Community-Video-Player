package app.tgplayer.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.tgplayer.data.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Capped by bytes, not entry count (like WebImage): count-capping full 480x270 bitmaps OOMs a low-heap device.
private val localCache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}
private val noThumb = LruCache<String, Boolean>(512) // files we already know have no picture (bounded, was an unbounded HashSet)

/** Video frame or embedded album art for a file on the phone; [placeholder] until/unless one exists. */
@Composable
fun LocalThumb(path: String, kind: MediaKind, modifier: Modifier = Modifier, placeholder: @Composable () -> Unit = {}) {
    ThumbBox(path, { localCache.get(it) }, modifier, placeholder = placeholder) { key ->
        if (noThumb.get(key) != null) null
        else withContext(Dispatchers.IO) { runCatching { load(key, kind) }.getOrNull() }
            ?.also { localCache.put(key, it) } ?: run { noThumb.put(key, true); null }
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
