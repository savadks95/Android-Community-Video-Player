package app.tgplayer.player

import androidx.annotation.OptIn
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection

/**
 * [DefaultLoadControl] stops loading once its byte target is full, and lets playback start on that target alone,
 * however little of the buffer can play. A high-bitrate file whose chosen audio track starts late (or a big back
 * buffer) fills the target with picture only: loading stops, nothing plays, and Media3 fails it as stuck.
 *
 * So while less than [floorUs] is buffered ahead, loading goes on past the target up to [ceilingBytes], and
 * playback waits for [startUs] (or [restartUs] after a stall) of media rather than the byte target. At the ceiling
 * the default rules decide again.
 */
@OptIn(UnstableApi::class)
class PlayableFirstLoadControl(
    private val delegate: DefaultLoadControl,
    private val ceilingBytes: Long,
    private val startUs: Long,
    private val restartUs: Long,
    private val floorUs: Long = restartUs,
) : LoadControl by delegate {

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldContinueLoading(parameters) || (parameters.bufferedDurationUs < floorUs && hasRoom(parameters))

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val needUs = if (parameters.rebuffering) restartUs else startUs
        return delegate.shouldStartPlayback(parameters) && (parameters.bufferedDurationUs >= needUs || !hasRoom(parameters))
    }

    // Kotlin's `by` forwards only abstract members, not Java default methods, so DefaultLoadControl's own versions
    // of these (which keep its per-player state) are forwarded by hand.
    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)
    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)
    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)
    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)
    override fun getBackBufferDurationUs(playerId: PlayerId) = delegate.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId) = delegate.retainBackBufferFromKeyframe(playerId)
    override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaPeriodId, bufferedDurationUs: Long) =
        delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    private fun hasRoom(parameters: LoadControl.Parameters) =
        delegate.getAllocator(parameters.playerId).totalBytesAllocated < ceilingBytes
}
