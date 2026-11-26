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
import net.schmizz.sshj.sftp.RemoteFile
import java.io.InputStream

/**
 * Reads a file on an SFTP drive (`sftp://host/path`) for ExoPlayer, over the drive's shared SSH
 * session. Reads are pipelined (16 requests in flight) from the wanted offset, so seeking is cheap.
 */
@OptIn(UnstableApi::class)
class SftpDataSource : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var file: RemoteFile? = null
    private var stream: InputStream? = null
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val f = NetworkDrives.sftpOpen(dataSpec.uri)
        file = f
        val size = f.length()
        if (dataSpec.position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        stream = f.ReadAheadRemoteFileInputStream(16, dataSpec.position)
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - dataSpec.position
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = stream!!.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (n == -1) return C.RESULT_END_OF_INPUT
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        runCatching { stream?.close() }
        runCatching { file?.close() }
        stream = null
        file = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
