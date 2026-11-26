package app.tgplayer.data

import app.tgplayer.TdException
import app.tgplayer.Telegram
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

enum class ChatKind(val label: String) { CHANNEL("Channel"), GROUP("Group"), PERSON("Person") }

data class ChatItem(
    val id: Long,
    val title: String,
    val kind: ChatKind,
    val photo: TdApi.File? = null,
    val miniThumb: ByteArray? = null,
)

enum class MediaKind { VIDEO, AUDIO }

/** What the media tabs and search look for. FILES = documents that are playable video/audio files (MKV, MP3...). */
enum class MediaFilter(val label: String) {
    VIDEOS("Videos"), FILES("Files"), AUDIO("Music");

    fun td(): TdApi.SearchMessagesFilter = when (this) {
        VIDEOS -> TdApi.SearchMessagesFilterVideo()
        FILES -> TdApi.SearchMessagesFilterDocument()
        AUDIO -> TdApi.SearchMessagesFilterAudio()
    }

    /** Whether a message with [content] is one [td] would find (for adding live messages to a list). */
    fun matches(content: TdApi.MessageContent): Boolean = when (this) {
        VIDEOS -> content is TdApi.MessageVideo
        FILES -> content is TdApi.MessageDocument
        AUDIO -> content is TdApi.MessageAudio
    }
}

data class MediaItem(
    val chatId: Long,
    val messageId: Long,
    val title: String,
    val chatTitle: String,
    val kind: MediaKind,
    val durationSec: Int,
    val sizeBytes: Long,
    val date: Int,
    val file: TdApi.File? = null,
    val thumb: TdApi.File? = null,
    val miniThumb: ByteArray? = null,
    val width: Int = 16,
    val height: Int = 9,
    /** Container MIME type for the player ([containerType]); null when unknown. */
    val type: String? = null,
) {
    val key get() = "$chatId/$messageId"
}

/** One page of results plus the cursor for the next one (null = no more). */
data class Page<C>(val items: List<MediaItem>, val next: C?)

/** What the screens read. [Library.source] is Telegram in the app; the debug demo swaps in sample data. */
interface LibrarySource {
    suspend fun chats(): List<ChatItem>
    suspend fun chatMedia(chatId: Long, filter: MediaFilter, fromMessageId: Long = 0, query: String = ""): Page<Long>
    suspend fun search(query: String, filter: MediaFilter, offset: String = ""): Page<String>
    suspend fun message(chatId: Long, messageId: Long): MediaItem?
    /** Offline downloads (TDLib's persistent download list), newest first, with current file state. */
    suspend fun downloads(): List<MediaItem>
}

object Library : LibrarySource {
    @Volatile var source: LibrarySource = TelegramSource
    override suspend fun chats() = source.chats()
    override suspend fun chatMedia(chatId: Long, filter: MediaFilter, fromMessageId: Long, query: String) =
        source.chatMedia(chatId, filter, fromMessageId, query)
    override suspend fun search(query: String, filter: MediaFilter, offset: String) = source.search(query, filter, offset)
    override suspend fun message(chatId: Long, messageId: Long) = source.message(chatId, messageId)
    override suspend fun downloads() = source.downloads()

    /** Downloads a whole (small) file and returns its final state; for thumbnails and avatars. */
    suspend fun download(file: TdApi.File, priority: Int = 1): TdApi.File =
        if (file.local.isDownloadingCompleted) file
        else Telegram.send(TdApi.DownloadFile(file.id, priority, 0, 0, true))

    /** The file's state right now (download progress, local path). */
    suspend fun fileState(file: TdApi.File): TdApi.File = Telegram.send(TdApi.GetFile(file.id))

    /** Keep [item] on the phone for offline use; TDLib downloads it in the background, across restarts. */
    suspend fun saveOffline(item: MediaItem) {
        val file = item.file ?: return
        Telegram.send(TdApi.AddFileToDownloads(file.id, item.chatId, item.messageId, 16))
    }

    /** Stop/undo an offline download and free the space. */
    suspend fun removeOffline(item: MediaItem) {
        val file = item.file ?: return
        try {
            Telegram.send(TdApi.RemoveFileFromDownloads(file.id, true))
        } catch (e: TdException) {
            Telegram.send(TdApi.DeleteFile(file.id)) // fully streamed but never added to the list
        }
    }
}

/** Read-side of Telegram: chats, media in a chat, search, single messages. */
object TelegramSource : LibrarySource {
    private val chatTitles = ConcurrentHashMap<Long, String>()
    private const val CHAT_LIMIT = 300

    override suspend fun chats(): List<ChatItem> {
        val limit = CHAT_LIMIT
        // loadChats fetches one chunk per call; repeat until TDLib holds `limit` chats or says there are no more.
        var ids = Telegram.send(TdApi.GetChats(TdApi.ChatListMain(), limit)).chatIds
        var rounds = 0
        while (ids.size < limit && rounds++ < 10) {
            try {
                Telegram.send(TdApi.LoadChats(TdApi.ChatListMain(), limit - ids.size))
            } catch (e: TdException) {
                if (e.code == 404) break // 404 = every chat is already loaded
                throw e
            }
            ids = Telegram.send(TdApi.GetChats(TdApi.ChatListMain(), limit)).chatIds
        }
        return coroutineScope { ids.map { async { Telegram.send(TdApi.GetChat(it)) } }.awaitAll() }.map { chat ->
            chatTitles[chat.id] = chat.title
            ChatItem(chat.id, chat.title, kindOf(chat.type), chat.photo?.small, chat.photo?.minithumbnail?.data)
        }
    }

    override suspend fun chatMedia(chatId: Long, filter: MediaFilter, fromMessageId: Long, query: String): Page<Long> {
        var from = fromMessageId
        // FILES pages can filter down to nothing (PDFs, APKs...); keep paging until something playable shows up.
        repeat(5) {
            val found = Telegram.send(TdApi.SearchChatMessages(chatId, null, query, null, from, 0, 50, filter.td()))
            val next = found.nextFromMessageId.takeIf { it != 0L }
            val items = found.messages.mapNotNull { toMediaItem(it) }
            if (items.isNotEmpty() || next == null) return Page(items, next)
            from = next
        }
        return Page(emptyList(), from)
    }

    /** Search every chat. Empty query + filter = the newest media across all chats. */
    override suspend fun search(query: String, filter: MediaFilter, offset: String): Page<String> {
        var cursor = offset
        repeat(5) {
            val found = Telegram.send(TdApi.SearchMessages(null, query, cursor, 50, filter.td(), null, 0, 0))
            val next = found.nextOffset.takeIf { it.isNotEmpty() }
            val items = found.messages.mapNotNull { toMediaItem(it) }
            if (items.isNotEmpty() || next == null) return Page(items, next)
            cursor = next
        }
        return Page(emptyList(), cursor)
    }

    override suspend fun downloads(): List<MediaItem> =
        // tails: first 200 only; page with next_offset if anyone keeps more offline.
        Telegram.send(TdApi.SearchFileDownloads("", false, false, "", 200)).files.mapNotNull { toMediaItem(it.message) }

    override suspend fun message(chatId: Long, messageId: Long): MediaItem? =
        try {
            toMediaItem(Telegram.send(TdApi.GetMessage(chatId, messageId)))
        } catch (e: TdException) {
            null // deleted, or chat left
        }

    private suspend fun chatTitle(chatId: Long): String =
        chatTitles[chatId] ?: try {
            Telegram.send(TdApi.GetChat(chatId)).title.also { chatTitles[chatId] = it }
        } catch (e: TdException) {
            ""
        }

    private suspend fun toMediaItem(m: TdApi.Message): MediaItem? {
        val chat = chatTitle(m.chatId)
        return when (val c = m.content) {
            is TdApi.MessageVideo -> c.video.let { v ->
                MediaItem(
                    m.chatId, m.id, titleOf(c.caption, v.fileName, "Video"), chat, MediaKind.VIDEO,
                    v.duration, v.video.size, m.date, v.video, v.thumbnail?.file, v.minithumbnail?.data, v.width, v.height,
                    type = containerType(v.mimeType, v.fileName),
                )
            }
            is TdApi.MessageAudio -> c.audio.let { a ->
                val name = listOf(a.performer, a.title).filter { it.isNotBlank() }.joinToString(" – ")
                MediaItem(
                    m.chatId, m.id, name.ifBlank { titleOf(c.caption, a.fileName, "Audio") }, chat, MediaKind.AUDIO,
                    a.duration, a.audio.size, m.date, a.audio, a.albumCoverThumbnail?.file, a.albumCoverMinithumbnail?.data, 1, 1,
                    type = containerType(a.mimeType, a.fileName),
                )
            }
            is TdApi.MessageDocument -> c.document.let { d ->
                val kind = playableKind(d.mimeType, d.fileName) ?: return null
                MediaItem(
                    m.chatId, m.id, titleOf(c.caption, d.fileName, "File"), chat, kind,
                    0, d.document.size, m.date, d.document, d.thumbnail?.file, d.minithumbnail?.data,
                    type = containerType(d.mimeType, d.fileName),
                )
            }
            else -> null
        }
    }

    private fun kindOf(type: TdApi.ChatType) = when (type) {
        is TdApi.ChatTypeSupergroup -> if (type.isChannel) ChatKind.CHANNEL else ChatKind.GROUP
        is TdApi.ChatTypeBasicGroup -> ChatKind.GROUP
        else -> ChatKind.PERSON
    }
}

private val videoExt = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "3gp", "flv", "wmv")
private val audioExt = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "alac")

internal fun playableKind(mime: String?, fileName: String?): MediaKind? {
    val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
    return when {
        mime?.startsWith("video/") == true || ext in videoExt -> MediaKind.VIDEO
        mime?.startsWith("audio/") == true || ext in audioExt -> MediaKind.AUDIO
        else -> null
    }
}

private val containerByExt = mapOf(
    "mkv" to "video/x-matroska", "mka" to "audio/x-matroska", "webm" to "video/webm",
    "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/mp4", "3gp" to "video/mp4", "m4a" to "audio/mp4",
    "ts" to "video/mp2t", "avi" to "video/x-msvideo", "flv" to "video/x-flv",
    "mp3" to "audio/mpeg", "flac" to "audio/flac", "ogg" to "audio/ogg", "opus" to "audio/ogg", "wav" to "audio/wav",
)

/**
 * The container type, so the player tries the right format reader first: a `tdlib://` link has no extension,
 * and without a type Media3 tries a fixed list (FLV, FLAC, WAV, MP4 … Matroska near the end), each reading
 * the file's head. The extension wins (an MKV sent as a file is `application/octet-stream`); a wrong type
 * only costs one failed try.
 */
internal fun containerType(mime: String?, fileName: String?): String? =
    fileName?.substringAfterLast('.', "")?.lowercase()?.let(containerByExt::get)
        ?: mime?.takeIf { it.startsWith("video/") || it.startsWith("audio/") }

/** Channel captions usually name the movie/episode; fall back to a cleaned-up file name. */
internal fun titleOf(caption: TdApi.FormattedText?, fileName: String?, fallback: String): String {
    caption?.text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }?.let { return it.take(120) }
    val name = fileName?.substringBeforeLast('.')?.replace('_', ' ')?.replace('.', ' ')?.trim()
    return if (name.isNullOrEmpty()) fallback else name
}
