package app.tgplayer.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.tgplayer.BuildConfig
import app.tgplayer.data.Settings
import app.tgplayer.data.Storage
import app.tgplayer.data.Store
import app.tgplayer.player.OnlineSubtitles
import app.tgplayer.player.StreamCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.reflect.KMutableProperty0

/** Settings: playback, network buffering, storage, files, about. */
@Composable
fun MoreScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var limit by remember { mutableIntStateOf(Settings.cacheLimitGb) }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val networkCache by produceState(0L, refresh) { value = withContext(Dispatchers.IO) { runCatching { StreamCache.usedBytes(context) }.getOrDefault(0L) } }

    LazyColumn(modifier, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp)) {
        item { BrandHeader("More", "Settings and storage") }

        item { Group("Playback") }
        item { Toggle("Resume where you left off", "Local files and links start from the last position", Settings::resume) }
        item { Toggle("Play next file automatically", "When a file ends, play the next one in the folder. Applies the next time the player starts.", Settings::autoPlayNext) }
        item { Toggle("Background play for videos", "Keep the sound going after leaving the player", Settings::backgroundVideo) }
        item { Toggle("Picture-in-picture on leave", "Pressing Home while a video plays shrinks it to a small window", Settings::autoPip) }
        item {
            Toggle(
                "Find subtitles online",
                if (OnlineSubtitles.available) "When a video has no subtitles of its own or beside it, look one up on OpenSubtitles.com by its name and file fingerprint"
                else "Needs an OpenSubtitles API key in this build (opensubtitles.apiKey in local.properties)",
                Settings::findSubtitlesOnline,
            )
        }
        item { Toggle("Prefer FFmpeg audio decoder", "Use FFmpeg before the phone's own decoders. Turn on if some files have no sound. Applies the next time the player starts.", Settings::preferFfmpeg) }

        item { Group("Network streams") }
        item { Toggle("Download ahead to disk", "Links keep buffering to storage while paused, instead of a small memory buffer", Settings::readAhead) }

        item { Group("Storage") }
        item {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text("Streaming cache limit", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Shared by Telegram and network links. Older streamed files are removed first; offline downloads never are.",
                    style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim,
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    PillTabs(Storage.limitChoicesGb, limit, { "$it GB" }, {
                        limit = it
                        Settings.cacheLimitGb = it
                        message = "Network cache limit applies after the app restarts"
                    })
                }
            }
        }
        item {
            ActionRow("Clear network cache", "Using ${formatSize(networkCache).ifEmpty { "nothing" }}") {
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { StreamCache.clear(context) } }
                    refresh++
                    message = "Network cache cleared"
                }
            }
        }
        item {
            ActionRow("Clear watch history", "Forget positions for local files and links") {
                scope.launch { Store.clearHistory(); message = "History cleared" }
            }
        }

        item { Group("Files") }
        item { Toggle("Show hidden files", "Include folders and files whose names start with a dot", Settings::showHiddenFiles) }

        message?.let { item { Text(it, Modifier.padding(vertical = 10.dp), color = Cinema.AccentText, style = MaterialTheme.typography.bodySmall) } }

        item { Group("About") }
        item {
            Text(
                "TG Player ${BuildConfig.VERSION_NAME}\nTDLib 1.8.70 · Media3 1.11.1 · FFmpeg 6.0 audio decoders · dav1d 1.5.5 AV1 decoder\nAll built from official sources.\n\n" +
                    "An unofficial app built on the Telegram API; not made or endorsed by Telegram.",
                Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim,
            )
        }
        // TMDB's API terms require this notice and a link back for the Library's posters and details.
        item {
            ActionRow("Movie and TV data from TMDB", "This product uses the TMDB API but is not endorsed or certified by TMDB.") {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.themoviedb.org"))) }
            }
        }
        // Google Play requires a privacy policy link inside the app as well as on the store listing.
        item {
            ActionRow("Privacy policy", "What TG Player keeps on this phone and what it sends where") {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))) }
            }
        }
    }
}

const val PRIVACY_POLICY_URL = "https://github.com/savadks95/Android-Community-Video-Player/blob/main/docs/privacy-policy.md"

@Composable
private fun Group(title: String) =
    SectionTitle(title, Modifier.padding(top = 26.dp, bottom = 6.dp))

@Composable
private fun Toggle(title: String, note: String, setting: KMutableProperty0<Boolean>) {
    var on by remember { mutableStateOf(setting.get()) }
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { on = !on; setting.set(on) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(note, style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim)
        }
        Switch(on, { on = it; setting.set(it) }, colors = SwitchDefaults.colors(checkedTrackColor = Cinema.AccentFill))
    }
}

@Composable
private fun ActionRow(title: String, note: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Cinema.Danger)
        Text(note, style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim)
    }
}
