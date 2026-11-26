package app.tgplayer.local

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A file to send: where to read it, and the name and size the receiver is shown. */
data class DropFile(val uri: Uri, val name: String, val size: Long)

/** Someone nearby wants to send [files]; answered with [DropItSession.answer]. */
class DropOffer(val from: String, val files: List<DropFile>) {
    internal val answer = CompletableFuture<Boolean>()
    val totalBytes get() = files.sumOf { it.size }
}

/** An accepted transfer coming in: [saved] of [count] files done, [doneBytes] of [totalBytes]. */
data class DropReceiving(
    val from: String,
    val count: Int,
    val totalBytes: Long,
    val saved: Int = 0,
    val doneBytes: Long = 0,
    val failed: Boolean = false,
) {
    val finished get() = saved == count
}

/**
 * Drop It: send files to another phone running TG Player on the same Wi-Fi, AirDrop-style.
 *
 * While the Drop It screen is open the phone is visible: [DropItSession] runs a small HTTP server and announces
 * itself with a UDP beacon on [DISCOVERY_PORT] that every open phone listens for. mDNS/Bonjour was tried first
 * but many home routers drop multicast between Wi-Fi clients, so phones never saw each other; the beacon is sent
 * as a broadcast *and* as a unicast sweep of the /24, which gets through whenever a ping would. Sending is two
 * steps: POST /ask with the sender's name and file list, which waits until the receiver taps Accept (60 s at
 * most) and answers with a random token; then each file is POSTed to /file?token=…&i=… . Files land in
 * Download/Drop It. Only LAN addresses are answered, and nothing is written without Accept, under the names and
 * sizes accepted.
 */
object DropIt {
    const val PROTOCOL = "dropit"
    const val FOLDER = "Drop It"
    /** Fixed UDP port every phone listens on for presence beacons. */
    const val DISCOVERY_PORT = 48662
    /** How long a sender waits for Accept. */
    internal const val ASK_WAIT_SECONDS = 60L

    /** The phone's name as set in system settings (what Bluetooth shows), else its model. */
    fun deviceName(context: Context): String =
        android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** Name and size of a file:// or content:// URI; null when it can't be read or its size is unknown. Blocking I/O. */
    fun describe(context: Context, uri: Uri): DropFile? = runCatching {
        if (uri.scheme == "file") {
            File(uri.path!!).takeIf { it.isFile }?.let { DropFile(uri, it.name, it.length()) }
        } else {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(1)) DropFile(uri, c.getString(0) ?: uri.lastPathSegment ?: "file", c.getLong(1)) else null
            }
        }
    }.getOrNull()

    /**
     * Sends [files] to [peer]: asks first, then uploads one by one. [onProgress] gets bytes sent and the total
     * once accepted. Returns false when the receiver declines or doesn't answer; throws on errors.
     */
    suspend fun send(
        context: Context,
        peer: FoundServer,
        from: String,
        files: List<DropFile>,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val ask = JSONObject().put("from", from)
            .put("files", JSONArray(files.map { JSONObject().put("name", it.name).put("size", it.size) }))
            .toString().toByteArray()
        val token = post(URL("http://${peer.address}/ask"), ask.size.toLong(), (ASK_WAIT_SECONDS + 15) * 1000) { it.write(ask) }.let { (code, body) ->
            when (code) {
                200 -> JSONObject(body).getString("token")
                403 -> return@withContext false
                409 -> throw IOException("${peer.name} is busy with another transfer")
                else -> throw IOException("${peer.name} answered $code")
            }
        }
        val total = files.sumOf { it.size }
        var sent = 0L
        files.forEachIndexed { i, f ->
            val url = URL("http://${peer.address}/file?token=$token&i=$i")
            val input = context.contentResolver.openInputStream(f.uri) ?: throw IOException("Couldn't read ${f.name}")
            val (code, _) = input.use { src ->
                // After the last byte is sent, the receiver still has to write the whole file to (often slow)
                // storage before it answers "200 OK"; the sender waits idle for that, so allow a slow sink:
                // 2 min, plus 1 s per MB, capped at 30 min. 30 s was too short for a large file on a cheap phone.
                val ackTimeout = (120_000L + f.size / 1024L).coerceAtMost(30 * 60_000L)
                post(url, f.size, ackTimeout) { out ->
                    copy(src, out, f.size) { n -> sent += n; onProgress(sent, total) }
                }
            }
            if (code != 200) throw IOException("${peer.name} couldn't save ${f.name} ($code)")
        }
        true
    }

    /** A fixed-length POST; returns the status code and body. [write] must write exactly [length] bytes. */
    private suspend fun post(url: URL, length: Long, readTimeoutMs: Long, write: suspend (OutputStream) -> Unit): Pair<Int, String> {
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 5_000
            c.readTimeout = readTimeoutMs.toInt()
            c.setFixedLengthStreamingMode(length)
            c.outputStream.use { write(it) }
            val code = c.responseCode
            val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return code to body
        } finally {
            c.disconnect()
        }
    }

    /** Copies exactly [length] bytes; throws if [input] ends first. Checks for cancellation between chunks. */
    private suspend fun copy(input: InputStream, out: OutputStream, length: Long, onBytes: (Int) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var left = length
        while (left > 0) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw IOException("The file ended early")
            out.write(buffer, 0, n)
            left -= n
            onBytes(n)
        }
    }

    /** A received name made safe to save: no folders, no control characters, not hidden, not empty. */
    internal fun safeName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' }.trim().trimStart('.').take(200).ifEmpty { "file" }
}

/**
 * This phone's side of Drop It while the screen is open: the receiving HTTP server and the UDP presence beacon.
 * [start] once, [stop] when the screen closes (that also ends any transfer in progress).
 */
class DropItSession(context: Context) {
    private val app = context.applicationContext
    private val _offer = MutableStateFlow<DropOffer?>(null)
    private val _receiving = MutableStateFlow<DropReceiving?>(null)
    private val _peers = MutableStateFlow<List<FoundServer>>(emptyList())
    /** A transfer waiting for Accept / Decline. */
    val offer: StateFlow<DropOffer?> = _offer.asStateFlow()
    /** The last accepted transfer coming in, kept after it finishes so the screen can say so. */
    val receiving: StateFlow<DropReceiving?> = _receiving.asStateFlow()
    /** Other phones with Drop It open, by UDP beacon; pruned when they go quiet. */
    val peers: StateFlow<List<FoundServer>> = _peers.asStateFlow()

    private class Batch(val token: String, val offer: DropOffer, val received: MutableSet<Int> = HashSet())
    @Volatile private var batch: Batch? = null
    private var server: ServerSocket? = null
    private var workers: ExecutorService? = null
    // A random id in every beacon, so the phone can tell its own beacon (which comes back on broadcast) from a peer's.
    private val selfId = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    @Volatile private var beacon: DatagramSocket? = null
    private val seen = LinkedHashMap<String, Pair<FoundServer, Long>>() // peer id → (server, lastSeenMs)

    fun start() {
        val socket = ServerSocket(0) // every interface: Wi-Fi, Ethernet, or this phone's own hotspot
        val pool = Executors.newCachedThreadPool()
        pool.execute {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching { pool.execute { runCatching { client.use(::serve) } } }.onFailure { client.close() }
            }
        }
        server = socket
        workers = pool
        startBeacon(pool, socket.localPort)
    }

    fun stop() {
        _offer.value?.answer?.complete(false)
        runCatching { beacon?.close() }
        beacon = null
        runCatching { server?.close() }
        workers?.shutdownNow()
        server = null
        workers = null
        batch = null
    }

    fun answer(accept: Boolean) {
        _offer.value?.answer?.complete(accept)
    }

    // ─── UDP presence ───────────────────────────────────────────────────────────

    /**
     * Announces this phone and listens for others on [DropIt.DISCOVERY_PORT]. The beacon carries this phone's
     * name and the HTTP [httpPort]. It is sent every 2 s to the broadcast address and, so it still arrives on
     * routers that drop broadcast/multicast between clients, unicast to every address on the /24.
     */
    private fun startBeacon(pool: ExecutorService, httpPort: Int) {
        val socket = runCatching { DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(java.net.InetSocketAddress(DropIt.DISCOVERY_PORT)) } }.getOrNull() ?: return
        beacon = socket
        val name = DropIt.deviceName(app)
        val packet = JSONObject().put("id", selfId).put("name", name).put("port", httpPort).toString().toByteArray()
        pool.execute {
            val buffer = ByteArray(2048)
            while (!socket.isClosed) {
                val dp = DatagramPacket(buffer, buffer.size)
                runCatching { socket.receive(dp) }.getOrElse { break }
                onBeacon(dp)
            }
        }
        pool.execute {
            while (!socket.isClosed) {
                runCatching { sweep(socket, packet) }
                runCatching { Thread.sleep(2_000) }.getOrElse { break }
                prune()
            }
        }
    }

    /** Sends [packet] to the subnet and every host on this phone's /24, so a peer gets it by broadcast or unicast. */
    private fun sweep(socket: DatagramSocket, packet: ByteArray) {
        val port = DropIt.DISCOVERY_PORT
        runCatching { socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName("255.255.255.255"), port)) }
        for (ip in ownIpv4()) {
            val octets = ip.split('.')
            if (octets.size != 4) continue
            val prefix = "${octets[0]}.${octets[1]}.${octets[2]}."
            runCatching { socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName("$prefix${255}"), port)) }
            for (host in 1..254) {
                if ("$prefix$host" == ip) continue
                runCatching { socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName("$prefix$host"), port)) }
            }
        }
    }

    private fun onBeacon(dp: DatagramPacket) {
        val json = runCatching { JSONObject(String(dp.data, dp.offset, dp.length, Charsets.UTF_8)) }.getOrNull() ?: return
        val id = json.optString("id").ifBlank { return }
        if (id == selfId) return // our own beacon, bounced back by broadcast
        val port = json.optInt("port").takeIf { it in 1..65535 } ?: return
        val name = json.optString("name").take(60).ifBlank { "A nearby phone" }
        val address = "${dp.address.hostAddress}:$port"
        synchronized(seen) {
            val peer = FoundServer(name, address, DropIt.PROTOCOL)
            val changed = seen.put(id, peer to System.currentTimeMillis())?.first != peer
            if (changed) _peers.value = seen.values.map { it.first }
        }
    }

    /** Drops peers not heard from in 6 s (three missed beacons). */
    private fun prune() {
        val cutoff = System.currentTimeMillis() - 6_000
        synchronized(seen) {
            val before = seen.size
            seen.values.removeAll { it.second < cutoff }
            if (seen.size != before) _peers.value = seen.values.map { it.first }
        }
    }

    /** This phone's own IPv4 addresses on its up, non-loopback interfaces. */
    private fun ownIpv4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    // ─── HTTP receive ────────────────────────────────────────────────────────────

    private fun serve(client: Socket) {
        if (!client.inetAddress.run { isSiteLocalAddress || isLinkLocalAddress }) return
        client.soTimeout = 30_000
        val input = DataInputStream(client.getInputStream().buffered())
        val request = input.line()?.split(' ') ?: return
        val headers = generateSequence { input.line()?.takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val out = client.getOutputStream()
        val target = Uri.parse(request.getOrNull(1).orEmpty())
        val length = headers["content-length"]?.toLongOrNull()
        when {
            request[0] != "POST" -> reply(out, "405 Method Not Allowed")
            length == null -> reply(out, "411 Length Required")
            target.path == "/ask" -> ask(input, length, out)
            target.path == "/file" -> receive(target, input, length, out)
            else -> reply(out, "404 Not Found")
        }
    }

    private fun ask(input: DataInputStream, length: Long, out: OutputStream) {
        if (length > 256 * 1024) return reply(out, "413 Payload Too Large")
        val json = JSONObject(ByteArray(length.toInt()).also(input::readFully).toString(Charsets.UTF_8))
        val list = json.getJSONArray("files")
        val files = (0 until list.length()).map { i ->
            val f = list.getJSONObject(i)
            DropFile(Uri.EMPTY, DropIt.safeName(f.getString("name")), f.getLong("size").coerceAtLeast(0))
        }
        if (files.isEmpty()) return reply(out, "400 Bad Request")
        val offer = DropOffer(json.optString("from").take(60).ifBlank { "A nearby phone" }, files)
        // One prompt at a time. A newly accepted offer replaces an unfinished batch (its token stops working).
        if (!_offer.compareAndSet(null, offer)) return reply(out, "409 Conflict")
        val accepted = runCatching { offer.answer.get(DropIt.ASK_WAIT_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)
        _offer.compareAndSet(offer, null)
        if (!accepted) return reply(out, "403 Forbidden")
        val token = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        batch = Batch(token, offer)
        _receiving.value = DropReceiving(offer.from, files.size, offer.totalBytes)
        reply(out, "200 OK", JSONObject().put("token", token).toString())
    }

    private fun receive(target: Uri, input: InputStream, length: Long, out: OutputStream) {
        val b = batch?.takeIf { it.token == target.getQueryParameter("token") } ?: return reply(out, "403 Forbidden")
        val i = target.getQueryParameter("i")?.toIntOrNull()
        val file = i?.let { b.offer.files.getOrNull(it) } ?: return reply(out, "404 Not Found")
        if (length != file.size) return reply(out, "400 Bad Request")
        if (!synchronized(b) { b.received.add(i) }) return reply(out, "409 Conflict") // each accepted file once
        try {
            save(file.name, input, length)
            _receiving.value = _receiving.value?.let { it.copy(saved = it.saved + 1) }
            reply(out, "200 OK")
        } catch (e: IOException) {
            _receiving.value = _receiving.value?.copy(failed = true)
            batch = null
            throw e
        }
    }

    /** Writes [length] bytes from [input] to Download/Drop It, removing the partial file if the transfer breaks. */
    private fun save(name: String, input: InputStream, length: Long) {
        val onBytes = { n: Int -> _receiving.value = _receiving.value?.let { it.copy(doneBytes = it.doneBytes + n) } }
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = app.contentResolver
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/${DropIt.FOLDER}")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            // MediaStore picks a free name ("clip (1).mp4") if one is taken.
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Couldn't create $name")
            try {
                (resolver.openOutputStream(uri) ?: throw IOException("Couldn't write $name")).use { copyExactly(input, it, length, onBytes) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e as? IOException ?: IOException(e)
            }
        } else {
            // tails: Android 8–9 need the storage-write permission for the public Download folder, so files go to this
            // app's own folder (Android/data/app.tgplayer/files/Download/Drop It). Ask for WRITE_EXTERNAL_STORAGE to fix.
            val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), DropIt.FOLDER).apply { mkdirs() }
            val base = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            val file = generateSequence(0) { it + 1 }.map { n -> File(dir, if (n == 0) name else "$base ($n)$ext") }.first { !it.exists() }
            try {
                file.outputStream().use { copyExactly(input, it, length, onBytes) }
            } catch (e: IOException) {
                file.delete()
                throw e
            }
        }
    }

    private fun copyExactly(input: InputStream, out: OutputStream, length: Long, onBytes: (Int) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var left = length
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw IOException("The transfer was cut off")
            out.write(buffer, 0, n)
            left -= n
            onBytes(n)
        }
    }

    private fun reply(out: OutputStream, status: String, body: String = "") {
        val bytes = body.toByteArray()
        out.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    /** One header line (ISO-8859-1, CRLF or LF); null at end of stream. Byte-wise so the body after it stays unread. */
    private fun DataInputStream.line(): String? {
        val sb = StringBuilder()
        while (true) {
            val c = read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) throw IOException("Header too long")
            sb.append(c.toChar())
        }
    }
}
