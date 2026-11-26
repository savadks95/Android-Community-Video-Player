package app.tgplayer.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.annotation.OptIn
import androidx.media3.cast.CastTrackSelector
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaTrack
import com.google.common.collect.ImmutableSet
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Lets a Chromecast play what only the phone can read (phone files, network drives, Telegram): a small
 * HTTP server on the phone's Wi-Fi address that streams registered items through [RoutingDataSource],
 * with Range requests so the TV can seek. It runs only while casting ([PlaybackService] stops it when
 * the Cast session ends), answers LAN addresses only, and every URL carries a random per-session token.
 * SRT subtitles are served as WebVTT, which the Default Media Receiver reads (it has no SRT support).
 */
@OptIn(UnstableApi::class)
object CastServer {
    private class Entry(val uri: Uri, val mime: String, val toVtt: Boolean) {
        /** Total size once known; C.LENGTH_UNSET before the first request (or when the source can't tell). */
        @Volatile var length = C.LENGTH_UNSET.toLong()
    }

    private val entries = ArrayList<Entry>() // the index is the id in the URL
    private val ids = HashMap<String, Int>()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private var server: ServerSocket? = null
    private var workers: ExecutorService? = null
    private var token = ""
    private lateinit var appContext: Context

    /** An http URL the TV can fetch [uri] from, starting the server if needed; null when the phone has no Wi-Fi/Ethernet address. */
    @Synchronized
    fun urlFor(context: Context, uri: Uri, mime: String, toVtt: Boolean = false): String? {
        val socket = server ?: start(context) ?: return null
        val id = ids.getOrPut("$toVtt|$uri") { entries += Entry(uri, mime, toVtt); entries.lastIndex }
        return "http://${socket.inetAddress.hostAddress}:${socket.localPort}/$token/$id"
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        workers?.shutdownNow()
        workers = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        entries.clear()
        ids.clear()
    }

    private fun start(context: Context): ServerSocket? {
        val address = lanAddress(context) ?: return null
        appContext = context.applicationContext
        token = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val socket = ServerSocket(0, 16, address)
        val pool = Executors.newCachedThreadPool()
        pool.execute {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                clients += client
                runCatching {
                    pool.execute {
                        runCatching { client.use(::serve) }
                        clients -= client
                    }
                }.onFailure { client.close() } // stopped meanwhile
            }
        }
        server = socket
        workers = pool
        return socket
    }

    /** The phone's IPv4 address on Wi-Fi or Ethernet, the network a Chromecast shares. */
    private fun lanAddress(context: Context): InetAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        // tails: allNetworks rather than activeNetwork so a VPN on top doesn't hide the Wi-Fi; a phone acting as a hotspot has no such network and can't cast local files.
        @Suppress("DEPRECATION")
        val lan = cm.allNetworks.filter { n ->
            cm.getNetworkCapabilities(n)?.run { hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } == true
        }
        return lan.flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty() }.map { it.address }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    }

    private fun serve(client: Socket) {
        if (!client.inetAddress.run { isSiteLocalAddress || isLinkLocalAddress || isLoopbackAddress }) return
        client.soTimeout = 30_000
        val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = input.readLine()?.split(' ') ?: return
        val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val out = client.getOutputStream().buffered()
        val path = request.getOrNull(1).orEmpty().substringBefore('?').split('/') // "", token, id
        val entry = path.takeIf { it.size == 3 && it[1] == token }?.get(2)?.toIntOrNull()?.let { synchronized(this) { entries.getOrNull(it) } }
        val method = request[0]
        when {
            method == "OPTIONS" -> head(out, "204 No Content")
            entry == null -> head(out, "404 Not Found", mapOf("Content-Length" to 0))
            method != "GET" && method != "HEAD" -> head(out, "405 Method Not Allowed", mapOf("Content-Length" to 0))
            entry.toVtt -> {
                val srt = DataSourceInputStream(RoutingDataSource(appContext), DataSpec(entry.uri)).use { it.readBytes() }.toString(Charsets.UTF_8)
                val body = toWebVtt(srt).toByteArray()
                head(out, "200 OK", mapOf("Content-Type" to "text/vtt; charset=utf-8", "Content-Length" to body.size))
                if (method == "GET") out.write(body)
            }
            else -> stream(entry, headers["range"], method == "HEAD", out)
        }
        out.flush()
    }

    private fun stream(entry: Entry, rangeHeader: String?, headOnly: Boolean, out: OutputStream) {
        val source = RoutingDataSource(appContext)
        var openAt = -1L // where the source is open, -1 = not open
        try {
            if (entry.length == C.LENGTH_UNSET.toLong()) {
                entry.length = source.open(DataSpec(entry.uri))
                openAt = 0
            }
            val total = entry.length
            if (total == C.LENGTH_UNSET.toLong()) {
                // tails: a source that can't tell its size is sent whole, without seeking; all of ours report it.
                head(out, "200 OK", mapOf("Content-Type" to entry.mime))
                if (!headOnly) copy(source, Long.MAX_VALUE, out)
                return
            }
            val range = parseRange(rangeHeader, total)
            if (range?.isEmpty() == true) return head(out, "416 Range Not Satisfiable", mapOf("Content-Range" to "bytes */$total", "Content-Length" to 0))
            val first = range?.first ?: 0L
            val length = (range?.last ?: (total - 1)) - first + 1
            head(
                out, if (range == null) "200 OK" else "206 Partial Content",
                mapOf("Content-Type" to entry.mime, "Accept-Ranges" to "bytes", "Content-Length" to length) +
                    (range?.let { mapOf("Content-Range" to "bytes ${it.first}-${it.last}/$total") } ?: emptyMap()),
            )
            if (headOnly || length == 0L) return
            if (openAt != first) {
                if (openAt >= 0) source.close()
                source.open(DataSpec.Builder().setUri(entry.uri).setPosition(first).setLength(length).build())
            }
            copy(source, length, out)
        } finally {
            runCatching { source.close() }
        }
    }

    private fun copy(source: RoutingDataSource, length: Long, out: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        var left = length
        while (left > 0) {
            val n = source.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, n)
            left -= n
        }
    }

    private fun head(out: OutputStream, status: String, headers: Map<String, Any> = emptyMap()) {
        // The receiver fetches subtitle tracks with XHR, which needs CORS.
        val all = headers + mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Headers" to "Range",
            "Access-Control-Expose-Headers" to "Content-Length, Content-Range, Accept-Ranges",
            "Connection" to "close",
        )
        out.write(("HTTP/1.1 $status\r\n" + all.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n").toByteArray(Charsets.ISO_8859_1))
    }

    private val rangeSpec = Regex("""bytes=(\d*)-(\d*)""")

    /**
     * The byte range a `Range` header asks for out of [total] bytes: null to send the whole file (no
     * header, or one we ignore: malformed or several ranges), an empty range when it can't be satisfied (416).
     */
    internal fun parseRange(header: String?, total: Long): LongRange? {
        val (a, b) = header?.trim()?.let(rangeSpec::matchEntire)?.destructured ?: return null
        val first = a.toLongOrNull()
        val last = b.toLongOrNull()
        return when {
            first == null && last == null -> null
            first == null -> if (last == 0L || total == 0L) LongRange.EMPTY else maxOf(0L, total - last!!) until total // "bytes=-500": the final 500
            last != null && last < first -> null
            first >= total -> LongRange.EMPTY
            else -> first..minOf(last ?: (total - 1), total - 1)
        }
    }

    private val srtTime = Regex("""(\d+:\d{2}:\d{2}),(\d{3})""")

    /** SRT → WebVTT: a header, and "." instead of "," in the timing lines. */
    internal fun toWebVtt(srt: String): String =
        "WEBVTT\n\n" + srt.removePrefix("﻿").replace("\r\n", "\n").lines()
            .joinToString("\n") { if ("-->" in it) it.replace(srtTime, "$1.$2") else it }
}

/**
 * Hands the TV URLs it can fetch: http(s) links (and Jellyfin streams) as they are, everything else
 * through [CastServer]. Subtitle files go along as text tracks.
 */
@OptIn(UnstableApi::class)
class CastItemConverter(private val context: Context) : MediaItemConverter {
    private val default = DefaultMediaItemConverter()

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem = default.toMediaItem(mediaQueueItem)

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        // Built from the original item, so its custom data keeps the phone-side URI for handing playback back.
        val queueItem = default.toMediaQueueItem(mediaItem)
        val config = mediaItem.localConfiguration ?: return queueItem
        val info = queueItem.media ?: return queueItem
        // tails: Telegram files and Jellyfin streams have no extension to go by and are sent as MP4; the receiver sniffs the real container.
        val mime = config.mimeType ?: mimeOf(mediaItem.mediaId) ?: mimeOf(config.uri.toString()) ?: MimeTypes.VIDEO_MP4
        info.writer.setContentType(mime)
        if (config.uri.scheme?.lowercase() !in setOf("http", "https")) {
            CastServer.urlFor(context, config.uri, mime)?.let(info.writer::setContentUrl)
        }
        info.writer.setMediaTracks(config.subtitleConfigurations.mapIndexedNotNull { i, sub -> textTrack(i + 1L, sub) })
        return queueItem
    }

    private fun textTrack(id: Long, sub: MediaItem.SubtitleConfiguration): MediaTrack? {
        val (mime, toVtt) = when (sub.mimeType) {
            MimeTypes.APPLICATION_SUBRIP -> MimeTypes.TEXT_VTT to true
            MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_TTML -> sub.mimeType!! to false
            else -> return null // tails: ASS/SSA have no Cast equivalent and stay on the phone
        }
        // Served by the phone even when on the web: the receiver needs CORS headers for text tracks.
        val url = CastServer.urlFor(context, sub.uri, mime, toVtt) ?: return null
        return MediaTrack.Builder(id, MediaTrack.TYPE_TEXT)
            .setContentId(url).setContentType(mime).setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
            .setName(sub.label?.toString() ?: "Subtitles")
            .apply { sub.language?.let { setLanguage(it) } }
            .build()
    }

    private fun mimeOf(uri: String): String? =
        Uri.parse(uri).lastPathSegment?.substringAfterLast('.', "")?.lowercase()?.ifEmpty { null }
            ?.let(MimeTypeMap.getSingleton()::getMimeTypeFromExtension)
}

/**
 * Lets the player's track list work on the TV: a picked track (an override) or a turned-off type wins;
 * otherwise the receiver's choice stays, and subtitles that came with the file are switched on.
 */
@OptIn(UnstableApi::class)
class CastTracks : CastTrackSelector() {
    override fun evaluate(request: CastTrackSelectorRequest): CastTrackSelectorResult {
        val params = request.trackSelectionParameters
        val textOn = request.currentlySelectedTrackGroups.any { it.type == C.TRACK_TYPE_TEXT }
        val firstText = request.trackGroupList.firstOrNull { it.type == C.TRACK_TYPE_TEXT }
        val selections = request.trackGroupList.filter { group ->
            when {
                group.type in params.disabledTrackTypes -> false
                params.overrides.values.any { it.type == group.type } -> group in params.overrides
                else -> group in request.currentlySelectedTrackGroups || (group == firstText && !textOn)
            }
        }
        return request.buildResultUpon().setSelections(ImmutableSet.copyOf(selections)).build()
    }
}
