package app.tgplayer.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import app.tgplayer.Telegram
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * Streams a Telegram file to ExoPlayer while TDLib downloads it.
 *
 * open() aims TDLib's download at the requested byte offset (so seeking re-aims it), and read()
 * waits until the bytes at the current position are on disk, then reads them straight from
 * TDLib's file. URIs look like `tdlib://file/<fileId>`, optionally `?type=<container MIME type>`.
 */
@OptIn(UnstableApi::class)
class TdlibDataSource : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var fileId = 0
    private var position = 0L
    private var remaining = 0L
    private var file: RandomAccessFile? = null
    private var filePath: String? = null
    private var opened = false
    /** Bytes from [position] up to here are on disk, as TDLib last said; reads below it skip TDLib. */
    private var onDiskEnd = 0L
    private val ahead = ByteArray(AHEAD_BYTES)
    private var aheadAt = 0L
    private var aheadLength = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        fileId = dataSpec.uri.lastPathSegment?.toIntOrNull() ?: throw IOException("Bad TDLib uri ${dataSpec.uri}")
        transferInitializing(dataSpec)
        val info = td(TdApi.GetFile(fileId))
        val size = if (info.size > 0) info.size else info.expectedSize
        if (dataSpec.position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        position = dataSpec.position
        onDiskEnd = 0
        aheadLength = 0
        remaining =if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - position
        aimDownload()
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val read = if (length < SMALL_READ_BYTES) readSmall(buffer, offset, length) else readFile(buffer, offset, length)
        if (read <= 0) return C.RESULT_END_OF_INPUT
        position += read
        remaining -= read
        bytesTransferred(read)
        return read
    }

    /**
     * Small reads come from a copy of the next bytes on disk: Media3's Matroska reader walks a file's index a
     * byte or two at a time, and a file read for each adds up. Only bytes already on disk are copied, so this
     * never waits longer than the small read itself would have.
     */
    private fun readSmall(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position < aheadAt || position >= aheadAt + aheadLength) {
            aheadLength = 0
            val n = readFile(ahead, 0, ahead.size)
            if (n <= 0) return n
            aheadAt = position
            aheadLength = n
        }
        val from = (position - aheadAt).toInt()
        val count = minOf(length, aheadLength - from)
        System.arraycopy(ahead, from, buffer, offset, count)
        return count
    }

    /**
     * Reads from TDLib's file. TDLib is asked (two blocking round trips) only once [position] passes the bytes
     * it last confirmed on disk; before, every read asked, thousands of times before the first frame of an MKV.
     */
    private fun readFile(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= onDiskEnd) {
            onDiskEnd = position + waitForData()
            val path = td(TdApi.GetFile(fileId)).local.path
            // TDLib renames the file once the download completes; an open handle keeps reading it either way.
            if (path != filePath) {
                file?.close()
                file = RandomAccessFile(path, "r")
                filePath = path
            }
        }
        val toRead = minOf(length.toLong(), onDiskEnd - position, remaining).toInt()
        val f = file!!
        f.seek(position)
        return f.read(buffer, offset, toRead)
    }

    /** Blocks (on ExoPlayer's loader thread) until bytes at [position] exist; returns how many. */
    private fun waitForData(): Long {
        var waitedMs = 0L
        var restartAtMs = RESTART_AFTER_MS
        var downloadedAtStart = -1L // TDLib's total downloaded bytes when this wait (or the last restart) began
        while (true) {
            val ready = td(TdApi.GetFileDownloadedPrefixSize(fileId, position)).size
            if (ready > 0) return ready
            if (waitedMs % 1000 == 0L) {
                val local = td(TdApi.GetFile(fileId)).local
                if (downloadedAtStart < 0) downloadedAtStart = local.downloadedSize
                if (waitedMs >= restartAtMs && local.downloadedSize - downloadedAtStart >= RESTART_ELSEWHERE_BYTES) {
                    restartDownload()
                    restartAtMs = waitedMs + RESTART_AFTER_MS
                    downloadedAtStart = -1
                } else if (!local.isDownloadingActive || local.downloadOffset > aimOffset()) {
                    // Another reader (ExoPlayer probing the end of an MP4) may have pulled the download elsewhere.
                    aimDownload()
                }
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException()
            }
            waitedMs += POLL_MS
            // tails: polling; switch to updateFile events if this ever shows up in battery stats.
        }
    }

    /**
     * TDLib is filling the file somewhere else and ignoring our offset. Seen when a download began at offset 0
     * before we aimed it (the offline download of the same file) and Telegram moved it to its CDN, where TDLib
     * ignores offsets ([aimOffset]). A fresh download aimed here isn't on the CDN; the offline download is paused
     * for that moment, then resumed, and joins it.
     */
    private fun restartDownload() {
        Log.i(TAG, "No bytes at $position of file $fileId while others arrive; restarting its download")
        val offline = td(TdApi.SearchFileDownloads("", true, false, "", 100)).files.any { it.fileId == fileId && !it.isPaused }
        if (offline) td(TdApi.ToggleDownloadIsPaused(fileId, true))
        td(TdApi.CancelDownloadFile(fileId, false))
        aimDownload()
        if (offline) td(TdApi.ToggleDownloadIsPaused(fileId, false))
    }

    private fun aimDownload() {
        td(TdApi.DownloadFile(fileId, PRIORITY, aimOffset(), 0, false))
    }

    /**
     * Never 0: TDLib lets Telegram redirect a download to its CDN only when it is aimed at offset 0, and a CDN
     * download then ignores every later offset (TDLib FileDownloader::start_part, PartsManager::set_streaming_offset).
     * Files from big channels go to the CDN, so a seek, or an index at the end of the file (MP4 moov, MKV cues),
     * waited for the whole file. Byte 1 is in the first part, so the start still downloads first.
     */
    private fun aimOffset() = maxOf(position, 1L)

    override fun getUri(): Uri? = uri

    /** Media3 tries the format reader for this Content-Type first. */
    override fun getResponseHeaders(): Map<String, List<String>> =
        uri?.getQueryParameter(TYPE_PARAM)?.let { mapOf("Content-Type" to listOf(it)) } ?: emptyMap()

    override fun close() {
        uri = null
        file?.close()
        file = null
        filePath = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = TdlibDataSource()
    }

    companion object {
        private const val TAG = "TdlibDataSource"
        private const val PRIORITY = 32
        private const val POLL_MS = 50L
        /** A wait this long while this much arrives elsewhere in the file means TDLib ignores our offset. */
        private const val RESTART_AFTER_MS = 3_000L
        private const val RESTART_ELSEWHERE_BYTES = 2L shl 20
        private const val AHEAD_BYTES = 64 * 1024
        private const val SMALL_READ_BYTES = 4 * 1024
        private const val TYPE_PARAM = "type"

        /** Starts downloading a file from its head, as the player will (offset 1, see [aimOffset]), before it opens. */
        suspend fun warm(fileId: Int) {
            Telegram.send(TdApi.DownloadFile(fileId, PRIORITY, 1, 0, false))
        }

        /** [type] (from [app.tgplayer.data.containerType]) reaches the player as the stream's Content-Type. */
        fun uriFor(fileId: Int, type: String? = null): Uri =
            Uri.parse("tdlib://file/$fileId").buildUpon().apply { if (type != null) appendQueryParameter(TYPE_PARAM, type) }.build()
    }
}

private fun <T : TdApi.Object> td(function: TdApi.Function<T>): T =
    try {
        runBlocking { Telegram.send(function) }
    } catch (e: Exception) {
        throw if (e is IOException) e else IOException(e.message, e)
    }
