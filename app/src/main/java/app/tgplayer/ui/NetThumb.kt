package app.tgplayer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.annotation.OptIn
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
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import app.tgplayer.player.RoutingDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

// Capped by bytes, not entry count (like WebImage): count-capping full bitmaps OOMs a low-heap device.
private val netCache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}
private val noNetThumb = HashSet<String>()
/** A folder of 50 videos shouldn't open 50 connections at once. */
private val netSlots = Semaphore(2)

/** A frame from a video on a network drive (SMB, FTP, SFTP), kept on disk; [placeholder] until/unless one exists. */
@Composable
fun NetThumb(uri: String, modifier: Modifier = Modifier, placeholder: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf(netCache.get(uri)) }
    LaunchedEffect(uri) {
        if (bitmap == null && uri !in noNetThumb) {
            bitmap = netSlots.withPermit { withContext(Dispatchers.IO) { runCatching { load(context, uri) }.getOrNull() } }
            bitmap?.let { netCache.put(uri, it) } ?: noNetThumb.add(uri)
        }
    }
    Box(modifier) {
        val b = bitmap
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else placeholder()
    }
}

private fun load(context: Context, uri: String): ImageBitmap? {
    val dir = File(context.cacheDir, "netthumbs").apply { mkdirs() }
    val file = File(dir, MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) } + ".jpg")
    if (!file.exists()) {
        val frame = MediaMetadataRetriever().run {
            try {
                RemoteMediaSource(context, Uri.parse(uri)).use { source ->
                    setDataSource(source)
                    // A tenth of the way in (max a minute): the first frame is often black or a logo.
                    val durationMs = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val atUs = minOf(durationMs / 10, 60_000L) * 1000
                    if (Build.VERSION.SDK_INT >= 27) getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
                    else getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            } finally {
                release()
            }
        } ?: return null
        file.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }
    return BitmapFactory.decodeFile(file.path)?.asImageBitmap()
}

/**
 * Lets MediaMetadataRetriever read a network file through the player's data sources, in 512 KiB
 * blocks (it makes many small reads, and each network open costs a round trip or a login).
 */
@OptIn(UnstableApi::class)
private class RemoteMediaSource(context: Context, private val uri: Uri) : MediaDataSource() {
    private val source = RoutingDataSource(context)
    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 16
    }
    private var length = -2L // -2: not asked yet

    override fun getSize(): Long {
        if (length == -2L) {
            length = try {
                source.open(DataSpec(uri)).let { if (it == C.LENGTH_UNSET.toLong()) -1L else it }
            } finally {
                source.close()
            }
        }
        return length
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        val start = position / BLOCK * BLOCK
        val block = blocks.getOrPut(start) { fetch(start) }
        val from = (position - start).toInt()
        if (from >= block.size) return -1
        val n = minOf(size, block.size - from)
        System.arraycopy(block, from, buffer, offset, n)
        return n
    }

    private fun fetch(start: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream(BLOCK)
        try {
            source.open(DataSpec.Builder().setUri(uri).setPosition(start).setLength(BLOCK.toLong()).build())
            val chunk = ByteArray(64 * 1024)
            while (out.size() < BLOCK) {
                val n = source.read(chunk, 0, minOf(chunk.size, BLOCK - out.size()))
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(chunk, 0, n)
            }
        } finally {
            source.close()
        }
        return out.toByteArray()
    }

    override fun close() {}

    private companion object {
        const val BLOCK = 512 * 1024
    }
}
