package app.tgplayer.player

import android.graphics.BitmapFactory
import android.os.Build
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.cast.MediaRouteButton
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.tgplayer.ui.Cinema
import app.tgplayer.ui.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

private enum class MusicSheet { OPTIONS, EQUALIZER, QUEUE }

/**
 * Now Playing for music: cover art (from the file's tags, else [fallbackArt]) over a blurred copy of itself,
 * title / artist / album, seek bar, shuffle and repeat, and the equalizer, sleep timer, speed and queue.
 * Swipe the cover sideways to skip. Clean-room design; the sheets are the video player's own.
 */
/**
 * A counter bumped on every Player event. Read it in composition to re-read live player state
 * (metadata, isPlaying, shuffle/repeat) that isn't Compose-observable. Extracted from the identical
 * listener MusicPlayerScreen and MiniBar both had.
 */
@Composable
fun rememberPlayerEvents(player: Player): Int {
    var version by remember(player) { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { version++ }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return version
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@OptIn(UnstableApi::class)
@Composable
fun MusicPlayerScreen(player: Player, host: PlayerHost, fallbackArt: @Composable (Modifier) -> Unit) {
    val version = rememberPlayerEvents(player)
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<MusicSheet?>(null) }
    val timers = remember { PlaybackTimers() }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
            buffered = player.bufferedPosition
            timers.tick(player, position, duration)?.let { feedback = it }
            delay(250)
        }
    }
    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(1_500)
            feedback = null
        }
    }

    version // re-read the player's state after every change
    val meta = player.mediaMetadata
    val title = meta.title?.toString()?.ifBlank { null } ?: host.title
    val artist = (meta.artist ?: meta.albumArtist)?.toString()?.ifBlank { null } ?: host.subtitle.ifBlank { null }
    val album = meta.albumTitle?.toString()?.ifBlank { null }
    val artData = meta.artworkData
    val art by produceState<ImageBitmap?>(null, artData) {
        value = artData?.let { bytes ->
            withContext(Dispatchers.Default) { runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull() }
        }
    }
    val playing = player.isPlaying
    val buffering = player.playbackState == Player.STATE_BUFFERING && player.playWhenReady

    Box(Modifier.fillMaxSize().background(Cinema.Stage)) {
        // Backdrop: the cover, blurred (Android 12+ can blur), under a dark wash so text stays readable.
        art?.takeIf { Build.VERSION.SDK_INT >= 31 }?.let {
            Image(it, null, Modifier.fillMaxSize().blur(70.dp), contentScale = ContentScale.Crop, alpha = 0.55f)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Cinema.Stage.copy(alpha = 0.92f)))))

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = host::close) { Icon(Icons.Rounded.KeyboardArrowDown, "Close", Modifier.size(32.dp), tint = Color.White) }
                Text("Now playing", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Cinema.TextDim, textAlign = TextAlign.Center)
                CompositionLocalProvider(LocalContentColor provides Color.White) { MediaRouteButton() }
                IconButton(onClick = { sheet = MusicSheet.OPTIONS }) { Icon(Icons.Rounded.MoreVert, "More options", tint = Color.White) }
            }

            Spacer(Modifier.weight(1f))
            // Cover: swipe left for the next song, right for the previous one.
            var drag by remember { mutableFloatStateOf(0f) }
            Box(
                Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(22.dp))
                    .pointerInput(player) {
                        detectHorizontalDragGestures(
                            onDragStart = { drag = 0f },
                            onDragEnd = {
                                if (abs(drag) > size.width / 4f) if (drag < 0) player.seekToNext() else player.seekToPrevious()
                                drag = 0f
                            },
                            onHorizontalDrag = { change, amount -> drag += amount; change.consume() },
                        )
                    },
            ) {
                val a = art
                if (a != null) Image(a, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else fallbackArt(Modifier.fillMaxSize())
            }
            Spacer(Modifier.weight(1f))

            Column(Modifier.fillMaxWidth()) {
                Text(title, Modifier.basicMarquee(), style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1)
                artist?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Cinema.AccentText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                album?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Cinema.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Spacer(Modifier.height(14.dp))
            SeekBar(position, duration, buffered, host.downloadedFraction, null) { player.seekTo(it) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                Text(formatDuration((position / 1000).toInt()), style = MaterialTheme.typography.labelMedium, color = Cinema.TextDim)
                Spacer(Modifier.weight(1f))
                Text("−" + formatDuration(((duration - position).coerceAtLeast(0) / 1000).toInt()), style = MaterialTheme.typography.labelMedium, color = Cinema.TextDim)
            }

            // Transport
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val shuffle = player.shuffleModeEnabled
                IconButton(onClick = { player.shuffleModeEnabled = !shuffle; feedback = if (shuffle) "Shuffle off" else "Shuffle on" }) {
                    Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (shuffle) Cinema.AccentFill else Color.White.copy(alpha = 0.7f))
                }
                IconButton(onClick = player::seekToPrevious, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(38.dp), tint = Color.White) }
                Box(
                    Modifier.size(76.dp).clip(CircleShape).background(Cinema.AccentFill).clickable { if (playing) player.pause() else player.play() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(42.dp), tint = Cinema.OnAccent)
                    if (buffering) CircularProgressIndicator(Modifier.size(76.dp), color = Cinema.OnAccent.copy(alpha = 0.6f), strokeWidth = 3.dp)
                }
                IconButton(onClick = player::seekToNext, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(38.dp), tint = Color.White) }
                val repeat = player.repeatMode
                IconButton(onClick = {
                    val next = when (repeat) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
                    player.repeatMode = next
                    feedback = when (next) { Player.REPEAT_MODE_ALL -> "Repeat all"; Player.REPEAT_MODE_ONE -> "Repeat this song"; else -> "Repeat off" }
                }) {
                    Icon(
                        if (repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat",
                        tint = if (repeat != Player.REPEAT_MODE_OFF) Cinema.AccentFill else Color.White.copy(alpha = 0.7f),
                    )
                }
            }

            // Tools
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Tool(Icons.Rounded.Equalizer, "Equalizer", AudioFx.eq.enabled) { sheet = MusicSheet.EQUALIZER }
                Tool(Icons.Rounded.Bedtime, if (timers.sleepChoice != 0) "Sleep on" else "Sleep", timers.sleepChoice != 0) { sheet = MusicSheet.OPTIONS }
                Tool(Icons.Rounded.Speed, "${player.playbackParameters.speed.toString().removeSuffix(".0")}×", player.playbackParameters.speed != 1f) { sheet = MusicSheet.OPTIONS }
                Tool(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", false) { sheet = MusicSheet.QUEUE }
            }
        }

        feedback?.let {
            Text(
                it,
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                color = Color.White, style = MaterialTheme.typography.titleMedium,
            )
        }
    }

    when (sheet) {
        MusicSheet.OPTIONS -> ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Cinema.Panel) {
            OptionsSheet(player, timers) { sheet = MusicSheet.EQUALIZER }
        }
        MusicSheet.EQUALIZER -> ModalBottomSheet(
            onDismissRequest = { sheet = null }, containerColor = Cinema.Panel,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) { EqualizerSheet() }
        MusicSheet.QUEUE -> ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Cinema.Panel) { QueueSheet(player, version) }
        null -> Unit
    }
}

@Composable
private fun Tool(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val tint = if (active) Cinema.AccentText else Color.White.copy(alpha = 0.8f)
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = tint)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The play queue in playlist order: tap a song to play it, ✕ to take it out. [version] re-reads it after changes. */
@Composable
private fun QueueSheet(player: Player, version: Int) {
    version
    val count = player.mediaItemCount
    val current = player.currentMediaItemIndex
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        SheetTitle("Queue · $count song${if (count == 1) "" else "s"}")
        if (player.shuffleModeEnabled) Dim("Shuffle is on: songs play in a random order, listed here in the original one.")
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), state = list) {
            items(count) { i ->
                val item = player.getMediaItemAt(i)
                val isCurrent = i == current
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (isCurrent) Cinema.PanelHigh else Color.Transparent)
                        .clickable { player.seekTo(i, 0); player.play() }
                        .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", Modifier.width(34.dp), style = MaterialTheme.typography.labelMedium, color = if (isCurrent) Cinema.AccentText else Cinema.TextDim)
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(
                            item.mediaMetadata.title?.toString() ?: "Song ${i + 1}",
                            style = MaterialTheme.typography.bodyLarge, color = if (isCurrent) Cinema.AccentText else Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        item.mediaMetadata.artist?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim, maxLines = 1) }
                    }
                    if (!isCurrent) IconButton(onClick = { player.removeMediaItem(i) }) { Icon(Icons.Rounded.Close, "Remove from queue", tint = Cinema.TextDim) }
                    else Spacer(Modifier.size(48.dp))
                }
            }
        }
    }
}
