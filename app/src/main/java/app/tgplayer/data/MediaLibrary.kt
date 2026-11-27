package app.tgplayer.data

import android.content.Context
import android.net.Uri
import app.tgplayer.Telegram
import app.tgplayer.local.NetworkDrive
import app.tgplayer.local.NetworkDrives
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** [added] = when a scan first saw the file (ms), for Recently added. */
data class LibraryMovie(val uri: String, val name: String, val parsedYear: Int?, val meta: Meta?, val added: Long = 0) {
    val title get() = meta?.title ?: name
    val year get() = meta?.year ?: parsedYear
}

data class LibraryEpisode(val uri: String, val season: Int, val episode: Int, val meta: EpisodeMeta?, val added: Long = 0) {
    val code get() = "S%02dE%02d".format(season, episode)
    val title get() = meta?.name?.takeIf { it.isNotBlank() } ?: "Episode $episode"
}

data class LibraryShow(val name: String, val parsedYear: Int?, val meta: Meta?, val episodes: List<LibraryEpisode>) {
    val title get() = meta?.title ?: name
    val year get() = meta?.year ?: parsedYear
    val key get() = name.lowercase()
    val added get() = episodes.maxOfOrNull { it.added } ?: 0
}

/**
 * Like Jellyfin's Next up: per show, the first unwatched episode after the one finished most recently,
 * shows with the newest activity first. A show whose latest episode is half-watched is left to
 * Continue watching. [history] is newest first ([Store.recentHistory]).
 */
fun nextUp(catalog: Catalog, history: List<Store.HistoryEntry>): List<Pair<LibraryShow, LibraryEpisode>> {
    val byUri = history.associateBy { it.uri }
    val showOf = catalog.shows.flatMap { s -> s.episodes.map { it.uri to s } }.toMap()
    return history.mapNotNull { h -> showOf[h.uri]?.let { it to h } }
        .distinctBy { it.first.key }
        .mapNotNull { (show, latest) ->
            if (!latest.watched) return@mapNotNull null
            val i = show.episodes.indexOfFirst { it.uri == latest.uri }
            show.episodes.drop(i + 1).firstOrNull { byUri[it.uri]?.watched != true }?.let { show to it }
        }
}

data class Catalog(val movies: List<LibraryMovie>, val shows: List<LibraryShow>) {
    /** Genre → titles in it (movies and shows), biggest genres first. */
    val genres: List<Pair<String, List<Any>>> by lazy {
        val all: List<Pair<List<String>, Any>> = movies.map { it.meta?.genres.orEmpty() to it } + shows.map { it.meta?.genres.orEmpty() to it }
        all.flatMap { (genres, item) -> genres.map { it to item } }.groupBy({ it.first }, { it.second }).toList().sortedByDescending { it.second.size }
    }

    /** File URI → its TMDb details: the movie's own, or its show's for an episode. Unmatched files are left out. */
    val metaByUri: Map<String, Meta> by lazy {
        buildMap {
            shows.forEach { s -> s.meta?.let { m -> s.episodes.forEach { put(it.uri, m) } } }
            movies.forEach { m -> m.meta?.let { put(m.uri, it) } }
        }
    }

    /** Newest first: movies, and shows by their newest episode. */
    val recentlyAdded: List<Any> by lazy {
        (movies.map { it.added to it } + shows.map { it.added to it }).sortedByDescending { it.first }.take(20).map { it.second }
    }
}

/**
 * The Library: video files in the folders picked in Browse (on the phone or network drives) and the
 * videos of Telegram chats added from their page, named by [Naming] and matched on [Tmdb], like a
 * Jellyfin library. The list of the last scan is kept, so the Library opens instantly and offline;
 * [scan] walks the folders again.
 *
 * Telegram: a chat is the folder `tgchat://chat/<chatId>/<title>`, a video is `tgmsg://msg/<chatId>/<messageId>`
 * (the player looks the file up when it plays), named "/<chat title>/<caption or file name>".
 */
object MediaLibrary {
    /** One scanned video: when it was first seen, and its name when the URI has no file path (Telegram). */
    private data class Found(val added: Long, val name: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appContext: Context
    private lateinit var filesList: File
    /** Fix match picks: "movie:<name>:<year>" or "tv:<name>" → TMDb id. */
    private lateinit var matchesFile: File
    /** Telegram chats: chat id → newest message id scanned, and "fullScanAt" (ms). */
    private lateinit var telegramFile: File
    private val _catalog = MutableStateFlow<Catalog?>(null)
    val catalog: StateFlow<Catalog?> = _catalog.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    /** Folders the last scan couldn't read (e.g. a network drive away from home); their old files are kept. */
    private val _unreachable = MutableStateFlow<List<String>>(emptyList())
    val unreachable: StateFlow<List<String>> = _unreachable.asStateFlow()

    fun init(context: Context) {
        if (::filesList.isInitialized) return
        appContext = context.applicationContext
        val dir = File(context.filesDir, "library").apply { mkdirs() }
        filesList = File(dir, "files.txt")
        matchesFile = File(dir, "matches.json")
        telegramFile = File(dir, "telegram.json")
        Tmdb.cacheDir = File(dir, "tmdb")
    }

    /**
     * Shows the last scan (cached details only, no network) and scans again when that is over an hour
     * old, so new files turn up without pressing refresh. Called when the app opens and on the tab.
     */
    fun load() {
        if (_scanning.value) return
        if (!filesList.exists()) return scan()
        if (_catalog.value == null) scope.launch {
            val cached = build(readFiles(), offline = true)
            // Re-check: if the scan below already published a (fresher) catalog while we built, don't clobber
            // it with this stale offline one — both run unordered on `scope`.
            if (_catalog.value == null) _catalog.value = cached
        }
        if (System.currentTimeMillis() - filesList.lastModified() > 60 * 60 * 1000L) scan()
    }

    fun addFolder(uri: String) {
        Settings.libraryFolders = Settings.libraryFolders + uri
        scan()
    }

    fun removeFolders(uris: Collection<String>) {
        if (uris.isEmpty()) return
        Settings.libraryFolders = Settings.libraryFolders - uris.toSet()
        scan()
    }

    @Volatile private var rescan = false
    @Volatile private var rescanFull = false

    /**
     * Walks the folders again; asked while a scan runs, it runs once more afterwards (a folder was just added).
     * Telegram chats are read in full only the first time, on [full] (the Scan again button) or weekly (to drop
     * deleted posts); other scans ask only for posts newer than the last one seen, like Telegram's own apps.
     */
    fun scan(full: Boolean = false) {
        if (!_scanning.compareAndSet(false, true)) {
            rescan = true
            rescanFull = rescanFull || full
            return
        }
        scope.launch {
            try {
                rescan = false
                rescanFull = false
                val previous = readFiles()
                val unreachable = mutableListOf<String>()
                val now = System.currentTimeMillis()
                val telegram = runCatching { JSONObject(telegramFile.readText()) }.getOrDefault(JSONObject())
                val fullTelegram = full || now - telegram.optLong(FULL_SCAN_AT) > FULL_SCAN_EVERY_MS
                val chatKeys = Settings.libraryFolders.mapNotNull { telegramChatId(Uri.parse(it))?.toString() }.toSet()
                telegram.keys().asSequence().toList().filter { it != FULL_SCAN_AT && it !in chatKeys }.forEach(telegram::remove)
                val files = LinkedHashMap<String, Found>()
                for (folder in Settings.libraryFolders) {
                    try {
                        videosIn(folder, previous, telegram, fullTelegram).forEach { v -> files[v.uri] = Found(previous[v.uri]?.added ?: v.date ?: firstSeen(v.uri, now), v.name) }
                    } catch (e: Exception) {
                        unreachable += folder
                        previous.filterKeys { belongsTo(it, folder) }.forEach { (uri, found) -> files[uri] = found }
                    }
                }
                _unreachable.value = unreachable
                if (fullTelegram && unreachable.isEmpty()) telegram.put(FULL_SCAN_AT, now)
                telegramFile.writeTextAtomically(telegram.toString())
                filesList.writeTextAtomically(files.entries.joinToString("\n") { (uri, f) -> "$uri\t${f.added}\t${f.name.orEmpty().replace('\t', ' ')}" })
                _catalog.value = build(files, offline = true) // names and anything already matched, at once
                if (Tmdb.available) _catalog.value = build(files, offline = false)
            } finally {
                _scanning.value = false
            }
            if (rescan) scan(rescanFull)
        }
    }

    /**
     * TMDb details for a played video outside the Library, from its name read like a Library file's (files and
     * network drives by path, so Show/Season 1/… folders count; Telegram by its title). Only a title match counts,
     * so a clip named VID_2026… gets nothing rather than a stranger's poster. Jellyfin items have their own art.
     * Blocking network I/O; answers are cached like the Library's.
     */
    fun metaFor(uri: String, title: String): Meta? {
        val u = Uri.parse(uri)
        val name = when (u.scheme) {
            Jellyfin.SCHEME -> return null
            "file", in NetworkDrive.PROTOCOLS -> u.path ?: return null
            else -> "/$title"
        }
        return runCatching {
            when (val p = Naming.parse(name)) {
                is Parsed.Movie -> Tmdb.movie(p.name, p.year, offline = false, titleRequired = true)
                is Parsed.Episode -> Tmdb.show(p.series, p.year, offline = false, titleRequired = true)
                null -> null
            }
        }.getOrNull()
    }

    fun matchKey(movie: LibraryMovie) = "movie:${movie.name.lowercase()}:${movie.parsedYear ?: ""}"
    fun matchKey(show: LibraryShow) = "tv:${show.key}"

    /** Fix match: use [meta] (picked from [Tmdb.candidates]) for this title from now on. */
    fun setMatch(key: String, meta: Meta) {
        scope.launch {
            val matches = readMatches()
            matches.put(key, meta.tmdbId)
            matchesFile.writeTextAtomically(matches.toString())
            _catalog.value = build(readFiles(), offline = false)
        }
    }

    private fun readMatches(): JSONObject =
        runCatching { JSONObject(matchesFile.readText()) }.getOrDefault(JSONObject())

    /**
     * Write + atomic rename. Plain writeText truncates first, so a concurrent reader (setMatch racing a
     * scan, both on [scope]) could read a half-written file — runCatching then swallows it to empty and the
     * hand-picked matches vanish. Rename is atomic on the same filesystem, and a crash can't truncate.
     */
    private fun File.writeTextAtomically(text: String) {
        val tmp = File.createTempFile(name, ".tmp", parentFile)
        tmp.writeText(text)
        if (!tmp.renameTo(this)) { tmp.delete(); throw IOException("Couldn't write $name") }
    }

    /** URI → what the last scan knew. Lines are "uri<TAB>ms<TAB>name" (older lists had fewer columns). */
    private fun readFiles(): Map<String, Found> =
        if (!filesList.exists()) emptyMap()
        else filesList.readLines().filter { it.isNotBlank() }.associate { line ->
            val cols = line.split('\t')
            cols[0] to Found(cols.getOrNull(1)?.toLongOrNull() ?: 0L, cols.getOrNull(2)?.ifEmpty { null })
        }

    private fun belongsTo(uri: String, folder: String): Boolean {
        val f = Uri.parse(folder)
        return if (f.scheme == TG_CHAT) uri.startsWith("$TG_MESSAGE://msg/${f.pathSegments.firstOrNull()}/") else uri.startsWith("$folder/")
    }

    /** The Library "folder" for a Telegram chat. */
    fun telegramFolder(chatId: Long, title: String): String =
        Uri.Builder().scheme(TG_CHAT).authority("chat").appendPath(chatId.toString()).appendPath(title).build().toString()

    const val TG_CHAT = "tgchat"
    private const val TMDB_CONCURRENCY = 6 // bounded parallel TMDb round trips per scan (OPT-4)
    private const val FULL_SCAN_AT = "fullScanAt"
    private const val FULL_SCAN_EVERY_MS = 7 * 24 * 60 * 60 * 1000L
    const val TG_MESSAGE = "tgmsg"

    private class Video(val uri: String, val name: String? = null, val date: Long? = null)

    /** A phone file's own date on the first scan (so a new library isn't all "added now"); network files: now. */
    private fun firstSeen(uri: String, now: Long): Long =
        Uri.parse(uri).takeIf { it.scheme == "file" }?.path?.let { File(it).lastModified() }?.takeIf { it > 0 } ?: now

    private suspend fun videosIn(folder: String, previous: Map<String, Found>, telegram: JSONObject, full: Boolean): List<Video> {
        val uri = Uri.parse(folder)
        return when (uri.scheme) {
            in NetworkDrive.PROTOCOLS -> NetworkDrives.videosUnder(uri).map { Video(it.uri.toString()) }
            TG_CHAT -> telegramVideos(uri, previous.filterKeys { belongsTo(it, folder) }, telegram, full)
            else -> {
                val root = File(uri.path.orEmpty())
                if (!root.isDirectory) throw IOException("Missing folder $root")
                root.walkTopDown().onEnter { it == root || !it.name.startsWith('.') }
                    .filter { it.isFile && playableKind(null, it.name) == MediaKind.VIDEO }
                    .map { Video(Uri.fromFile(it).toString()) }.toList()
            }
        }
    }

    private fun telegramChatId(folder: Uri): Long? =
        if (folder.scheme == TG_CHAT) folder.pathSegments.firstOrNull()?.toLongOrNull() else null

    /**
     * A chat's videos and video files, newest first. Clips under 10 minutes (trailers, promos) are left
     * out; files without a known length are kept.
     *
     * Unless [full], only posts newer than the newest one the last scan saw are asked for (one request per kind
     * when nothing is new), and the chat's [previous] videos are kept; [telegram] records the newest seen.
     * tails: first 3000 videos per chat; page further if a channel ever holds more.
     */
    private suspend fun telegramVideos(folder: Uri, previous: Map<String, Found>, telegram: JSONObject, full: Boolean): List<Video> {
        val chatId = telegramChatId(folder) ?: throw IOException("Bad Telegram folder")
        val chatTitle = folder.pathSegments.getOrNull(1).orEmpty().replace('/', ' ')
        if (!Telegram.ready(appContext)) throw IOException("Telegram isn't logged in")
        val since = if (full) null else telegram.optLong(chatId.toString()).takeIf { it > 0 }
        var newest = since ?: 0L
        val out = LinkedHashMap<String, Video>()
        for (filter in listOf(MediaFilter.VIDEOS, MediaFilter.FILES)) {
            var from: Long? = 0L
            while (from != null && out.size < 3000) {
                val page = Library.chatMedia(chatId, filter, from)
                page.items.forEach { newest = maxOf(newest, it.messageId) }
                page.items.filter {
                    (since == null || it.messageId > since) && it.kind == MediaKind.VIDEO && (it.durationSec == 0 || it.durationSec >= 600)
                }.forEach { m ->
                    val uri = "$TG_MESSAGE://msg/${m.chatId}/${m.messageId}"
                    // ".mkv" so a title's own dots ("Movie.2010.1080p") aren't read as an extension.
                    out[uri] = Video(uri, "/$chatTitle/${m.title.replace('/', ' ')}.mkv", m.date * 1000L)
                }
                from = page.next?.takeIf { since == null || it > since } // newest first: stop at what was seen
            }
        }
        if (since != null) previous.forEach { (uri, f) -> out.putIfAbsent(uri, Video(uri, f.name, f.added)) }
        telegram.put(chatId.toString(), newest)
        return out.values.toList()
    }

    // Online scans do a TMDb round trip per movie and per show/season; running them serially made a
    // large library a long wait. Fan out with bounded concurrency (OPT-4) — fast without hammering the
    // API. Offline scans (cache reads only) share the path harmlessly. Output order is unchanged: both
    // lists are sorted below, so the result is identical to the serial version.
    private suspend fun build(files: Map<String, Found>, offline: Boolean): Catalog {
        fun <T> lookup(block: () -> T?): T? = runCatching(block).getOrNull() // no network now: unmatched until the next scan
        val matches = readMatches()
        fun picked(key: String, kind: String) = matches.optInt(key, 0).takeIf { it > 0 }?.let { id -> lookup { Tmdb.byId(kind, id, offline) } }
        val parsed = files.mapNotNull { (uri, f) -> (f.name ?: Uri.parse(uri).path)?.let(Naming::parse)?.let { uri to it } }
        val movies = parsed.mapNotNull { (uri, p) -> (p as? Parsed.Movie)?.let { uri to it } }
            .parallelMap { (uri, movie) ->
                val m = LibraryMovie(uri, movie.name, movie.year, null, files[uri]?.added ?: 0)
                m.copy(meta = picked(matchKey(m), "movie") ?: lookup { Tmdb.movie(movie.name, movie.year, offline) })
            }
        val shows = parsed.mapNotNull { (uri, p) -> (p as? Parsed.Episode)?.let { uri to it } }
            .groupBy { it.second.series.lowercase() }
            .toList()
            .parallelMap { (_, episodes) ->
                val first = episodes.first().second
                val meta = picked("tv:${first.series.lowercase()}", "tv") ?: lookup { Tmdb.show(first.series, first.year, offline) }
                val seasons = episodes.map { it.second.season }.distinct().associateWith { s ->
                    meta?.let { m -> lookup { Tmdb.season(m.tmdbId, s, offline) } }.orEmpty()
                }
                val list = episodes.map { (uri, e) -> LibraryEpisode(uri, e.season, e.episode, seasons[e.season]?.get(e.episode), files[uri]?.added ?: 0) }
                LibraryShow(first.series, first.year, meta, list.sortedWith(compareBy({ it.season }, { it.episode }, { it.uri })))
            }
        return Catalog(movies.sortedBy { it.title.lowercase() }, shows.sortedBy { it.title.lowercase() })
    }

    /** Runs [block] over the list with at most [TMDB_CONCURRENCY] calls in flight (bounded TMDb I/O). */
    private suspend fun <T, R> List<T>.parallelMap(block: suspend (T) -> R): List<R> = coroutineScope {
        val gate = Semaphore(TMDB_CONCURRENCY)
        map { async { gate.withPermit { block(it) } } }.awaitAll()
    }
}
