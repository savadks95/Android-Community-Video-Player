package app.tgplayer.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * Plays a network file from [HttpDownload]'s file on disk while it downloads: reads whatever bytes
 * have arrived, waits for the rest, and re-aims the download when the player seeks elsewhere.
 * Same idea as [TdlibDataSource] for Telegram files.
 */
@OptIn(UnstableApi::class)
class HttpStreamDataSource(private val context: Context) : BaseDataSource(/* isNetwork = */ true) {
    private var download: HttpDownload? = null
    private var file: RandomAccessFile? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val dl = StreamCache.download(context, dataSpec.uri)
        download = dl
        position = dataSpec.position
        dl.aim(position)
        // Wait (briefly) to learn the file's length from the server, so the player knows where it ends.
        var waited = 0L
        while (dl.length < 0 && dl.available(position) == 0L && waited < 30_000) {
            dl.error?.let { throw asHttpError(it, dataSpec) }
            sleep()
            waited += POLL_MS
        }
        if (dl.length in 0 until position) throw IOException("Seek past the end of the file")
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            dl.length >= 0 -> dl.length - position
            else -> C.LENGTH_UNSET.toLong()
        }
        file = RandomAccessFile(dl.file, "r")
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val dl = download ?: throw IOException("read() before open()")
        var waited = 0L
        var available: Long
        while (true) {
            available = dl.available(position)
            if (available > 0) break
            if (dl.length in 0..position) return C.RESULT_END_OF_INPUT
            dl.error?.let { throw IOException(it) }
            if (waited % 1_000 == 0L) dl.aim(position) // someone else may have pulled the download away
            sleep()
            waited += POLL_MS
        }
        var toRead = minOf(length.toLong(), available)
        if (remaining != C.LENGTH_UNSET.toLong()) toRead = minOf(toRead, remaining)
        val f = file!!
        f.seek(position)
        val read = f.read(buffer, offset, toRead.toInt())
        if (read <= 0) return C.RESULT_END_OF_INPUT
        position += read
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
        bytesTransferred(read)
        return read
    }

    private fun sleep() {
        try {
            Thread.sleep(POLL_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException()
        }
    }

    private fun asHttpError(e: IOException, spec: DataSpec): IOException =
        if (e is HttpDownload.HttpStatusException) {
            HttpDataSource.InvalidResponseCodeException(e.code, null, e, emptyMap(), spec, ByteArray(0))
        } else e

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        file?.close()
        file = null
        download = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private companion object {
        const val POLL_MS = 40L
    }
}
