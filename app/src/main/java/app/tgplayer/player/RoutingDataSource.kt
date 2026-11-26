package app.tgplayer.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener

/**
 * Picks the reader by URI scheme: `tdlib://` → Telegram ([TdlibDataSource]), `smb://`, `ftp(s)://` and `sftp://` → network drives ([SmbDataSource], [FtpDataSource], [SftpDataSource]), `http(s)://` → [HttpStreamDataSource] (the playing file, read ahead to disk) or plain HTTP,
 * anything else (file://, content://) → Media3's default local reader.
 */
@OptIn(UnstableApi::class)
class RoutingDataSource(private val context: Context) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = when (dataSpec.uri.scheme?.lowercase()) {
            "tdlib" -> TdlibDataSource()
            "smb" -> SmbDataSource()
            "ftp", "ftps" -> FtpDataSource()
            "sftp" -> SftpDataSource()
            // The file being played downloads ahead to disk; manifests/segments (HLS, DASH) go straight to HTTP.
            "http", "https" ->
                if (StreamCache.isReadAheadTarget(dataSpec.uri)) HttpStreamDataSource(context)
                else StreamCache.plainHttp.createDataSource()
            else -> DefaultDataSource(context, /* allowCrossProtocolRedirects = */ false)
        }
        listeners.forEach(source::addTransferListener)
        delegate = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: throw IllegalStateException("read() before open()")

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
        }
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutingDataSource(context.applicationContext)
    }
}
