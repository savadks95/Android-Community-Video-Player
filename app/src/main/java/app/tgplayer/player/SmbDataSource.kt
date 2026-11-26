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
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.share.File as SmbFile
import java.io.IOException

/**
 * Reads a file on a network drive (`smb://host/share/path`) for ExoPlayer. Reads go out in 1 MiB
 * requests, since ExoPlayer asks for small pieces and each SMB request is a network round trip.
 */
@OptIn(UnstableApi::class)
class SmbDataSource : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var file: SmbFile? = null
    private var position = 0L
    private var remaining = 0L
    private val chunk = ByteArray(1 shl 20)
    private var chunkStart = 0L
    private var chunkLength = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val f = NetworkDrives.open(dataSpec.uri)
        file = f
        val size = smb { f.fileInformation.standardInformation.endOfFile }
        if (dataSpec.position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - position
        chunkLength = 0
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (position < chunkStart || position >= chunkStart + chunkLength) {
            val n = smb { file!!.read(chunk, position, 0, minOf(chunk.size.toLong(), remaining).toInt()) }
            if (n <= 0) return C.RESULT_END_OF_INPUT
            chunkStart = position
            chunkLength = n
        }
        val from = (position - chunkStart).toInt()
        val n = minOf(length.toLong(), (chunkLength - from).toLong(), remaining).toInt()
        System.arraycopy(chunk, from, buffer, offset, n)
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        val f = file
        file = null
        try {
            if (f != null) smb { f.close() }
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    /** smbj throws unchecked errors; ExoPlayer retries and reports only IOExceptions. */
    private fun <T> smb(block: () -> T): T =
        try {
            block()
        } catch (e: SMBRuntimeException) {
            throw IOException(e.message, e)
        }
}
