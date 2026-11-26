package app.tgplayer.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class SavedList(val label: String) { FAVOURITES("Favourites"), WATCH_LATER("Watch later") }

/** A saved reference; the UI re-reads the message from Telegram to show it. */
data class Ref(val chatId: Long, val messageId: Long)

data class Progress(val ref: Ref, val positionMs: Long, val durationMs: Long) {
    val fraction get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * The app's own data: Favourites, Watch later, playback positions and music playlists, in a small SQLite file.
 * tails: plain SQLiteOpenHelper instead of Room (no KSP); move to Room if the schema grows past a few tables.
 */
object Store {
    private lateinit var db: SQLiteOpenHelper
    private val _version = MutableStateFlow(0)
    /** Bumps on every write so screens can re-query. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** [fileName] is overridden only by the debug demo, so sample data never mixes with real lists. */
    fun init(context: Context, fileName: String = "library.db") {
        if (::db.isInitialized) return
        db = object : SQLiteOpenHelper(context.applicationContext, fileName, null, 3) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE saved(list TEXT NOT NULL, chat_id INTEGER NOT NULL, message_id INTEGER NOT NULL, " +
                        "added_at INTEGER NOT NULL, PRIMARY KEY(list, chat_id, message_id))",
                )
                db.execSQL(
                    "CREATE TABLE progress(chat_id INTEGER NOT NULL, message_id INTEGER NOT NULL, position_ms INTEGER NOT NULL, " +
                        "duration_ms INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(chat_id, message_id))",
                )
                createHistory(db)
                createPlaylists(db)
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                if (oldVersion < 2) createHistory(db)
                if (oldVersion < 3) createPlaylists(db)
            }

            /** v3: music playlists; songs are file paths in play order. */
            private fun createPlaylists(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE playlist(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created_at INTEGER NOT NULL)")
                db.execSQL(
                    "CREATE TABLE playlist_song(playlist_id INTEGER NOT NULL, path TEXT NOT NULL, position INTEGER NOT NULL, " +
                        "PRIMARY KEY(playlist_id, path))",
                )
            }

            /** v2: local files and network links (Telegram items stay in `progress`). */
            private fun createHistory(db: SQLiteDatabase) = db.execSQL(
                "CREATE TABLE history(uri TEXT PRIMARY KEY, title TEXT NOT NULL, position_ms INTEGER NOT NULL, " +
                    "duration_ms INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
            )
        }
    }

    data class HistoryEntry(val uri: String, val title: String, val positionMs: Long, val durationMs: Long) {
        val fraction get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        /** Played to the end (95%, the same line "Continue watching" uses). */
        val watched get() = fraction >= 0.95f
    }

    suspend fun saveHistory(uri: String, title: String, positionMs: Long, durationMs: Long) = write {
        insertWithOnConflict("history", null, ContentValues().apply {
            put("uri", uri); put("title", title)
            put("position_ms", positionMs); put("duration_ms", durationMs)
            put("updated_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun history(uri: String): HistoryEntry? = io {
        db.readableDatabase.rawQuery("SELECT uri, title, position_ms, duration_ms FROM history WHERE uri=?", arrayOf(uri))
            .use { c -> if (c.moveToFirst()) HistoryEntry(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3)) else null }
    }

    /** Recently played local files and links, newest first. */
    suspend fun recentHistory(limit: Int = 30): List<HistoryEntry> = io {
        db.readableDatabase.rawQuery(
            "SELECT uri, title, position_ms, duration_ms FROM history ORDER BY updated_at DESC LIMIT $limit", null,
        ).use { c -> buildList { while (c.moveToNext()) add(HistoryEntry(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3))) } }
    }

    suspend fun clearHistory() = write { delete("history", null, null) }

    /** Forget one file's position ("Mark unwatched"). */
    suspend fun removeHistory(uri: String) = write { delete("history", "uri=?", arrayOf(uri)) }

    suspend fun isSaved(list: SavedList, ref: Ref): Boolean = io {
        db.readableDatabase.rawQuery(
            "SELECT 1 FROM saved WHERE list=? AND chat_id=? AND message_id=?",
            arrayOf(list.name, ref.chatId.toString(), ref.messageId.toString()),
        ).use { it.moveToFirst() }
    }

    suspend fun setSaved(list: SavedList, ref: Ref, saved: Boolean) = write {
        if (saved) {
            insertWithOnConflict("saved", null, ContentValues().apply {
                put("list", list.name); put("chat_id", ref.chatId); put("message_id", ref.messageId)
                put("added_at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        } else {
            delete("saved", "list=? AND chat_id=? AND message_id=?", arrayOf(list.name, ref.chatId.toString(), ref.messageId.toString()))
        }
    }

    suspend fun saved(list: SavedList): List<Ref> = io {
        db.readableDatabase.rawQuery(
            "SELECT chat_id, message_id FROM saved WHERE list=? ORDER BY added_at DESC",
            arrayOf(list.name),
        ).use { c -> buildList { while (c.moveToNext()) add(Ref(c.getLong(0), c.getLong(1))) } }
    }

    suspend fun saveProgress(ref: Ref, positionMs: Long, durationMs: Long) = write {
        insertWithOnConflict("progress", null, ContentValues().apply {
            put("chat_id", ref.chatId); put("message_id", ref.messageId)
            put("position_ms", positionMs); put("duration_ms", durationMs)
            put("updated_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun progress(ref: Ref): Progress? = io {
        db.readableDatabase.rawQuery(
            "SELECT position_ms, duration_ms FROM progress WHERE chat_id=? AND message_id=?",
            arrayOf(ref.chatId.toString(), ref.messageId.toString()),
        ).use { c -> if (c.moveToFirst()) Progress(ref, c.getLong(0), c.getLong(1)) else null }
    }

    /** Started but not finished (under 95%), newest first. */
    suspend fun continueWatching(limit: Int = 20): List<Progress> = io {
        db.readableDatabase.rawQuery(
            "SELECT chat_id, message_id, position_ms, duration_ms FROM progress " +
                "WHERE position_ms > 5000 AND (duration_ms = 0 OR position_ms < duration_ms * 0.95) " +
                "ORDER BY updated_at DESC LIMIT $limit",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(Progress(Ref(c.getLong(0), c.getLong(1)), c.getLong(2), c.getLong(3))) } }
    }

    /** Everything ever played, most recent first; [Storage] trims the streaming cache in this order. */
    suspend fun playedByRecency(): List<Ref> = io {
        db.readableDatabase.rawQuery("SELECT chat_id, message_id FROM progress ORDER BY updated_at DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(Ref(c.getLong(0), c.getLong(1))) }
        }
    }

    suspend fun allProgress(): Map<String, Progress> = io {
        db.readableDatabase.rawQuery("SELECT chat_id, message_id, position_ms, duration_ms FROM progress", null).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    val p = Progress(Ref(c.getLong(0), c.getLong(1)), c.getLong(2), c.getLong(3))
                    put("${p.ref.chatId}/${p.ref.messageId}", p)
                }
            }
        }
    }

    // ─── Music playlists ───────────────────────────────────────────────────────

    data class Playlist(val id: Long, val name: String, val paths: List<String>)

    /** Every playlist, newest first, each with its songs' paths in order. */
    suspend fun playlists(): List<Playlist> = io {
        val r = db.readableDatabase
        val songs = HashMap<Long, MutableList<String>>()
        r.rawQuery("SELECT playlist_id, path FROM playlist_song ORDER BY playlist_id, position", null).use { c ->
            while (c.moveToNext()) songs.getOrPut(c.getLong(0)) { mutableListOf() }.add(c.getString(1))
        }
        r.rawQuery("SELECT id, name FROM playlist ORDER BY created_at DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(Playlist(c.getLong(0), c.getString(1), songs[c.getLong(0)].orEmpty())) }
        }
    }

    /** Creates an empty playlist and returns its id. */
    suspend fun createPlaylist(name: String): Long {
        val id = io {
            db.writableDatabase.insert("playlist", null, ContentValues().apply {
                put("name", name); put("created_at", System.currentTimeMillis())
            })
        }
        _version.value++
        return id
    }

    suspend fun renamePlaylist(id: Long, name: String) = write {
        update("playlist", ContentValues().apply { put("name", name) }, "id=?", arrayOf(id.toString()))
    }

    suspend fun deletePlaylist(id: Long) = write {
        delete("playlist_song", "playlist_id=?", arrayOf(id.toString()))
        delete("playlist", "id=?", arrayOf(id.toString()))
    }

    /** Appends [paths] to the end; songs already in the playlist stay where they are. */
    suspend fun addToPlaylist(id: Long, paths: List<String>) = write {
        beginTransaction()
        try {
            var next = rawQuery("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_song WHERE playlist_id=?", arrayOf(id.toString()))
                .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            for (path in paths) {
                val row = ContentValues().apply { put("playlist_id", id); put("path", path); put("position", next) }
                if (insertWithOnConflict("playlist_song", null, row, SQLiteDatabase.CONFLICT_IGNORE) != -1L) next++
            }
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    suspend fun removeFromPlaylist(id: Long, path: String) = write {
        delete("playlist_song", "playlist_id=? AND path=?", arrayOf(id.toString(), path))
    }

    /** Moves the song at [from] to [to] (indexes into the playlist's order). */
    suspend fun movePlaylistSong(id: Long, from: Int, to: Int) = write {
        val paths = rawQuery("SELECT path FROM playlist_song WHERE playlist_id=? ORDER BY position", arrayOf(id.toString()))
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }.toMutableList()
        if (from !in paths.indices || to !in paths.indices) return@write
        paths.add(to, paths.removeAt(from))
        beginTransaction()
        try {
            paths.forEachIndexed { i, path ->
                update("playlist_song", ContentValues().apply { put("position", i) }, "playlist_id=? AND path=?", arrayOf(id.toString(), path))
            }
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private suspend fun write(block: SQLiteDatabase.() -> Unit) {
        io { db.writableDatabase.block() }
        _version.value++
    }
}
