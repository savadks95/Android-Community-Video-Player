package app.tgplayer.player

import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.C
import app.tgplayer.data.Settings

/**
 * Sound settings: the equalizer ([EqualizerProcessor] reads [eq]) and a volume boost on the player's
 * audio session (set by [PlaybackService]). Same process as the UI, so the player screen changes them directly.
 */
object AudioFx {
    /** Centre frequencies of the equalizer's bands, one octave apart (Hz). */
    val BANDS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)
    const val MAX_DB = 12f

    /** Replaced whole on every change, so the audio thread never sees half an update. */
    class Eq(val enabled: Boolean, val gains: FloatArray, val preampDb: Float) {
        fun copy(enabled: Boolean = this.enabled, gains: FloatArray = this.gains, preampDb: Float = this.preampDb) = Eq(enabled, gains, preampDb)
    }

    val presets: List<Pair<String, FloatArray>> = listOf(
        "Flat" to bands(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        "Bass boost" to bands(6, 5, 4, 2, 0, 0, 0, 0, 0, 0),
        "Treble boost" to bands(0, 0, 0, 0, 0, 0, 1, 3, 5, 6),
        "Voice" to bands(-3, -2, -1, 0, 2, 4, 4, 3, 1, 0),
        "Loudness" to bands(5, 4, 2, 0, -1, 0, 0, 1, 3, 4),
        "Rock" to bands(4, 3, 2, 0, -1, -1, 1, 2, 3, 4),
        "Pop" to bands(-1, 1, 3, 4, 3, 0, -1, -1, 1, 1),
        "Jazz" to bands(3, 2, 1, 2, -1, -1, 0, 1, 2, 3),
        "Classical" to bands(0, 0, 0, 0, 0, 0, -2, -3, -3, -4),
        "Dance" to bands(6, 4, 1, 0, 0, -3, -4, -4, 0, 0),
    )

    private fun bands(vararg db: Int) = FloatArray(db.size) { db[it].toFloat() }

    /** The preset the bands match, or null when they've been set by hand. */
    fun presetName(gains: FloatArray): String? = presets.firstOrNull { it.second.contentEquals(gains) }?.first

    @Volatile private var current: Eq? = null

    val eq: Eq
        get() = current ?: run {
            val saved = Settings.eqGains.split(',').mapNotNull { it.trim().toFloatOrNull() }
            val gains = if (saved.size == BANDS.size) saved.toFloatArray() else FloatArray(BANDS.size)
            Eq(Settings.eqEnabled, gains, Settings.eqPreamp).also { current = it }
        }

    /** Takes effect within one audio buffer. [save] = false while a slider is still moving. */
    fun setEq(value: Eq, save: Boolean = true) {
        current = value
        if (save) {
            Settings.eqEnabled = value.enabled
            Settings.eqGains = value.gains.joinToString(",")
            Settings.eqPreamp = value.preampDb
        }
    }

    @Volatile var sessionId: Int = C.AUDIO_SESSION_ID_UNSET
        private set
    private var booster: LoudnessEnhancer? = null
    var boostMb: Int = 0; private set // millibels; 1000 = +10 dB

    @Synchronized
    fun attach(audioSessionId: Int) {
        if (audioSessionId == sessionId || audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        release()
        sessionId = audioSessionId
        // Re-apply the user's choice to the new session.
        if (boostMb > 0) setBoost(boostMb)
    }

    @Synchronized
    fun setBoost(millibels: Int) {
        boostMb = millibels
        runCatching {
            if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
            val b = booster ?: LoudnessEnhancer(sessionId).also { booster = it }
            b.setTargetGain(millibels)
            b.enabled = millibels > 0
        }
    }

    @Synchronized
    fun release() {
        runCatching { booster?.release() }
        booster = null
        sessionId = C.AUDIO_SESSION_ID_UNSET
    }
}
