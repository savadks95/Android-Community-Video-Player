package app.tgplayer.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.cast.Cast
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.tgplayer.MainActivity
import app.tgplayer.Telegram
import app.tgplayer.data.Settings
import app.tgplayer.data.Store

/**
 * Owns the one ExoPlayer, so playback survives the player screen (music in the background, PiP).
 * [PlayerActivity] drives it through a MediaController.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    companion object {
        /** True while the service is alive, so the UI's mini player connects only to a player that exists. */
        @Volatile var running = false
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        Telegram.start(this)
        Store.init(this)
        Settings.init(this)

        val renderers = object : DefaultRenderersFactory(this) {
            // The equalizer runs in the sink both the phone's decoders and FFmpeg play through.
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParameters: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters)
                    .setAudioProcessors(arrayOf(EqualizerProcessor()))
                    .build()

            // Video: the phone's decoders first, dav1d (AV1) only when the phone has none for the file. Always, since
            // "Prefer FFmpeg" is about audio and must not swap a phone's hardware AV1 decoder for software.
            override fun buildVideoRenderers(
                context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
                eventHandler: Handler, eventListener: VideoRendererEventListener, allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>,
            ) = super.buildVideoRenderers(
                context, EXTENSION_RENDERER_MODE_ON, mediaCodecSelector, enableDecoderFallback,
                eventHandler, eventListener, allowedVideoJoiningTimeMs, out,
            )
        }
            // Phone decoders first, FFmpeg (AC3/DTS/TrueHD…) when the phone can't; "prefer" flips the order (audio only).
            .setExtensionRendererMode(
                if (Settings.preferFfmpeg) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            )
            .setEnableDecoderFallback(true)

        // Big buffer: up to 15 minutes ahead, capped at a quarter of the app's heap.
        // Network files also get a full disk read-ahead (StreamCache); Telegram files are downloaded whole by TDLib.
        val bufferBytes = (Runtime.getRuntime().maxMemory() / 4).coerceIn(32L shl 20, 256L shl 20).toInt()
        val loadControl = PlayableFirstLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(30_000, 15 * 60_000, 2_000, 4_000)
                .setTargetBufferBytes(bufferBytes)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build(),
            // Past the target only while almost nothing can play: up to half the heap.
            ceilingBytes = maxOf(bufferBytes.toLong(), Runtime.getRuntime().maxMemory() / 2),
            startUs = 2_000_000,
            restartUs = 4_000_000,
        )

        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(RoutingDataSource.Factory(this), CuelessMatroskaExtractor.Factory()))
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setPauseAtEndOfMediaItems(!Settings.autoPlayNext)
            .build()
        // Start every file in the languages picked last time (the track list in the player remembers them).
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            Settings.preferredAudioLanguage.ifEmpty { null }?.let { setPreferredAudioLanguage(it) }
            Settings.preferredSubtitleLanguage.ifEmpty { null }?.let { setPreferredTextLanguage(it) }
        }.build()
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) = AudioFx.attach(audioSessionId)

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val uri = mediaItem?.localConfiguration?.uri
                if (uri != null && uri.scheme?.lowercase() in setOf("http", "https")) StreamCache.readAhead(this@PlaybackService, uri)
                else StreamCache.cancel()
            }
        })
        AudioFx.attach(player.audioSessionId)

        // Casting: the session's player moves playback (queue, position) between this ExoPlayer and a Chromecast.
        Cast.getSingletonInstance(this).initialize()
        val remote = RemoteCastPlayer.Builder(this)
            .setMediaItemConverter(CastItemConverter(this))
            .setTrackSelector(CastTracks())
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        val castPlayer = CastPlayer.Builder(this).setLocalPlayer(player).setRemotePlayer(remote).build()
        castPlayer.addListener(object : Player.Listener {
            // The file server only lives while the TV plays from it.
            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_LOCAL) CastServer.stop()
            }
        })

        // tails: the notification reopens the app's home, not the exact track; pass the item along if wanted.
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, castPlayer).setSessionActivity(openApp).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // Swiping the app away stops a paused/finished player instead of leaving a dead notification.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        running = false
        StreamCache.cancel()
        AudioFx.release()
        CastServer.stop()
        session?.run {
            player.release() // the CastPlayer releases the ExoPlayer too
            release()
        }
        session = null
        super.onDestroy()
    }
}
