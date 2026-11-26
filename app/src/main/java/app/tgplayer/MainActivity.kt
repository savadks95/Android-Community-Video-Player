package app.tgplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.tgplayer.ui.enableCinemaEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.tgplayer.data.MediaLibrary
import app.tgplayer.data.Settings
import app.tgplayer.data.Store
import app.tgplayer.ui.AppEvents
import app.tgplayer.ui.AppShell
import app.tgplayer.ui.CinemaTheme
import app.tgplayer.ui.LaunchIntro

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableCinemaEdgeToEdge()
        Store.init(this)
        Settings.init(this)
        MediaLibrary.init(this)
        MediaLibrary.load() // rescans in the background when the last scan is over an hour old
        // Telegram (TDLib) starts only when the user opens Telegram from Browse.
        setContent {
            CinemaTheme {
                // Saveable: replays on each launch, not on rotation or when returning from the background.
                var intro by rememberSaveable { mutableStateOf(true) }
                Box {
                    AppShell()
                    if (intro) LaunchIntro { intro = false }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppEvents.resumed.value++ // e.g. file access may have just been granted in system settings
    }
}
