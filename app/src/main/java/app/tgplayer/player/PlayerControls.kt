package app.tgplayer.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.media3.cast.MediaRouteButton
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import app.tgplayer.data.Settings
import app.tgplayer.ui.Cinema
import app.tgplayer.ui.PillTabs
import app.tgplayer.ui.formatDuration
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private enum class Sheet { TRACKS, OPTIONS, EQUALIZER }

private val resizeModes = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
)

/**
 * MX/VLC-style controls over the video. Gestures: tap shows/hides, double-tap left/right seeks 10 s
 * (centre toggles play), swipe sideways to seek, swipe up/down on the left for brightness and on the
 * right for volume, pinch to zoom. Lock disables all of it until unlocked.
 */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@OptIn(UnstableApi::class)
@Composable
fun PlayerOverlay(player: Player, host: PlayerHost, audioOnly: Boolean) {
    var visible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(player.isPlaying) }
    var buffering by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var seekPreview by remember { mutableStateOf<Long?>(null) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val timers = remember { PlaybackTimers() }

    fun touch() {
        lastTouch = SystemClock.uptimeMillis()
        visible = true
    }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
            buffered = player.bufferedPosition
            playing = player.isPlaying
            buffering = player.playbackState == Player.STATE_BUFFERING && player.playWhenReady
            timers.tick(player, position, duration)?.let { feedback = it }
            val idle = SystemClock.uptimeMillis() - lastTouch > 3_500
            if (visible && playing && sheet == null && !audioOnly && idle) visible = false
            delay(250)
        }
    }
    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(900)
            feedback = null
        }
    }
    // TV remote: the overlay itself holds focus while the controls are hidden; when they hide, take it back.
    val focus = remember { FocusRequester() }
    LaunchedEffect(visible) { if (!visible) runCatching { focus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onPreviewKeyEvent { if (visible) lastTouch = SystemClock.uptimeMillis(); false } // keys keep the controls up
            .onKeyEvent { e ->
                // Controls hidden: ←/→ seek 10 s, OK plays/pauses, ↑/↓ bring the controls. Shown: the arrows move between buttons.
                if (e.type != KeyEventType.KeyDown || locked || visible) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); feedback = "« 10 s"; true }
                    Key.DirectionRight -> { player.seekTo(player.currentPosition + 10_000); feedback = "10 s »"; true }
                    Key.DirectionCenter, Key.Enter -> { if (player.isPlaying) player.pause() else player.play(); touch(); true }
                    Key.DirectionUp, Key.DirectionDown -> { touch(); true }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(locked) {
                detectTapGestures(
                    onTap = {
                        if (visible) visible = false else touch()
                    },
                    onDoubleTap = { at ->
                        if (locked) return@detectTapGestures
                        // Wide side zones (40 % each, everything beside the transport buttons); the middle 20 % toggles play.
                        when {
                            at.x < size.width * 0.4f -> {
                                player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); feedback = "« 10 s"
                            }
                            at.x > size.width * 0.6f -> {
                                player.seekTo(player.currentPosition + 10_000); feedback = "10 s »"
                            }
                            else -> if (player.isPlaying) player.pause() else player.play()
                        }
                    },
                )
            }
            .pointerInput(locked) {
                if (locked) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var mode = 0 // 1 seek, 2 brightness, 3 volume, 4 pinch
                    var zoom = 1f
                    val start = player.currentPosition
                    val total = max(player.duration, 0)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            mode = 4
                            zoom *= event.calculateZoom()
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val change = pressed.first()
                        val d = change.position - down.position
                        if (mode == 0 && d.getDistance() > viewConfiguration.touchSlop * 1.5f) {
                            mode = when {
                                abs(d.x) > abs(d.y) -> 1
                                down.position.x < size.width / 2f -> 2
                                else -> 3
                            }
                        }
                        when (mode) {
                            1 -> {
                                val delta = (d.x / size.width * 90_000).toLong() // a full-width swipe = 90 s
                                val target = (start + delta).coerceIn(0, if (total > 0) total else Long.MAX_VALUE)
                                seekPreview = target
                                feedback = "${if (delta >= 0) "+" else "−"}${formatDuration((abs(delta) / 1000).toInt())}\n${formatDuration((target / 1000).toInt())}"
                            }
                            2 -> feedback = "Brightness ${(host.adjustBrightness(-change.positionChange().y / size.height) * 100).toInt()}%"
                            3 -> feedback = "Volume ${(host.adjustVolume(-change.positionChange().y / size.height) * 100).toInt()}%"
                        }
                        if (mode != 0) change.consume()
                    }
                    when (mode) {
                        1 -> seekPreview?.let(player::seekTo).also { seekPreview = null }
                        4 -> when {
                            zoom > 1.12f -> { host.setResize(AspectRatioFrameLayout.RESIZE_MODE_ZOOM); feedback = "Zoom" }
                            zoom < 0.9f -> { host.setResize(AspectRatioFrameLayout.RESIZE_MODE_FIT); feedback = "Fit" }
                        }
                    }
                }
            },
    ) {
        // Shown even with the controls hidden, so a slow link never looks like "nothing happens".
        if (buffering) {
            val controlsUp = visible && !locked
            Column(
                Modifier.align(Alignment.Center).padding(top = if (controlsUp) 170.dp else 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!controlsUp) CircularProgressIndicator(color = Cinema.AccentText, strokeWidth = 3.dp)
                Spacer(Modifier.height(10.dp))
                Text(
                    host.downloadedFraction?.let { "Buffering… ${(it * 100).toInt()}% downloaded" } ?: "Buffering…",
                    Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 6.dp),
                    color = Color.White, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (locked) {
            AnimatedVisibility(visible, Modifier.align(Alignment.CenterStart).safeDrawingPadding().padding(16.dp), enter = fadeIn(), exit = fadeOut()) {
                RoundIcon(Icons.Rounded.Lock, "Unlock") { locked = false; touch() }
            }
        } else AnimatedVisibility(visible, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                // Top bar
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
                        .safeDrawingPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = host::close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(host.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (host.subtitle.isNotEmpty()) Text(host.subtitle, style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim, maxLines = 1)
                    }
                    // Chromecast / Google TV picker; shows once Google Play services' Cast is ready.
                    CompositionLocalProvider(LocalContentColor provides Color.White) { MediaRouteButton() }
                    IconButton(onClick = { sheet = Sheet.TRACKS }) { Icon(Icons.Rounded.Subtitles, "Audio and subtitles", tint = Color.White) }
                    IconButton(onClick = { sheet = Sheet.OPTIONS }) { Icon(Icons.Rounded.MoreVert, "More options", tint = Color.White) }
                }

                // Centre transport
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (player.hasPreviousMediaItem()) RoundIcon(Icons.Rounded.SkipPrevious, "Previous") { player.seekToPreviousMediaItem(); touch() }
                    RoundIcon(Icons.Rounded.Replay10, "Back 10 seconds") { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); touch() }
                    Box(
                        Modifier.size(76.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable {
                            if (player.isPlaying) player.pause() else player.play()
                            touch()
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(42.dp), tint = Color.White)
                        if (buffering) CircularProgressIndicator(Modifier.size(76.dp), color = Color.White.copy(alpha = 0.8f), strokeWidth = 3.dp)
                    }
                    RoundIcon(Icons.Rounded.Forward10, "Forward 10 seconds") { player.seekTo(player.currentPosition + 10_000); touch() }
                    if (player.hasNextMediaItem()) RoundIcon(Icons.Rounded.SkipNext, "Next") { player.seekToNextMediaItem(); touch() }
                }

                // Bottom: seek bar + tools
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                        .safeDrawingPadding().padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    SeekBar(position, duration, buffered, host.downloadedFraction, seekPreview) { player.seekTo(it); touch() }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${formatDuration((position / 1000).toInt())} / ${formatDuration((duration / 1000).toInt())}",
                            style = MaterialTheme.typography.labelLarge, color = Color.White,
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { locked = true; feedback = "Locked" }) { Icon(Icons.Rounded.LockOpen, "Lock controls", tint = Color.White) }
                        if (!audioOnly) {
                            IconButton(onClick = {
                                val i = resizeModes.indexOfFirst { it.first == host.resizeMode }
                                val next = resizeModes[(i + 1) % resizeModes.size]
                                host.setResize(next.first); feedback = next.second; touch()
                            }) { Icon(Icons.Rounded.AspectRatio, "Aspect ratio", tint = Color.White) }
                            IconButton(onClick = { feedback = host.cycleRotation(); touch() }) { Icon(Icons.Rounded.ScreenRotation, "Rotation", tint = Color.White) }
                            IconButton(onClick = host::enterPip) { Icon(Icons.Rounded.PictureInPictureAlt, "Picture in picture", tint = Color.White) }
                        }
                        Text(
                            "${player.playbackParameters.speed.toString().removeSuffix(".0")}×",
                            Modifier.clip(RoundedCornerShape(50)).clickable { sheet = Sheet.OPTIONS }.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge, color = Cinema.AccentText,
                        )
                    }
                }
            }
        }

        // Jellyfin intro/recap: a Skip button while it plays, with or without the controls (like streaming apps).
        if (!locked) host.skippable.firstOrNull { (_, range) -> position >= range.first && position < range.last - 1_000 }?.let { (kind, range) ->
            Text(
                if (kind == "Recap") "Skip recap" else "Skip intro",
                Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(end = 16.dp, bottom = 110.dp)
                    .clip(RoundedCornerShape(50)).background(Color.White).clickable { player.seekTo(range.last); touch() }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                color = Color.Black, style = MaterialTheme.typography.labelLarge,
            )
        }

        // Brightness, volume, seek, zoom…: drawn last so nothing covers it. With the controls up it sits
        // above the play button (bottom padding shifts the centred box up by half of it).
        feedback?.let {
            Text(
                it,
                Modifier.align(Alignment.Center).padding(bottom = if (visible && !locked) 190.dp else 0.dp)
                    .clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                color = Color.White, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
            )
        }
    }

    when (sheet) {
        Sheet.TRACKS -> ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Cinema.Panel) { TracksSheet(player, host) }
        Sheet.OPTIONS -> ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Cinema.Panel) { OptionsSheet(player, timers) { sheet = Sheet.EQUALIZER } }
        Sheet.EQUALIZER -> ModalBottomSheet(
            onDismissRequest = { sheet = null }, containerColor = Cinema.Panel,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) { EqualizerSheet() }
        null -> Unit
    }
}

/** Seek bar with what's buffered (white) and, for Telegram/links, what's downloaded to disk (cyan) behind it. */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(position: Long, duration: Long, buffered: Long, downloaded: Float?, preview: Long?, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    val max = duration.coerceAtLeast(1).toFloat()
    val state = remember(max) { SliderState(0f, 0, 0f..max) }
    LaunchedEffect(position, preview, max) {
        if (!dragging) state.value = (preview ?: position).toFloat().coerceIn(0f, max)
    }
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.15f))) {
            downloaded?.let { Box(Modifier.fillMaxHeight().fillMaxWidth(it.coerceIn(0f, 1f)).background(Cinema.AccentText.copy(alpha = 0.45f))) }
            Box(Modifier.fillMaxHeight().fillMaxWidth((buffered / max).coerceIn(0f, 1f)).background(Color.White.copy(alpha = 0.35f)))
        }
        val colors = SliderDefaults.colors(
            thumbColor = Color.White, activeTrackColor = Cinema.AccentFill, inactiveTrackColor = Color.Transparent,
        )
        val interaction = remember { MutableInteractionSource() }
        Slider(
            state = state,
            onValueChange = { dragging = true; state.value = it },
            onValueChangeFinished = { onSeek(state.value.toLong()); dragging = false },
            colors = colors,
            interactionSource = interaction,
            // Thin played bar matching the 3dp buffered bar behind it; half-height (22dp) thumb.
            thumb = { SliderDefaults.Thumb(interaction, colors = colors, thumbSize = DpSize(4.dp, 22.dp)) },
            track = {
                SliderDefaults.Track(
                    it, Modifier.height(3.dp), colors = colors,
                    drawStopIndicator = null, thumbTrackGapSize = 0.dp,
                )
            },
        )
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(30.dp), tint = Color.White) }
}

// ─── Sheets ───────────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
private fun TracksSheet(player: Player, host: PlayerHost) {
    var refresh by remember { mutableStateOf(0) }
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
        key(refresh) {
            SheetTitle("Audio")
            val audio = trackOptions(player, C.TRACK_TYPE_AUDIO)
            if (audio.isEmpty()) Dim("No audio tracks")
            audio.forEach { t -> ChoiceLine(t.label, t.selected, t.supported) { t.select(); refresh++ } }

            SheetTitle("Subtitles")
            val textDisabled = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes
            ChoiceLine("Off", textDisabled, true) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                refresh++
            }
            trackOptions(player, C.TRACK_TYPE_TEXT).forEach { t -> ChoiceLine(t.label, t.selected && !textDisabled, t.supported) { t.select(); refresh++ } }
            ChoiceLine("Load subtitle file…", false, true) { host.pickSubtitleFile() }

            SheetTitle("Subtitle timing")
            var delay by remember { mutableStateOf(host.subtitleDelayMs) }
            var timingWorks by remember { mutableStateOf(true) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { timingWorks = host.setSubtitleDelay(delay - 250); if (timingWorks) delay -= 250 }) { Text("− 0.25 s") }
                Text(
                    if (delay == 0L) "In sync" else "%+.2f s".format(delay / 1000f),
                    Modifier.weight(1f), textAlign = TextAlign.Center, color = Color.White,
                )
                TextButton(onClick = { timingWorks = host.setSubtitleDelay(delay + 250); if (timingWorks) delay += 250 }) { Text("+ 0.25 s") }
            }
            if (delay != 0L) ChoiceLine("Reset timing", false, true) { if (host.setSubtitleDelay(0)) delay = 0 }
            if (!timingWorks) Dim("Timing works for subtitle files (beside the video, loaded, or found online), not ones built into the video.")

            SheetTitle("Subtitle size")
            var scale by remember { mutableFloatStateOf(host.subtitleScale) }
            ChoiceRow(listOf(0.04f to "Small", 0.0533f to "Normal", 0.07f to "Large", 0.09f to "Huge"), scale) { scale = it; host.setSubtitleScale(it) }
        }
    }
}

private class TrackOption(val label: String, val selected: Boolean, val supported: Boolean, val select: () -> Unit)

private fun trackOptions(player: Player, type: Int): List<TrackOption> = buildList {
    var n = 0
    for (group in player.currentTracks.groups) {
        if (group.type != type) continue
        for (i in 0 until group.length) {
            n++
            val f = group.getTrackFormat(i)
            val supported = group.isTrackSupported(i)
            add(TrackOption(trackLabel(f, n) + if (supported) "" else " (can't decode)", group.isTrackSelected(i), supported) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(type, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                    .build()
                // Remember the language, so the next file starts in it too.
                f.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }?.let {
                    if (type == C.TRACK_TYPE_AUDIO) Settings.preferredAudioLanguage = it else Settings.preferredSubtitleLanguage = it
                }
            })
        }
    }
}

private fun trackLabel(f: Format, n: Int): String {
    val language = f.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }?.let { Locale.forLanguageTag(it).displayLanguage }
    val channels = if (f.channelCount > 0) when (f.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${f.channelCount}ch" } else null
    // Subtitle files are converted to Media3's own cue format before they get here; that name means nothing to anyone.
    val codec = f.sampleMimeType?.takeIf { it != MimeTypes.APPLICATION_MEDIA3_CUES }?.substringAfter('/')?.uppercase()
    return listOfNotNull(f.label, language, channels, codec).distinct().joinToString(" · ").ifEmpty { "Track $n" }
}

@OptIn(UnstableApi::class)
@Composable
private fun OptionsSheet(player: Player, timers: PlaybackTimers, openEqualizer: () -> Unit) {
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    var sleep by remember { mutableStateOf(timers.sleepLabel()) }
    var ab by remember { mutableStateOf(timers.abLabel()) }
    var boost by remember { mutableStateOf(AudioFx.boostMb) }
    var background by remember { mutableStateOf(Settings.backgroundVideo) }
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
        SheetTitle("Playback speed")
        ChoiceRow(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f).map { it to "${it.toString().removeSuffix(".0")}×" }, speed) { speed = it; player.setPlaybackSpeed(it) }

        SheetTitle("Sleep timer")
        ChoiceRow(listOf(0 to "Off", 15 to "15 min", 30 to "30 min", 45 to "45 min", 60 to "1 h", -1 to "End of file"), timers.sleepChoice) {
            timers.setSleep(it); sleep = timers.sleepLabel()
        }
        if (sleep.isNotEmpty()) Dim(sleep)

        SheetTitle("A-B repeat")
        ChoiceRow(listOf(1 to "Set A", 2 to "Set B", 0 to "Clear"), -1) {
            when (it) { 1 -> timers.abA = player.currentPosition; 2 -> timers.abB = player.currentPosition; else -> { timers.abA = null; timers.abB = null } }
            ab = timers.abLabel()
        }
        if (ab.isNotEmpty()) Dim(ab)

        SheetTitle("Equalizer")
        val eq = AudioFx.eq
        ChoiceLine(if (eq.enabled) "On · ${AudioFx.presetName(eq.gains) ?: "Custom"}" else "Off", eq.enabled, true, openEqualizer)
        SheetTitle("Volume boost")
        ChoiceRow(listOf(0 to "Off", 300 to "+3 dB", 600 to "+6 dB", 1000 to "+10 dB"), boost) { boost = it; AudioFx.setBoost(it) }

        Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Background play", style = MaterialTheme.typography.titleSmall)
                Dim("Keep video sound playing after leaving the player")
            }
            Switch(background, { background = it; Settings.backgroundVideo = it }, colors = SwitchDefaults.colors(checkedTrackColor = Cinema.AccentFill))
        }

        SheetTitle("Media info")
        mediaInfo(player).ifEmpty { listOf("Not available yet") }.forEach { Dim(it) }
    }
}

@OptIn(UnstableApi::class)
private fun mediaInfo(player: Player): List<String> = player.currentTracks.groups.filter { it.isSelected }.flatMap { g ->
    (0 until g.length).filter { g.isTrackSelected(it) }.map { i ->
        val f = g.getTrackFormat(i)
        val codec = f.codecs ?: f.sampleMimeType?.substringAfter('/') ?: "?"
        val kbps = if (f.bitrate > 0) " · ${f.bitrate / 1000} kbps" else ""
        when (g.type) {
            C.TRACK_TYPE_VIDEO -> "Video: $codec · ${f.width}×${f.height}${if (f.frameRate > 0) " · %.0f fps".format(f.frameRate) else ""}$kbps"
            C.TRACK_TYPE_AUDIO -> "Audio: $codec · ${f.channelCount} ch · ${f.sampleRate} Hz$kbps${f.language?.let { " · $it" } ?: ""}"
            C.TRACK_TYPE_TEXT -> "Subtitles: ${f.label ?: f.language ?: codec}"
            else -> "Track: $codec"
        }
    }
}

/** Sleep timer and A-B loop, driven by the overlay's 250 ms tick. Lives as long as the player screen. */
private class PlaybackTimers {
    var sleepChoice = 0; private set
    private var sleepAt: Long? = null
    var abA: Long? = null
    var abB: Long? = null

    fun setSleep(minutes: Int) {
        sleepChoice = minutes
        sleepAt = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else null
    }

    fun sleepLabel(): String = when {
        sleepChoice == -1 -> "Pauses at the end of this file"
        sleepAt != null -> "Pauses in ${((sleepAt!! - SystemClock.elapsedRealtime()) / 60_000) + 1} min"
        else -> ""
    }

    fun abLabel(): String = when {
        abA != null && abB != null -> "Looping ${formatDuration((abA!! / 1000).toInt())} → ${formatDuration((abB!! / 1000).toInt())}"
        abA != null -> "A at ${formatDuration((abA!! / 1000).toInt())}; now set B"
        else -> ""
    }

    /** Returns a message to flash when something fired. */
    fun tick(player: Player, position: Long, duration: Long): String? {
        val a = abA
        val b = abB
        if (a != null && b != null && b > a && position >= b) player.seekTo(a)
        sleepAt?.let {
            if (SystemClock.elapsedRealtime() >= it) {
                player.pause(); setSleep(0); return "Sleep timer: paused"
            }
        }
        if (sleepChoice == -1 && duration > 0 && position >= duration - 800) {
            player.pause(); setSleep(0); return "Sleep timer: paused"
        }
        return null
    }
}

// ─── Small sheet parts ────────────────────────────────────────────────────────

@Composable
private fun SheetTitle(text: String) =
    Text(text, Modifier.padding(top = 18.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium)

@Composable
private fun Dim(text: String) =
    Text(text, Modifier.padding(vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim)

@Composable
private fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        PillTabs(options.map { it.first }, selected, { v -> options.first { it.first == v }.second }, onSelect)
    }
}

@Composable
private fun ChoiceLine(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(18.dp).clip(CircleShape)
                .background(if (selected) Cinema.Accent else Brush.linearGradient(listOf(Cinema.Line, Cinema.Line))),
        )
        Spacer(Modifier.width(14.dp))
        Text(label, color = if (enabled) Color.White else Cinema.TextDim, style = MaterialTheme.typography.bodyLarge)
    }
}

