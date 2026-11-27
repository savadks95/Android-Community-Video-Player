package app.tgplayer.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Decoded images, capped by memory (an eighth of the heap) since backdrops are far bigger than posters. */
private val webCache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/** An image from the web (TMDb posters and stills, Jellyfin art), kept on disk; [placeholder] until/unless it loads. */
@Composable
fun WebImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val dir = remember(context) { File(context.cacheDir, "images") } // was reallocated every recomposition
    ThumbBox(url, { webCache.get(it) }, modifier, contentScale, placeholder) { key ->
        withContext(Dispatchers.IO) { runCatching { load(dir, key) }.getOrNull() }?.also { webCache.put(key, it) }
    }
}

private fun load(dir: File, url: String): ImageBitmap? {
    val file = File(dir, MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) })
    if (!file.exists()) {
        dir.mkdirs()
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            if (c.responseCode !in 200..299) return null
            val temp = File(dir, "${file.name}.part")
            c.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
            temp.renameTo(file)
        } finally {
            c.disconnect()
        }
    }
    return BitmapFactory.decodeFile(file.path)?.asImageBitmap()
}
