package app.tgplayer.data

import app.tgplayer.BuildConfig
import app.tgplayer.player.HttpDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** A signed-in Jellyfin server. Only the access token is kept, never the password. */
data class JellyfinServer(val url: String, val name: String, val userId: String, val userName: String, val token: String)

/** One item from the server: a library, folder, series, season, movie or episode. */
data class JellyfinItem(
    val id: String,
    val name: String,
    val type: String,
    val isFolder: Boolean,
    val year: Int?,
    val overview: String,
    val rating: Double?,
    val runTimeMs: Long,
    val season: Int?,
    val episode: Int?,
    val seriesName: String?,
    val hasImage: Boolean,
    val played: Boolean,
    val positionMs: Long,
) {
    val playable get() = !isFolder && type in PLAYABLE
    /** What the player and watch history key on: `jellyfin://item/<id>` (the token stays out of history). */
    val uri get() = "${Jellyfin.SCHEME}://item/$id"
    val fraction get() = if (runTimeMs > 0) (positionMs.toFloat() / runTimeMs).coerceIn(0f, 1f) else 0f
    val label get() = if (type == "Episode" && episode != null) "${seriesName.orEmpty()} · S%02dE%02d · $name".format(season ?: 0, episode).trimStart(' ', '·') else name

    private companion object {
        val PLAYABLE = setOf("Movie", "Episode", "Video", "MusicVideo", "Trailer")
    }
}

/**
 * Your Jellyfin server as a source: its libraries, artwork, Continue watching and Next up, with
 * positions reported back so the server and other Jellyfin apps stay in step. Endpoints as in
 * Jellyfin.Api (UserController, UserViewsController, ItemsController, TvShowsController,
 * PlaystateController, VideosController).
 * tails: one server at a time; add a list if anyone has several.
 */
object Jellyfin {
    const val SCHEME = "jellyfin"

    var server: JellyfinServer?
        get() = runCatching {
            JSONObject(Settings.jellyfinJson).run { JellyfinServer(getString("url"), getString("name"), getString("userId"), getString("userName"), getString("token")) }
        }.getOrNull()
        private set(value) {
            Settings.jellyfinJson = value?.let {
                JSONObject().put("url", it.url).put("name", it.name).put("userId", it.userId).put("userName", it.userName).put("token", it.token).toString()
            }.orEmpty()
        }

    /** Signs in and keeps the server; [address] may lack http:// and the port is kept as typed. */
    suspend fun signIn(address: String, user: String, password: String): JellyfinServer = withContext(Dispatchers.IO) {
        val url = address.trim().trimEnd('/').let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" }
        val body = JSONObject().put("Username", user).put("Pw", password).toString()
        val auth = JSONObject(request(url, "/Users/AuthenticateByName", null, "POST", body))
        val name = runCatching { JSONObject(request(url, "/System/Info/Public", null)).optString("ServerName") }.getOrNull()
        JellyfinServer(
            url,
            name?.ifEmpty { null } ?: url.substringAfter("://"),
            auth.getJSONObject("User").getString("Id"),
            auth.getJSONObject("User").optString("Name", user),
            auth.getString("AccessToken"),
        ).also { server = it }
    }

    /** Forgets the server here and ends this device's session on it. */
    suspend fun signOut() {
        val s = server ?: return
        server = null
        withContext(Dispatchers.IO) { runCatching { request(s.url, "/Sessions/Logout", s.token, "POST", "") } }
    }

    suspend fun views(): List<JellyfinItem> = items("/UserViews", emptyMap())

    /** A folder's contents (a library's movies, a series' seasons, a season's episodes). */
    suspend fun children(parentId: String): List<JellyfinItem> =
        items("/Items", mapOf("parentId" to parentId, "sortBy" to "ParentIndexNumber,IndexNumber,SortName", "limit" to "500"))

    suspend fun resume(): List<JellyfinItem> = items("/UserItems/Resume", mapOf("mediaTypes" to "Video", "limit" to "20"))

    suspend fun nextUp(): List<JellyfinItem> = items("/Shows/NextUp", mapOf("limit" to "20"))

    /** The item's poster (episodes: their still). Image requests don't need the token. */
    fun imageUrl(item: JellyfinItem, maxHeight: Int = 480): String? =
        if (!item.hasImage) null else server?.let { "${it.url}/Items/${item.id}/Images/Primary?maxHeight=$maxHeight&quality=90" }

    /** The [type] image ("Backdrop", or "Primary": poster, episodes their still) of the item a history [uri] plays; null for other URIs. */
    fun imageUrl(uri: String, type: String, maxWidth: Int): String? =
        itemId(uri)?.let { id -> server?.let { "${it.url}/Items/$id/Images/$type?maxWidth=$maxWidth&quality=90" } }

    /** Direct play of the original file, for the player. */
    fun streamUrl(itemId: String): String? = server?.let { "${it.url}/Videos/$itemId/stream?static=true&api_key=${it.token}" }

    fun itemId(uri: String): String? = uri.takeIf { it.startsWith("$SCHEME://item/") }?.substringAfterLast('/')

    /**
     * Intro and recap ranges (ms) the server knows for an item (Jellyfin's MediaSegments, filled by its
     * intro-detection plugins), for the player's Skip button. Empty when there are none.
     */
    suspend fun skippable(itemId: String): List<Pair<String, LongRange>> = withContext(Dispatchers.IO) {
        val s = server ?: return@withContext emptyList()
        runCatching {
            val array = JSONObject(request(s.url, "/MediaSegments/$itemId?includeSegmentTypes=Intro&includeSegmentTypes=Recap", s.token)).optJSONArray("Items") ?: JSONArray()
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map { o ->
                o.optString("Type") to (o.optLong("StartTicks") / 10_000..o.optLong("EndTicks") / 10_000)
            }.filter { it.second.last > it.second.first }
        }.getOrDefault(emptyList())
    }

    /** Tells the server where playback stopped; past 90% it marks the item played. */
    suspend fun reportStopped(itemId: String, positionMs: Long) = withContext(Dispatchers.IO) {
        val s = server ?: return@withContext
        runCatching { request(s.url, "/PlayingItems/$itemId?positionTicks=${positionMs * 10_000}", s.token, "DELETE") }
    }

    private suspend fun items(path: String, params: Map<String, String>): List<JellyfinItem> = withContext(Dispatchers.IO) {
        val s = server ?: throw IOException("Not signed in to Jellyfin")
        val all = params + mapOf(
            "userId" to s.userId,
            "fields" to "Overview,ProductionYear",
            "enableUserData" to "true",
            "enableImageTypes" to "Primary",
        )
        val query = all.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val array = JSONObject(request(s.url, "$path?$query", s.token)).optJSONArray("Items") ?: JSONArray()
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map(::toItem)
    }

    private fun toItem(o: JSONObject): JellyfinItem {
        val user = o.optJSONObject("UserData")
        return JellyfinItem(
            id = o.getString("Id"),
            name = o.optString("Name"),
            type = o.optString("Type"),
            isFolder = o.optBoolean("IsFolder"),
            year = o.optInt("ProductionYear", 0).takeIf { it > 0 },
            overview = o.optString("Overview"),
            rating = o.optDouble("CommunityRating").takeIf { !it.isNaN() },
            runTimeMs = o.optLong("RunTimeTicks") / 10_000,
            season = o.optInt("ParentIndexNumber", -1).takeIf { it >= 0 },
            episode = o.optInt("IndexNumber", -1).takeIf { it >= 0 },
            seriesName = o.optString("SeriesName").ifEmpty { null },
            hasImage = o.optJSONObject("ImageTags")?.has("Primary") == true,
            played = user?.optBoolean("Played") == true,
            positionMs = (user?.optLong("PlaybackPositionTicks") ?: 0L) / 10_000,
        )
    }

    private fun request(base: String, path: String, token: String?, method: String = "GET", jsonBody: String? = null): String {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("Accept", "application/json")
            // The header Jellyfin's AuthorizationContext parses: client, device and (once signed in) the token.
            val tokenPart = token?.let { ", Token=\"$it\"" }.orEmpty()
            c.setRequestProperty(
                "Authorization",
                "MediaBrowser Client=\"TG Player\", Device=\"Android\", DeviceId=\"${deviceId()}\", Version=\"${BuildConfig.VERSION_NAME}\"$tokenPart",
            )
            if (jsonBody != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(jsonBody.toByteArray()) }
            }
            when (c.responseCode) {
                in 200..299 -> return c.inputStream.bufferedReader().use { it.readText() }
                401 -> throw IOException(if (token == null) "Wrong user name or password" else "Jellyfin signed this device out; sign in again")
                else -> throw HttpDownload.HttpStatusException(c.responseCode)
            }
        } finally {
            c.disconnect()
        }
    }

    private fun deviceId(): String = Settings.jellyfinDeviceId.ifEmpty { UUID.randomUUID().toString().also { Settings.jellyfinDeviceId = it } }
}
