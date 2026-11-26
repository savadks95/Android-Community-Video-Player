package app.tgplayer.data

import android.content.Context
import app.tgplayer.Telegram
import org.drinkless.tdlib.TdApi

/**
 * Keeps streamed video/music under the user's size limit, oldest-played first, and never touches
 * offline downloads (TDLib's own optimizeStorage can't exclude those, so it isn't used).
 */
object Storage {
    const val GB = 1L shl 30
    val limitChoicesGb = listOf(2, 5, 10, 20)

    /** Set by the player so a trim never deletes what is playing right now. */
    @Volatile var nowPlaying: Ref? = null

    fun init(context: Context) = Settings.init(context)

    var limitGb: Int
        get() = Settings.cacheLimitGb
        set(value) { Settings.cacheLimitGb = value }

    data class Usage(val streamedBytes: Long, val offlineBytes: Long)

    suspend fun usage(): Usage = scan(deleteOver = null)

    /** Deletes streamed files beyond the limit; [limitBytes] 0 clears the whole streaming cache. */
    suspend fun trim(limitBytes: Long = limitGb * GB): Usage = scan(deleteOver = limitBytes)

    private suspend fun scan(deleteOver: Long?): Usage {
        val offline = Library.downloads()
        val offlineIds = offline.mapNotNullTo(HashSet()) { it.file?.id }
        val offlineBytes = offline.sumOf { it.file?.local?.downloadedSize ?: 0L }
        var kept = 0L
        for (ref in Store.playedByRecency()) {
            val file = Library.message(ref.chatId, ref.messageId)?.file ?: continue
            if (file.id in offlineIds) continue
            val size = Library.fileState(file).local.downloadedSize
            if (size == 0L) continue
            if (deleteOver == null || ref == nowPlaying || kept + size <= deleteOver) {
                kept += size
            } else {
                runCatching { Telegram.send(TdApi.DeleteFile(file.id)) }
            }
        }
        // tails: only counts files that reached the player's progress table; a video closed within
        // a second isn't tracked. Add updateFile bookkeeping if that leak ever matters.
        return Usage(kept, offlineBytes)
    }
}
