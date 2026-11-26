package app.tgplayer.data

import app.tgplayer.BuildConfig
import app.tgplayer.player.HttpDownload
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/** A movie's or show's details. Image fields are TMDb paths; [Tmdb.image] turns them into URLs. */
data class Meta(
    val tmdbId: Int,
    val title: String,
    val year: Int?,
    val overview: String,
    val rating: Double,
    val poster: String?,
    val backdrop: String?,
    val genres: List<String>,
)

data class EpisodeMeta(val name: String, val overview: String, val still: String?)

/**
 * Movie and show details from TMDb (themoviedb.org), Jellyfin's default metadata source, matched the
 * way Jellyfin searches: by cleaned name and year, retrying without the year. Responses are kept on
 * disk for two weeks, so a rescan only asks about new titles and the Library works offline.
 */
object Tmdb {
    private const val API = "https://api.themoviedb.org/3"
    lateinit var cacheDir: File

    /** Off when the build has no `tmdb.apiKey` in local.properties. */
    val available get() = BuildConfig.TMDB_API_KEY.isNotEmpty()

    /** Sizes: posters w342, backdrops w780, episode stills w300. */
    fun image(path: String?, size: String): String? = path?.takeIf { it.isNotEmpty() }?.let { "https://image.tmdb.org/t/p/$size$it" }

    /**
     * [offline] = cached answers only (startup); otherwise asks TMDb about anything not cached.
     * [titleRequired] = only a result whose title matches counts, not TMDb's top hit (see [bestMatch]).
     */
    fun movie(name: String, year: Int?, offline: Boolean, titleRequired: Boolean = false): Meta? = search("movie", name, year, offline, titleRequired)

    fun show(name: String, year: Int?, offline: Boolean, titleRequired: Boolean = false): Meta? = search("tv", name, year, offline, titleRequired)

    /** Episode number → details for one season of a show. */
    fun season(showId: Int, season: Int, offline: Boolean): Map<Int, EpisodeMeta> {
        val episodes = get("/tv/$showId/season/$season", emptyMap(), offline)?.optJSONArray("episodes") ?: return emptyMap()
        return objects(episodes).associate { e ->
            e.optInt("episode_number") to EpisodeMeta(e.optString("name"), e.optString("overview"), e.optString("still_path").ifEmpty { null })
        }
    }

    /** A title picked by hand (Fix match): [kind] is "movie" or "tv". */
    fun byId(kind: String, id: Int, offline: Boolean): Meta? =
        if (!available) null else get("/$kind/$id", emptyMap(), offline)?.takeIf { it.has("id") }?.let { toMeta(kind, it, genres(kind, offline), "") }

    /** Everything TMDb finds for [query], in its order, for the Fix match list. Needs the network once. */
    fun candidates(kind: String, query: String, year: Int?): List<Meta> {
        if (!available || query.isBlank()) return emptyList()
        val genreNames = genres(kind, offline = false)
        return results(kind, query, year, offline = false).map { toMeta(kind, it, genreNames, query) }
    }

    private fun results(kind: String, name: String, year: Int?, offline: Boolean): List<JSONObject> {
        val yearKey = if (kind == "movie") "year" else "first_air_date_year"
        fun page(withYear: Boolean) = get(
            "/search/$kind",
            buildMap { put("query", name); if (withYear && year != null) put(yearKey, year.toString()) },
            offline,
        )?.optJSONArray("results")?.let(::objects).orEmpty()
        return page(true).ifEmpty { if (year != null) page(false) else emptyList() }
    }

    private fun search(kind: String, name: String, year: Int?, offline: Boolean, titleRequired: Boolean): Meta? {
        if (!available || name.isBlank()) return null
        val found = results(kind, name, year, offline)
        val dateKey = if (kind == "movie") "release_date" else "first_air_date"
        val (titleKey, originalKey) = if (kind == "movie") "title" to "original_title" else "name" to "original_name"
        val candidates = found.map { Candidate(it.optString(titleKey), it.optString(originalKey), yearOf(it.optString(dateKey))) }
        val best = found[bestMatch(candidates, name, year, titleRequired) ?: return null]
        return toMeta(kind, best, genres(kind, offline), name)
    }

    /** A search result (genre_ids) or a details response (genres objects). */
    private fun toMeta(kind: String, json: JSONObject, genreNames: Map<Int, String>, fallbackTitle: String): Meta {
        val genres = json.optJSONArray("genre_ids")?.let { ids -> (0 until ids.length()).mapNotNull { genreNames[ids.optInt(it)] } }
            ?: json.optJSONArray("genres")?.let(::objects)?.map { it.optString("name") }.orEmpty()
        return Meta(
            tmdbId = json.getInt("id"),
            title = json.optString(if (kind == "movie") "title" else "name").ifEmpty { fallbackTitle },
            year = yearOf(json.optString(if (kind == "movie") "release_date" else "first_air_date")),
            overview = json.optString("overview"),
            rating = json.optDouble("vote_average", 0.0),
            poster = json.optString("poster_path").takeIf { it.isNotEmpty() && it != "null" },
            backdrop = json.optString("backdrop_path").takeIf { it.isNotEmpty() && it != "null" },
            genres = genres,
        )
    }

    /**
     * Jellyfin's TmdbUtils.FindBestMatch: TMDb orders by popularity (searching "Dark" puts "Dark Shadows"
     * first), so an exact title (8) beats a whole-word prefix (4), and a year within one (2) breaks ties.
     * Nothing scoring keeps TMDb's own order.
     */
    internal data class Candidate(val title: String, val originalTitle: String, val year: Int?)

    /**
     * Index of the best of [results] (in TMDb's order) for [name] and [year]; null when there are none, or
     * with [titleRequired] when no title matches (for names that may not be films at all, like VID_2026…).
     */
    internal fun bestMatch(results: List<Candidate>, name: String, year: Int?, titleRequired: Boolean = false): Int? {
        if (results.isEmpty()) return null
        val wanted = normalize(name)
        fun titleScore(title: String): Int {
            val t = normalize(title)
            return when {
                t == wanted -> 8
                t.length > wanted.length && t[wanted.length] == ' ' && t.startsWith(wanted) -> 4
                else -> 0
            }
        }
        if (wanted.isEmpty()) return if (titleRequired) null else 0
        var best = 0
        var bestScore = 0
        results.forEachIndexed { i, r ->
            val score = maxOf(titleScore(r.title), titleScore(r.originalTitle)) +
                if (year != null && r.year != null && kotlin.math.abs(r.year - year) <= 1) 2 else 0
            if (score > bestScore) { // strictly greater: ties keep TMDb's more relevant order
                bestScore = score
                best = i
            }
        }
        // A title scores 4 or 8, a year only 2: under 4 nothing matched by name.
        return if (titleRequired && bestScore < 4) null else best
    }

    private fun normalize(title: String) = title.replace(Regex("[^\\p{L}\\p{N}\\p{M}]+"), " ").trim().lowercase()

    private fun genres(kind: String, offline: Boolean): Map<Int, String> =
        get("/genre/$kind/list", emptyMap(), offline)?.optJSONArray("genres")?.let(::objects).orEmpty().associate { it.optInt("id") to it.optString("name") }

    /**
     * A cached or fresh API response; null when [offline] and not cached. A 404 caches as `{}`.
     * Online, answers older than [MAX_AGE_MS] are fetched again (new episodes, fixed posters); if that
     * fails the old answer is used.
     */
    private fun get(path: String, params: Map<String, String>, offline: Boolean): JSONObject? {
        val all = params + ("language" to Locale.getDefault().toLanguageTag())
        val query = all.entries.sortedBy { it.key }.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val cached = File(cacheDir, sha1("$path?$query") + ".json")
        fun old() = runCatching { JSONObject(cached.readText()) }.getOrNull()
        if (cached.exists() && (offline || System.currentTimeMillis() - cached.lastModified() < MAX_AGE_MS)) return old()
        if (offline) return null
        val body = try {
            fetch("$API$path?$query&api_key=${BuildConfig.TMDB_API_KEY}")
        } catch (e: IOException) {
            if (cached.exists()) return old() else throw e
        }
        cacheDir.mkdirs()
        val temp = File(cacheDir, "${cached.name}.part")
        temp.writeText(body)
        if (!temp.renameTo(cached)) throw IOException("Couldn't save TMDb response")
        return JSONObject(body)
    }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.setRequestProperty("Accept", "application/json")
            return when (c.responseCode) {
                in 200..299 -> c.inputStream.bufferedReader().use { it.readText() }
                404 -> "{}"
                else -> throw HttpDownload.HttpStatusException(c.responseCode)
            }
        } finally {
            c.disconnect()
        }
    }

    private const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

    private fun objects(array: JSONArray) = (0 until array.length()).mapNotNull { array.optJSONObject(it) }

    private fun yearOf(date: String) = date.take(4).toIntOrNull()

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
