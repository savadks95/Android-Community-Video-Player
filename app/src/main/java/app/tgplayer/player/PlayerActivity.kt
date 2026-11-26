package app.tgplayer.player

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.cast.Cast
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import app.tgplayer.Telegram
import app.tgplayer.data.Jellyfin
import app.tgplayer.data.Library
import app.tgplayer.data.MediaItem
import app.tgplayer.data.MediaKind
import app.tgplayer.data.MediaLibrary
import app.tgplayer.data.Ref
import app.tgplayer.data.Settings
import app.tgplayer.data.Storage
import app.tgplayer.data.Store
import app.tgplayer.data.playableKind
import app.tgplayer.local.LocalMedia
import app.tgplayer.local.NetworkDrive
import app.tgplayer.local.NetworkDrives
import app.tgplayer.ui.Cinema
import app.tgplayer.ui.enableCinemaEdgeToEdge
import app.tgplayer.ui.CinemaTheme
import app.tgplayer.ui.GradientPlaceholder
import app.tgplayer.ui.LocalThumb
import app.tgplayer.ui.TdImage
import app.tgplayer.ui.VideoAdBox
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import android.provider.Settings as SystemSettings
import androidx.media3.common.MediaItem as ExoItem

/**
 * Full-screen player for anything: a Telegram message, local files (with the rest of the folder as
 * a playlist), network links, or files other apps open with TG Player. Playback runs in
 * [PlaybackService]; this screen draws the video and [PlayerOverlay]'s controls.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity(), PlayerHost {
    private var telegramRef: Ref? = null
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var player by mutableStateOf<Player?>(null)
    private var telegramItem by mutableStateOf<MediaItem?>(null)
    private var audioOnly by mutableStateOf(false)
    private var inPip by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var currentTitle by mutableStateOf("")
    private var currentSubtitle by mutableStateOf("")
    private var currentUri by mutableStateOf<String?>(null)
    /** Intro/recap ranges of the current item (Jellyfin), for the Skip button. */
    private var skipRanges by mutableStateOf<List<Pair<String, LongRange>>>(emptyList())
    private var skipRangesFor: String? = null
    private var subtitleDelayState by mutableStateOf(0L)
    /** The current item's subtitle files before any timing shift, so shifts don't add up. */
    private val unshiftedSubs = HashMap<String, List<ExoItem.SubtitleConfiguration>>()
    private var resizeModeState by mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    private var subtitleScaleState by mutableFloatStateOf(0.0533f)
    private var downloadedState by mutableStateOf<Float?>(null)
    /** The Telegram message now playing (a chat's video, or a Telegram file in the Library), for Telegram's video ads. */
    private var adRef by mutableStateOf<Ref?>(null)
    private var playingState by mutableStateOf(false)
    private var rotation = Rotation.AUTO
    private var brightness = -1f
    private var volume = -1f
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    /** Media ids already looked up on OpenSubtitles while this screen is open. */
    private val subtitleLookups = mutableSetOf<String>()
    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::addSubtitle) }

    private enum class Rotation(val label: String) { AUTO("Auto rotate"), LANDSCAPE("Landscape"), PORTRAIT("Portrait"), SENSOR("Follow phone") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableCinemaEdgeToEdge()
        Telegram.start(this)
        Store.init(this)
        Settings.init(this)
        Cast.getSingletonInstance(this).initialize() // before the Cast button composes
        subtitleScaleState = Settings.subtitleScale
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent { CinemaTheme { PlayerScreen() } }

        // Start the Telegram file downloading while the player service connects.
        val warmChat = intent.getLongExtra(EXTRA_CHAT, 0)
        if (savedInstanceState == null && warmChat != 0L) {
            lifecycleScope.launch { warmTelegram(warmChat, intent.getLongExtra(EXTRA_MESSAGE, 0)) }
        }

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture.addListener({
            val controller = runCatching { controllerFuture.get() }.getOrElse {
                error = "Couldn't start the player."
                return@addListener
            }
            controller.addListener(listener)
            player = controller
            if (savedInstanceState == null && !intent.getBooleanExtra(EXTRA_OPEN_ONLY, false)) lifecycleScope.launch { load(controller) } else syncFromPlayer(controller)
        }, ContextCompat.getMainExecutor(this))

        lifecycleScope.launch {
            var tick = 0
            while (isActive) {
                delay(1_000)
                downloadedState = downloaded()
                if (++tick % 15 == 0 && player?.isPlaying == true) saveProgress() // crash/kill-safe checkpoint
            }
        }
    }

    /** Playing on a Chromecast: the phone is only the remote, so leaving the screen mustn't pause or stop it. */
    private val casting get() = player?.deviceInfo?.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE

    private val listener = object : Player.Listener {
        override fun onPlayerError(e: PlaybackException) {
            error = describe(e)
        }

        override fun onMediaItemTransition(mediaItem: ExoItem?, reason: Int) {
            error = null
            subtitleDelayState = 0
            player?.let(::syncFromPlayer)
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (tracks.isEmpty) return
            audioOnly = !tracks.containsType(C.TRACK_TYPE_VIDEO)
            findSubtitleOnline(tracks)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = applyRotation()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playingState = isPlaying
        }
    }

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused this link (${e.cause?.message?.substringAfter("code: ", "")?.ifEmpty { null } ?: "HTTP error"})."
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Can't reach the server. Check your connection and try again."
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The file isn't there any more."
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "TG Player isn't allowed to read this file."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
            "This file format isn't supported, or the file is damaged."
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
            "This phone can't decode this file's video or audio."
        // Name the underlying reason (e.g. "HTTP 401") rather than only Media3's error code.
        else -> "Can't play this file: ${generateSequence(e as Throwable) { it.cause }.last().message ?: e.errorCodeName}"
    }

    /** Builds the playlist from the intent and starts it, resuming where it was left. */
    private suspend fun load(controller: Player) {
        val chat = intent.getLongExtra(EXTRA_CHAT, 0)
        if (chat != 0L) return loadTelegram(controller, Ref(chat, intent.getLongExtra(EXTRA_MESSAGE, 0)))

        val uris = intent.getStringArrayListExtra(EXTRA_URIS) ?: intent.data?.let { arrayListOf(it.toString()) }
        if (uris.isNullOrEmpty()) {
            error = "Nothing to play."
            return
        }
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES)
        val index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, uris.lastIndex)
        // Off the main thread: it reads folders for subtitles, and a long folder playlist froze the screen (ANR).
        val folderSubs = HashMap<String, List<File>>() // each folder listed once, not once per video
        val items = withContext(Dispatchers.IO) { uris.mapIndexed { i, u -> buildItem(u, titles?.getOrNull(i), folderSubs) } }
        val start = if (Settings.resume) Store.history(uris[index])?.takeIf { it.positionMs > 5_000 && it.fraction < 0.95f }?.positionMs ?: 0L else 0L
        audioOnly = playableKind(null, Uri.parse(uris[index]).lastPathSegment) == MediaKind.AUDIO
        // Register a link for disk read-ahead before the player's first request (the service also does it on track changes).
        Uri.parse(uris[index]).takeIf { it.scheme == "http" || it.scheme == "https" }?.let { StreamCache.readAhead(this, it) }
        controller.setMediaItems(items, index, start)
        controller.prepare()
        controller.play()
        syncFromPlayer(controller)
    }

    private suspend fun buildItem(uriString: String, title: String?, folderSubs: MutableMap<String, List<File>>): ExoItem {
        val uri = Uri.parse(uriString)
        // Library items from Telegram keep their tgmsg:// id (history keys on it); play the file behind it.
        val playUri = when (uri.scheme) {
            MediaLibrary.TG_MESSAGE -> telegramFileUri(uri) ?: uri
            Jellyfin.SCHEME -> Jellyfin.itemId(uriString)?.let(Jellyfin::streamUrl)?.let(Uri::parse) ?: uri
            else -> uri
        }
        val name = title ?: displayName(uri)
        val sidecars: List<Pair<Uri, String>> = when (uri.scheme) {
            "file" -> {
                val path = uri.path.orEmpty()
                val dir = File(path).parent.orEmpty()
                LocalMedia.sidecarSubtitles(path, folderSubs.getOrPut(dir) { LocalMedia.subtitleFilesIn(File(dir)) }).map { Uri.fromFile(it) to it.name }
            }
            // Read through the same network data sources as the video.
            in NetworkDrive.PROTOCOLS -> runCatching { NetworkDrives.sidecarSubtitles(uri) }.getOrDefault(emptyList()).map { it.uri to it.name }
            else -> emptyList()
        }
        val subs = sidecars.mapNotNull { (subUri, fileName) ->
            subtitleMime(fileName)?.let { mime ->
                // A subtitle file beside the video is meant to be shown (like VLC/MX), so mark it default;
                // "movie.en.srt" also tells its language, which the remembered subtitle language can match.
                val language = fileName.substringBeforeLast('.').substringAfterLast('.', "").takeIf { it.length in 2..3 && it.all(Char::isLetter) }
                ExoItem.SubtitleConfiguration.Builder(subUri).setMimeType(mime).setLabel(fileName).setLanguage(language)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
            }
        }
        return ExoItem.Builder()
            .setMediaId(uriString)
            .setUri(playUri)
            .setSubtitleConfigurations(subs)
            // Music keeps the artist and album from the file's own tags (the session merges them in); other files show their source.
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(name)
                    .apply { if (playableKind(null, uri.lastPathSegment) != MediaKind.AUDIO) setArtist(sourceLabel(uri)) }.build(),
            )
            .build()
    }

    /** tgmsg://msg/<chatId>/<messageId> → the TDLib stream for that message's file; null if it's gone or there's no login. */
    private suspend fun telegramFileUri(uri: Uri): Uri? {
        val ids = uri.pathSegments.mapNotNull { it.toLongOrNull() }.takeIf { it.size == 2 } ?: return null
        if (!Telegram.ready(this)) return null
        val media = Library.message(ids[0], ids[1]) ?: return null
        val file = media.file ?: return null
        return TdlibDataSource.uriFor(file.id, media.type)
    }

    /**
     * Asks TDLib for the file's first bytes before the player exists, so its own first request finds them on the way.
     * Skipped when TDLib is already downloading it (still playing, or saved for offline): that download isn't moved.
     * tails: a resume still warms the head (the player reads it first anyway); warming the resume spot would need the index.
     */
    private suspend fun warmTelegram(chatId: Long, messageId: Long) {
        runCatching {
            if (!Telegram.ready(this)) return
            val file = Library.message(chatId, messageId)?.file ?: return
            if (!file.local.isDownloadingCompleted && !file.local.isDownloadingActive) TdlibDataSource.warm(file.id)
        }
    }

    private suspend fun loadTelegram(controller: Player, ref: Ref) {
        telegramRef = ref
        val media = Library.message(ref.chatId, ref.messageId)
        val file = media?.file
        if (media == null || file == null) {
            error = "This message is gone or isn't playable."
            return
        }
        telegramItem = media
        audioOnly = media.kind == MediaKind.AUDIO
        Storage.nowPlaying = ref
        if (controller.currentMediaItem?.mediaId == media.key) return syncFromPlayer(controller) // still playing
        val start = Store.progress(ref)?.takeIf { it.fraction < 0.95f }?.positionMs ?: 0L
        val art = media.thumb?.let { runCatching { Library.download(it).local.path }.getOrNull() }?.takeIf { it.isNotEmpty() }
        val item = ExoItem.Builder()
            .setMediaId(media.key)
            .setUri(TdlibDataSource.uriFor(file.id, media.type))
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(media.title).setArtist(media.chatTitle)
                    .apply { if (art != null) setArtworkUri(Uri.fromFile(File(art))) }.build(),
            )
            .build()
        controller.setMediaItem(item, start)
        controller.prepare()
        controller.play()
        syncFromPlayer(controller)
    }

    private fun syncFromPlayer(p: Player) {
        p.currentMediaItem?.mediaId?.let(::loadSkipRanges)
        // The item's own metadata is set locally and available at once; the session's arrives later.
        val meta = p.currentMediaItem?.mediaMetadata?.takeIf { it.title != null } ?: p.mediaMetadata
        currentTitle = meta.title?.toString().orEmpty()
        currentSubtitle = meta.artist?.toString().orEmpty()
        currentUri = p.currentMediaItem?.localConfiguration?.uri?.toString()
        if (!p.currentTracks.isEmpty) audioOnly = !p.currentTracks.containsType(C.TRACK_TYPE_VIDEO)
        val mediaId = p.currentMediaItem?.mediaId.orEmpty()
        adRef = when {
            mediaId == telegramItem?.key -> telegramRef
            mediaId.startsWith("${MediaLibrary.TG_MESSAGE}:") ->
                Uri.parse(mediaId).pathSegments.mapNotNull { it.toLongOrNull() }.takeIf { it.size == 2 }?.let { Ref(it[0], it[1]) }
            else -> null
        }
        playingState = p.isPlaying
    }

    private suspend fun downloaded(): Float? {
        val uri = currentUri?.let(Uri::parse) ?: return null
        return when (uri.scheme) {
            "tdlib" -> telegramItem?.file?.let { f ->
                runCatching { Library.fileState(f) }.getOrNull()?.let { s ->
                    val size = if (s.size > 0) s.size else s.expectedSize
                    if (size > 0) s.local.downloadedSize.toFloat() / size else null
                }
            }
            "http", "https" -> if (StreamCache.readAheadUri == uri && StreamCache.readAheadTotal > 0) {
                StreamCache.readAheadBytes.toFloat() / StreamCache.readAheadTotal
            } else null
            else -> null
        }
    }

    // ─── UI ──────────────────────────────────────────────────────────────────

    @Composable
    private fun PlayerScreen() {
        val p = player
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        subtitleView?.apply {
                            setApplyEmbeddedStyles(true)
                            setStyle(
                                CaptionStyleCompat(
                                    android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
                                    CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null,
                                ),
                            )
                        }
                    }
                },
                update = { view ->
                    view.player = p
                    view.resizeMode = resizeModeState
                    view.subtitleView?.setFractionalTextSize(subtitleScaleState)
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (p != null && !inPip) {
                if (audioOnly) MusicPlayerScreen(p, this@PlayerActivity) { FallbackArt(it) } else PlayerOverlay(p, this@PlayerActivity, false)
            }
            // Telegram's video ads (required by the Telegram API terms); hidden, and their timers paused, in picture-in-picture.
            adRef?.takeIf { !audioOnly }?.let { ref ->
                VideoAdBox(
                    ref.chatId, ref.messageId, { playingState && !inPip }, visible = !inPip,
                    Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(start = 16.dp, end = 16.dp, bottom = 120.dp),
                )
            }
            when {
                // Above the centre controls, on its own card, so it never collides with the play button.
                error != null -> Text(
                    error!!,
                    Modifier.align(Alignment.Center).offset(y = (-150).dp).padding(horizontal = 28.dp)
                        .clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
                )
                p == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }

    /** Cover for music without embedded art: Telegram's thumbnail, the file's picture, or a gradient. */
    @Composable
    private fun FallbackArt(modifier: Modifier) {
        val tg = telegramItem
        val uri = currentUri?.let(Uri::parse)
        when {
            tg != null -> TdImage(tg.thumb, tg.miniThumb, modifier) { GradientPlaceholder(tg.title, MediaKind.AUDIO) }
            uri?.scheme == "file" -> LocalThumb(uri.path.orEmpty(), MediaKind.AUDIO, modifier) { GradientPlaceholder(currentTitle, MediaKind.AUDIO) }
            else -> Box(modifier) { GradientPlaceholder(currentTitle, MediaKind.AUDIO) }
        }
    }

    // ─── PlayerHost (what the controls ask of the screen) ────────────────────

    override val title get() = currentTitle
    override val subtitle get() = currentSubtitle
    override val resizeMode get() = resizeModeState
    override val subtitleScale get() = subtitleScaleState
    override val downloadedFraction get() = downloadedState

    override fun close() = finish()

    override fun setResize(mode: Int) {
        resizeModeState = mode
    }

    override fun setSubtitleScale(scale: Float) {
        subtitleScaleState = scale
        Settings.subtitleScale = scale
    }

    override fun pickSubtitleFile() = pickSubtitle.launch(arrayOf("*/*"))

    override fun cycleRotation(): String {
        rotation = Rotation.entries[(rotation.ordinal + 1) % Rotation.entries.size]
        applyRotation()
        return rotation.label
    }

    private fun applyRotation() {
        requestedOrientation = when (rotation) {
            Rotation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            Rotation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            Rotation.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            Rotation.AUTO -> {
                val size = player?.videoSize
                when {
                    audioOnly || size == null || size.width == 0 -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    size.width >= size.height -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                }
            }
        }
    }

    override fun adjustBrightness(delta: Float): Float {
        if (brightness < 0) {
            val current = window.attributes.screenBrightness
            brightness = if (current >= 0) current
            else runCatching { SystemSettings.System.getInt(contentResolver, SystemSettings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
        }
        brightness = (brightness + delta).coerceIn(0.01f, 1f)
        window.attributes = window.attributes.apply { screenBrightness = brightness }
        return brightness
    }

    override fun adjustVolume(delta: Float): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
        if (volume < 0) volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
        volume = (volume + delta * max * 1.2f).coerceIn(0f, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume.roundToInt(), 0)
        return volume / max
    }

    override fun enterPip() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) || audioOnly) return
        val size = player?.videoSize
        val ratio = if (size != null && size.width > 0 && size.height > 0) {
            val r = size.width.toFloat() / size.height
            when { r > 2.39f -> Rational(239, 100); r < 1 / 2.39f -> Rational(100, 239); else -> Rational(size.width, size.height) }
        } else Rational(16, 9)
        runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build()) }
    }

    // ─── Subtitles ───────────────────────────────────────────────────────────

    private fun loadSkipRanges(mediaId: String) {
        if (mediaId == skipRangesFor) return
        skipRangesFor = mediaId
        skipRanges = emptyList()
        val id = Jellyfin.itemId(mediaId) ?: return
        lifecycleScope.launch {
            val ranges = Jellyfin.skippable(id)
            if (skipRangesFor == mediaId) skipRanges = ranges
        }
    }

    override val skippable get() = skipRanges
    override val subtitleDelayMs get() = subtitleDelayState

    /**
     * Re-times the current item's subtitle files by [ms] and reloads them at the same spot. False
     * when there are none to move (subtitles built into the video can't be re-timed).
     */
    override fun setSubtitleDelay(ms: Long): Boolean {
        val p = player ?: return false
        val current = p.currentMediaItem ?: return false
        val originals = unshiftedSubs.getOrPut(current.mediaId) { current.localConfiguration?.subtitleConfigurations.orEmpty() }
        if (originals.isEmpty()) return false
        subtitleDelayState = ms
        lifecycleScope.launch {
            val shifted = withContext(Dispatchers.IO) { originals.map { c -> if (ms == 0L) c else runCatching { shiftedCopy(c, ms) }.getOrDefault(c) } }
            if (p.currentMediaItem?.mediaId != current.mediaId) return@launch
            val index = p.currentMediaItemIndex
            val position = p.currentPosition
            p.replaceMediaItem(index, current.buildUpon().setSubtitleConfigurations(shifted).build())
            p.seekTo(index, position)
        }
        return true
    }

    @OptIn(UnstableApi::class)
    private fun shiftedCopy(c: ExoItem.SubtitleConfiguration, ms: Long): ExoItem.SubtitleConfiguration {
        val name = c.label?.toString() ?: c.uri.lastPathSegment.orEmpty()
        val extension = when (c.mimeType) {
            MimeTypes.TEXT_SSA -> "ass"
            MimeTypes.TEXT_VTT -> "vtt"
            else -> "srt"
        }
        // Read through the player's own data sources: the file may be on the phone, a network drive or the web.
        val source = RoutingDataSource(this)
        val text = DataSourceInputStream(source, DataSpec(c.uri)).use { it.readBytes() }.toString(Charsets.UTF_8)
        val dir = File(cacheDir, "subshift").apply { mkdirs() }
        val out = File(dir, "${(c.uri.toString() + name).hashCode().toUInt()}_$ms.$extension")
        out.writeText(SubtitleShift.shift(text, ms, extension))
        return c.buildUpon().setUri(Uri.fromFile(out)).build()
    }

    private fun addSubtitle(uri: Uri) {
        val name = displayName(uri)
        val mime = subtitleMime(name) ?: return run { error = "Not a subtitle file: $name" }
        attachSubtitle(uri, mime, name)
    }

    /** A video with no subtitles built in or beside it: look one up online, once per item. */
    private fun findSubtitleOnline(tracks: Tracks) {
        val item = player?.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri ?: return
        if (!Settings.findSubtitlesOnline || !OnlineSubtitles.available) return
        if (!tracks.containsType(C.TRACK_TYPE_VIDEO) || tracks.containsType(C.TRACK_TYPE_TEXT)) return
        if (item.localConfiguration?.subtitleConfigurations.orEmpty().isNotEmpty()) return
        if (!subtitleLookups.add(item.mediaId)) return
        val name = item.mediaMetadata.title?.toString() ?: displayName(uri)
        lifecycleScope.launch {
            val found = runCatching { OnlineSubtitles.find(this@PlayerActivity, item.mediaId, name, uri) }.getOrNull() ?: return@launch
            if (player?.currentMediaItem?.mediaId != item.mediaId) return@launch // moved on meanwhile
            val language = Locale.forLanguageTag(found.language).displayLanguage.ifEmpty { found.language }
            attachSubtitle(Uri.fromFile(found.file), subtitleMime(found.file.name) ?: MimeTypes.APPLICATION_SUBRIP, "$language · OpenSubtitles")
            Toast.makeText(this@PlayerActivity, "Subtitles found online ($language)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun attachSubtitle(uri: Uri, mime: String, label: String) {
        val p = player ?: return
        val current = p.currentMediaItem ?: return
        val sub = ExoItem.SubtitleConfiguration.Builder(uri).setMimeType(mime).setLabel(label)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        val subs = current.localConfiguration?.subtitleConfigurations.orEmpty() + sub
        unshiftedSubs[current.mediaId] = unshiftedSubs[current.mediaId].orEmpty() + sub
        val index = p.currentMediaItemIndex
        val position = p.currentPosition
        p.replaceMediaItem(index, current.buildUpon().setSubtitleConfigurations(subs).build())
        p.seekTo(index, position)
    }

    private fun subtitleMime(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
        else -> null
    }

    private fun displayName(uri: Uri): String = when (uri.scheme) {
        "content" -> runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "Video"
        "file" -> File(uri.path.orEmpty()).nameWithoutExtension
        else -> uri.lastPathSegment?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: uri.host ?: "Stream"
    }

    private fun sourceLabel(uri: Uri): String = when (uri.scheme) {
        "file" -> File(uri.path.orEmpty()).parentFile?.name.orEmpty()
        "http", "https", in NetworkDrive.PROTOCOLS -> uri.host.orEmpty()
        MediaLibrary.TG_MESSAGE -> "Telegram"
        Jellyfin.SCHEME -> Jellyfin.server?.name.orEmpty()
        else -> ""
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    private fun saveProgress() {
        val p = player ?: return
        val position = p.currentPosition
        if (position <= 0) return
        val duration = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val ref = telegramRef
        val uri = p.currentMediaItem?.mediaId ?: return
        val title = currentTitle
        // Not lifecycleScope: the write must finish even while the screen is going away.
        CoroutineScope(Dispatchers.IO).launch {
            if (ref != null) Store.saveProgress(ref, position, duration) else Store.saveHistory(uri, title, position, duration)
            Jellyfin.itemId(uri)?.let { Jellyfin.reportStopped(it, position) } // keep the server's resume point in step
        }
    }

    /** singleTask: playing something else while this screen exists (e.g. in PiP) lands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveProgress()
        setIntent(intent)
        telegramRef = null
        telegramItem = null
        error = null
        player?.let { p -> lifecycleScope.launch { load(p) } }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Settings.autoPip && player?.isPlaying == true && !audioOnly && !casting) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        // Music (and video with background play on) keeps going; otherwise pause when the screen goes away.
        if (!audioOnly && !Settings.backgroundVideo && !casting) player?.pause()
    }

    override fun onDestroy() {
        if (isFinishing && !audioOnly && !Settings.backgroundVideo && !casting) {
            player?.stop() // a closed video shouldn't linger in the notification
            player?.clearMediaItems()
        }
        player?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
        if (telegramRef != null) CoroutineScope(Dispatchers.IO).launch { runCatching { Storage.trim() } }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_CHAT = "chat"
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_OPEN_ONLY = "open_only"

        /** Opens the player on whatever is already playing (from the mini player), without loading anything. */
        fun openCurrent(context: Context): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_OPEN_ONLY, true)

        fun intent(context: Context, chatId: Long, messageId: Long): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_CHAT, chatId).putExtra(EXTRA_MESSAGE, messageId)

        /** A playlist of local files or links, starting at [index]. */
        fun intent(context: Context, uris: List<String>, titles: List<String>?, index: Int): Intent =
            Intent(context, PlayerActivity::class.java)
                .putStringArrayListExtra(EXTRA_URIS, ArrayList(uris))
                .apply { if (titles != null) putStringArrayListExtra(EXTRA_TITLES, ArrayList(titles)) }
                .putExtra(EXTRA_INDEX, index)
    }
}

/** What [PlayerOverlay] needs from the screen around it. */
interface PlayerHost {
    val title: String
    val subtitle: String
    val resizeMode: Int
    val subtitleScale: Float
    /** Telegram/link download progress (0..1), or null when not applicable. */
    val downloadedFraction: Float?
    fun close()
    fun setResize(mode: Int)
    fun setSubtitleScale(scale: Float)
    fun pickSubtitleFile()
    /** Current subtitle timing shift in ms (positive = later). */
    val subtitleDelayMs: Long
    fun setSubtitleDelay(ms: Long): Boolean
    /** (kind, range in ms) the viewer can skip: Jellyfin intros and recaps. */
    val skippable: List<Pair<String, LongRange>>
    fun cycleRotation(): String
    fun adjustBrightness(delta: Float): Float
    fun adjustVolume(delta: Float): Float
    fun enterPip()
}
