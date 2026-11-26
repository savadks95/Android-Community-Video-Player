package app.tgplayer.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import app.tgplayer.local.NetworkDrives
import org.apache.commons.net.ftp.FTPClient
import java.io.IOException
import java.io.InputStream

/**
 * Reads a file on an FTP drive (`ftp://host/path`) for ExoPlayer. Each open (start or seek) logs in
 * on its own connection and resumes the download at the wanted byte with REST, since an FTP
 * connection carries one transfer at a time.
 */
@OptIn(UnstableApi::class)
class FtpDataSource : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var client: FTPClient? = null
    private var stream: InputStream? = null
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val c = NetworkDrives.ftpConnect(dataSpec.uri)
        client = c
        val path = NetworkDrives.ftpPath(dataSpec.uri)
        val size = NetworkDrives.ftpSize(c, path)
        if (size >= 0 && dataSpec.position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        c.restartOffset = dataSpec.position
        stream = c.retrieveFileStream(path) ?: throw IOException(c.replyString?.trim() ?: "Couldn't read the file")
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            size >= 0 -> size - dataSpec.position
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        val n = stream!!.read(buffer, offset, toRead)
        if (n == -1) return C.RESULT_END_OF_INPUT
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    /** Drops the connection instead of finishing the transfer: seeks abandon the rest of the file. */
    override fun close() {
        uri = null
        runCatching { stream?.close() }
        stream = null
        client?.let(NetworkDrives::ftpClose)
        client = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
