package app.tgplayer.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultHttpDataSource
import app.tgplayer.data.Settings
import app.tgplayer.data.Storage
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.UnknownServiceException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Network links, downloaded to disk ahead of playback.
 *
 * VLC-style players keep a small memory buffer, so a slow link stalls again soon after you pause to
 * let it buffer. Here the current link's file is downloaded to disk as fast as the connection allows,
 * keeps going while paused, and survives restarts. [HttpStreamDataSource] plays from whatever has
 * arrived, and seeking past the downloaded part moves the download there (like Telegram files).
 * HLS/DASH links use the player's (large) memory buffer instead.
 */
@OptIn(UnstableApi::class)
object StreamCache {
    private val downloads = ConcurrentHashMap<String, HttpDownload>()
    @Volatile private var current: HttpDownload? = null

    val readAheadUri: Uri? get() = current?.uri
    val readAheadBytes: Long get() = current?.downloadedBytes ?: 0
    val readAheadTotal: Long get() = current?.length ?: C.LENGTH_UNSET.toLong()

    /** Plain HTTP for manifests, segments and anything that isn't the current progressive file. */
    val plainHttp: DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(30_000)
        .setReadTimeoutMs(30_000)
        .setUserAgent(USER_AGENT)

    fun isReadAheadTarget(uri: Uri): Boolean = current?.uri == uri

    fun download(context: Context, uri: Uri): HttpDownload =
        downloads.getOrPut(uri.toString()) { HttpDownload(uri, File(dir(context), sha1(uri.toString()))) }

    /** The player moved to [uri]: download it ahead (progressive files only), stop the previous one. */
    @Synchronized
    fun readAhead(context: Context, uri: Uri) {
        if (!Settings.readAhead || Util.inferContentType(uri) != C.CONTENT_TYPE_OTHER) return cancel()
        val next = download(context, uri)
        if (current !== next) current?.stop()
        current = next
        next.aim(0) // start from the beginning; the player re-aims it at its own position
        trim(context)
    }

    @Synchronized
    fun cancel() {
        current?.stop()
        current = null
    }

    fun usedBytes(context: Context): Long =
        dir(context).listFiles().orEmpty().filter { it.extension == "data" }.sumOf(::bytesOnDisk)

    /**
     * Downloaded bytes in a cache file. Not File.length(): writing near the end (players probe a file's
     * index there) makes a sparse file whose nominal size is the whole video.
     */
    private fun bytesOnDisk(data: File): Long {
        val ranges = File(data.path.removeSuffix(".data") + ".ranges")
        return runCatching {
            ranges.readLines().drop(1).sumOf { line -> line.split(' ').let { it[1].toLong() - it[0].toLong() } }
        }.getOrDefault(data.length())
    }

    fun clear(context: Context) {
        cancel()
        downloads.values.forEach { it.stop() }
        downloads.clear()
        dir(context).listFiles().orEmpty().forEach { it.delete() }
    }

    /** Oldest files go first once the folder is over the user's limit; the current file is kept. */
    private fun trim(context: Context) {
        val limit = Settings.cacheLimitGb * Storage.GB
        val keep = current?.file
        val files = dir(context).listFiles().orEmpty().filter { it.extension == "data" }.sortedBy { it.lastModified() }
        var total = files.sumOf(::bytesOnDisk)
        for (f in files) {
            if (total <= limit) break
            if (f == keep) continue
            total -= bytesOnDisk(f)
            downloads.entries.removeIf { (_, d) -> (d.file == f).also { if (it) d.stop() } }
            f.delete()
            File(f.path.removeSuffix(".data") + ".ranges").delete()
        }
    }

    private fun dir(context: Context) = File(context.applicationContext.noBackupFilesDir, "stream-cache").apply { mkdirs() }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } + ".data"

    const val USER_AGENT = "TGPlayer"
}

/**
 * One network file filling up on disk. Keeps a set of downloaded byte ranges; a single background
 * thread fills the first gap at or after where it was last aimed, then the rest of the file.
 */
class HttpDownload(val uri: Uri, val file: File) {
    private val rangesFile = File(file.path.removeSuffix(".data") + ".ranges")
    private val ranges = RangeSet()
    private val writer = RandomAccessFile(file, "rw")
    private var thread: Thread? = null
    @Volatile private var generation = 0
    @Volatile private var writeOffset = -1L
    @Volatile var length = -1L; private set
    @Volatile var error: IOException? = null; private set

    init {
        loadRanges()
    }

    val downloadedBytes: Long get() = synchronized(ranges) { ranges.downloaded }
    val complete: Boolean get() = length >= 0 && nextMissing(0) == null

    /** Bytes on disk starting exactly at [pos] (0 if none yet). */
    fun available(pos: Long): Long = synchronized(ranges) { ranges.available(pos) }

    /** Make sure bytes at [pos] are on their way; restarts the downloader there if it's elsewhere. */
    @Synchronized
    fun aim(pos: Long) {
        error = null
        val target = nextMissing(pos) ?: if (complete) return else pos
        val running = thread?.isAlive == true
        val at = writeOffset
        // Already filling the gap we need, or just behind it (avoid thrashing on small skips).
        if (running && at <= target && target - at < 2 * MB) return
        val gen = ++generation
        thread = Thread({ run(gen, target) }, "read-ahead").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        generation++
        saveRanges()
    }

    private fun run(gen: Int, startAt: Long) {
        var offset = startAt
        var failures = 0
        while (gen == generation) {
            if (length >= 0 && offset >= length) offset = nextMissing(0) ?: break
            try {
                val (stream, connection) = open(offset)
                connection.use {
                    stream.use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (gen == generation) {
                            val limit = nextRangeStart(offset) // stop where data already exists
                            if (limit != null && offset >= limit) break
                            val want = if (limit == null) buffer.size else minOf(buffer.size.toLong(), limit - offset).toInt()
                            val n = input.read(buffer, 0, want)
                            if (n < 0) {
                                if (length < 0) length = offset // server didn't say how long it was
                                break
                            }
                            synchronized(writer) {
                                writer.seek(offset)
                                writer.write(buffer, 0, n)
                            }
                            addRange(offset, offset + n)
                            offset += n
                            writeOffset = offset
                            failures = 0
                            if (offset % (4 * MB) < n) saveRanges()
                        }
                    }
                }
                offset = nextMissing(offset) ?: nextMissing(0) ?: break
            } catch (e: IOException) {
                if (gen != generation) break
                Log.w(TAG, "download failed at $offset (attempt ${failures + 1}): ${e.javaClass.simpleName}: ${e.message}")
                // Flaky/slow networks: retry with backoff. Errors that retrying can't fix go to the player at once.
                if (isPermanent(e) || ++failures > 6) {
                    error = e
                    break
                }
                Thread.sleep(minOf(1_000L shl failures, 20_000L))
            }
        }
        if (gen == generation) writeOffset = -1
        saveRanges()
    }

    /** GET from [offset]; follows redirects (also http→https). Skips ahead if the server ignores Range. */
    private fun open(offset: Long): Pair<InputStream, AutoCloseable> {
        var url = URL(uri.toString())
        repeat(6) {
            val c = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", StreamCache.USER_AGENT)
                setRequestProperty("Range", "bytes=$offset-")
                setRequestProperty("Accept-Encoding", "identity")
            }
            val code = c.responseCode
            when (code) {
                in 300..399 -> {
                    val location = c.getHeaderField("Location") ?: throw IOException("Redirect without a location")
                    c.disconnect()
                    url = URL(url, location)
                    return@repeat
                }
                206 -> {
                    c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()?.let { length = it }
                    return c.inputStream to AutoCloseable { c.disconnect() }
                }
                200 -> {
                    c.contentLengthLong.takeIf { it > 0 }?.let { length = it }
                    val input = c.inputStream
                    var skipped = 0L
                    while (skipped < offset) { // no Range support: read past what we don't need
                        val s = input.skip(offset - skipped)
                        if (s <= 0) throw IOException("Stream ended while skipping to $offset")
                        skipped += s
                    }
                    return input to AutoCloseable { c.disconnect() }
                }
                416 -> {
                    c.disconnect()
                    if (length < 0) length = offset
                    return java.io.ByteArrayInputStream(ByteArray(0)) to AutoCloseable {}
                }
                else -> {
                    c.disconnect()
                    throw HttpStatusException(code)
                }
            }
        }
        throw IOException("Too many redirects")
    }

    class HttpStatusException(val code: Int) : IOException("HTTP $code")

    /** Refused by policy, bad address, or a 4xx answer: the same request will fail again. */
    private fun isPermanent(e: IOException) =
        e is UnknownServiceException || e is MalformedURLException || (e is HttpStatusException && e.code in 400..499)

    // ─── Range bookkeeping ───────────────────────────────────────────────────

    private fun addRange(start: Long, end: Long) = synchronized(ranges) { ranges.add(start, end) }

    /** First byte at or after [from] that isn't downloaded; null when the rest of the file is. */
    private fun nextMissing(from: Long): Long? = synchronized(ranges) { ranges.nextMissing(from, length) }

    private fun nextRangeStart(after: Long): Long? = synchronized(ranges) { ranges.nextRangeStart(after) }

    private fun saveRanges() = runCatching {
        val text = synchronized(ranges) { "$length\n" + ranges.lines() }
        rangesFile.writeText(text)
        file.setLastModified(System.currentTimeMillis()) // recency for the cache trim
    }

    private fun loadRanges() = runCatching {
        if (!rangesFile.exists()) return@runCatching
        val lines = rangesFile.readLines()
        length = lines.firstOrNull()?.toLongOrNull() ?: -1
        val size = file.length()
        lines.drop(1).mapNotNull { l -> l.split(' ').takeIf { it.size == 2 }?.let { longArrayOf(it[0].toLong(), it[1].toLong()) } }
            .filter { it[1] <= size } // never trust ranges past what's actually on disk
            .forEach { addRange(it[0], it[1]) }
    }

    private companion object {
        const val MB = 1L shl 20
        const val TAG = "HttpDownload"
    }
}
