package app.tgplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.lifecycle.lifecycleScope
import app.tgplayer.data.ChatItem
import app.tgplayer.data.ChatKind
import app.tgplayer.data.Library
import app.tgplayer.data.LibrarySource
import app.tgplayer.data.MediaFilter
import app.tgplayer.data.MediaItem
import app.tgplayer.data.MediaKind
import app.tgplayer.data.Page
import app.tgplayer.data.Ref
import app.tgplayer.data.SavedList
import app.tgplayer.data.Store
import app.tgplayer.ui.TelegramRoot
import app.tgplayer.ui.CinemaTheme
import app.tgplayer.ui.enableCinemaEdgeToEdge
import kotlinx.coroutines.launch

/** Debug-only: the signed-in app on sample data (no Telegram calls), to check the UI without logging in. */
class DemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableCinemaEdgeToEdge()
        // Lets adb screenshots work while the phone is locked; the lock itself stays on (debug builds only).
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        Library.source = DemoSource
        Store.init(this, "demo.db")
        app.tgplayer.data.Settings.init(this)
        lifecycleScope.launch {
            val v = DemoSource.videos
            Store.saveProgress(Ref(v[0].chatId, v[0].messageId), 41 * 60_000L, 128 * 60_000L)
            Store.saveProgress(Ref(v[3].chatId, v[3].messageId), 12 * 60_000L, 47 * 60_000L)
            Store.setSaved(SavedList.WATCH_LATER, Ref(v[5].chatId, v[5].messageId), true)
            Store.setSaved(SavedList.WATCH_LATER, Ref(v[6].chatId, v[6].messageId), true)
            Store.setSaved(SavedList.FAVOURITES, Ref(v[1].chatId, v[1].messageId), true)
        }
        // `--es screen login` → login screen; `--es screen lock` → Telegram password lock; `--es screen shell` → the player shell (Video/Music/Browse/More);
        // default → the signed-in Telegram section.
        val screen = intent.getStringExtra("screen")
        setContent {
            CinemaTheme {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(app.tgplayer.ui.Cinema.Stage),
                ) {
                    when (screen) {
                        "login" -> LoginScreen(org.drinkless.tdlib.TdApi.AuthorizationStateWaitPhoneNumber(), androidx.compose.ui.Modifier.safeDrawingPadding())
                        "shell" -> app.tgplayer.ui.AppShell()
                        "lock" -> {
                            app.tgplayer.data.TelegramLock.init(this@DemoActivity)
                            app.tgplayer.ui.TelegramLockScreen(onUnlocked = {}, onExit = {}, onForgot = {})
                        }
                        else -> {
                            var account by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                            TelegramRoot(onExit = {}, onAccount = { account = true })
                            if (account) app.tgplayer.ui.TelegramAccountSheet(onDismiss = { account = false }, onLogout = { account = false })
                        }
                    }
                }
            }
        }
    }
}

private object DemoSource : LibrarySource {
    val chats = listOf(
        ChatItem(1, "Cinema Vault HD", ChatKind.CHANNEL),
        ChatItem(2, "Anime Weekly", ChatKind.CHANNEL),
        ChatItem(3, "Lo-fi Beats Archive", ChatKind.CHANNEL),
        ChatItem(4, "Movie Night Crew", ChatKind.GROUP),
        ChatItem(5, "Documentary Club", ChatKind.GROUP),
        ChatItem(6, "Saved Messages", ChatKind.PERSON),
    )

    private fun video(chat: Int, id: Long, title: String, minutes: Int, gb: Double) = MediaItem(
        chats[chat].id, id, title, chats[chat].title, MediaKind.VIDEO, minutes * 60, (gb * (1L shl 30)).toLong(), 0,
    )

    val videos = listOf(
        video(0, 101, "Interstellar Drift (2024) 1080p", 128, 2.4),
        video(0, 102, "The Last Lighthouse", 112, 1.9),
        video(1, 201, "Skyborne — Episode 07", 24, 0.35),
        video(4, 501, "Planet Deep: Oceans Part 2", 47, 0.8),
        video(1, 202, "Skyborne — Episode 08", 24, 0.36),
        video(3, 401, "Neon Streets: Director's Cut", 141, 3.1),
        video(4, 502, "The Quiet Glaciers", 52, 0.9),
        video(0, 103, "Midnight Express Remastered", 104, 2.0),
        video(5, 601, "Trip vlog — Munnar", 18, 0.2),
        video(1, 203, "Skyborne — Episode 09", 24, 0.34),
    )

    private val music = listOf(
        MediaItem(3, 301, "Kaito – Rainy Window", "Lo-fi Beats Archive", MediaKind.AUDIO, 188, 6_000_000, 0, width = 1, height = 1),
        MediaItem(3, 302, "Mira – Late Train Home", "Lo-fi Beats Archive", MediaKind.AUDIO, 204, 6_500_000, 0, width = 1, height = 1),
    )

    override suspend fun chats() = chats

    override suspend fun chatMedia(chatId: Long, filter: MediaFilter, fromMessageId: Long, query: String): Page<Long> =
        Page(pool(filter).filter { it.chatId == chatId && it.title.contains(query, true) }, null)

    override suspend fun search(query: String, filter: MediaFilter, offset: String): Page<String> =
        Page(pool(filter).filter { it.title.contains(query, true) }, null)

    override suspend fun message(chatId: Long, messageId: Long) =
        (videos + music).firstOrNull { it.chatId == chatId && it.messageId == messageId }

    override suspend fun downloads() = listOf(videos[2], videos[4])

    private fun pool(filter: MediaFilter) = if (filter == MediaFilter.AUDIO) music else videos
}
