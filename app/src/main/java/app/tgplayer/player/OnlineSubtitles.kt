package app.tgplayer.player

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.tgplayer.BuildConfig
import app.tgplayer.Telegram
import app.tgplayer.local.LocalMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Locale

/**
 * Finds a subtitle on OpenSubtitles.com for a video that has none of its own. Matches by the file's
 * OpenSubtitles hash (size + first/last 64 KiB) when those bytes are readable, plus its name.
 * Found files stay in the cache, so replaying a video doesn't spend the download quota
 * (a free API key allows 5 downloads per IP per 24 hours without a login).
 */
object OnlineSubtitles {
    private const val API = "https://api.opensubtitles.com/api/v1"
    private const val CHUNK = 64 * 1024

    /** Off when the build has no `opensubtitles.apiKey` in local.properties. */
    val available get() = BuildConfig.OPENSUBTITLES_API_KEY.isNotEmpty()

    class Found(val file: File, val language: String)

    /** A subtitle for the video at [uri] (cached or freshly downloaded), or null when none matched. */
    suspend fun find(context: Context, key: String, name: String, uri: Uri): Found? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "subtitles").apply { mkdirs() }
        val id = sha1(key)
        // Cached as "<id>.<language>.<ext>".
        dir.listFiles { f -> f.name.startsWith("$id.") }?.firstOrNull()?.let { return@withContext Found(it, it.name.split('.')[1]) }

        val languages = listOf(deviceLanguage(), "en").distinct()
        val (fileId, language) = search(name, fingerprint(context, uri), languages) ?: return@withContext null
        val download = JSONObject(request("$API/download", JSONObject().put("file_id", fileId).toString()))
        val link = download.getString("link").takeIf { it.startsWith("https://") } ?: throw IOException("Unexpected download link")
        val ext = download.optString("file_name").substringAfterLast('.', "").lowercase().takeIf { it in LocalMedia.SUBTITLE_EXT } ?: "srt"
        val target = File(dir, "$id.${language.ifEmpty { "und" }}.$ext")
        val temp = File(dir, "$id.part")
        temp.writeText(request(link)) // the API serves subtitles as UTF-8
        if (!temp.renameTo(target)) throw IOException("Couldn't save the subtitle")
        Found(target, language)
    }

    /** Best match: hash match first, then preferred language, then most downloaded. Single-file subtitles only. */
    private fun search(name: String, hash: String?, languages: List<String>): Pair<Int, String>? {
        // The docs ask for sorted, lowercase parameters (otherwise the API redirects).
        val params = sortedMapOf("languages" to languages.sorted().joinToString(","), "query" to name.lowercase())
        if (hash != null) params["moviehash"] = hash
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val data = JSONObject(request("$API/subtitles?$query")).getJSONArray("data")
        val best = (0 until data.length()).map { data.getJSONObject(it).getJSONObject("attributes") }
            .filter { it.optJSONArray("files")?.length() == 1 }
            .minWithOrNull(
                compareBy<JSONObject>(
                    { !it.optBoolean("moviehash_match") },
                    { languages.indexOf(it.optString("language").lowercase()).let { i -> if (i < 0) languages.size else i } },
                    { -it.optLong("download_count") },
                ),
            ) ?: return null
        return best.getJSONArray("files").getJSONObject(0).getInt("file_id") to best.optString("language").lowercase()
    }

    /** The video's OpenSubtitles hash, when its first and last 64 KiB are on this phone. */
    private suspend fun fingerprint(context: Context, uri: Uri): String? = runCatching {
        when (uri.scheme) {
            "file" -> RandomAccessFile(uri.path.orEmpty(), "r").use { movieHash(it.channel, it.length()) }
            "content" -> context.contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
                val size = pfd.statSize
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { movieHash(it.channel, size) }
            }
            "tdlib" -> {
                // tails: uses only bytes playback already fetched; a stream without its tail yet searches by name only.
                val fileId = uri.lastPathSegment?.toIntOrNull() ?: return null
                val file = Telegram.send(TdApi.GetFile(fileId))
                val size = file.size
                val ready = size > 2L * CHUNK && file.local.path.isNotEmpty() &&
                    Telegram.send(TdApi.GetFileDownloadedPrefixSize(fileId, 0)).size >= CHUNK &&
                    Telegram.send(TdApi.GetFileDownloadedPrefixSize(fileId, size - CHUNK)).size >= CHUNK
                if (ready) RandomAccessFile(file.local.path, "r").use { movieHash(it.channel, size) } else null
            }
            else -> null // tails: network links search by name only; hashing would need two extra range requests
        }
    }.getOrNull()

    /**
     * OpenSubtitles hash: size plus the little-endian 64-bit sums of the first and last 64 KiB, as 16 hex digits.
     * Null outside the sizes the site indexes (131072 < size < 9000000000) or when the bytes can't be read.
     */
    internal fun movieHash(channel: FileChannel, size: Long): String? {
        if (size <= 2L * CHUNK || size >= 9_000_000_000L) return null
        val head = chunkSum(channel, 0) ?: return null
        val tail = chunkSum(channel, size - CHUNK) ?: return null
        return "%016x".format(size + head + tail)
    }

    private fun chunkSum(channel: FileChannel, at: Long): Long? {
        val buffer = ByteBuffer.allocate(CHUNK)
        while (buffer.hasRemaining()) if (channel.read(buffer, at + buffer.position()) <= 0) return null
        buffer.flip()
        val longs = buffer.order(ByteOrder.LITTLE_ENDIAN).asLongBuffer()
        var sum = 0L
        while (longs.hasRemaining()) sum += longs.get()
        return sum
    }

    /** OpenSubtitles splits only Portuguese and Chinese by region (pt-br, zh-cn…); other languages are two letters. */
    private fun deviceLanguage(): String {
        val parts = Locale.getDefault().toLanguageTag().lowercase().split('-')
        val region = parts.drop(1).lastOrNull { it.length == 2 }
        return if (parts[0] in setOf("pt", "zh") && region != null) "${parts[0]}-$region" else parts[0]
    }

    private fun request(url: String, jsonBody: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.setRequestProperty("Api-Key", app.tgplayer.Secrets.openSubtitles)
            c.setRequestProperty("User-Agent", "TG Player v${BuildConfig.VERSION_NAME}")
            c.setRequestProperty("Accept", "application/json")
            if (jsonBody != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(jsonBody.toByteArray()) }
            }
            if (c.responseCode !in 200..299) throw HttpDownload.HttpStatusException(c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
