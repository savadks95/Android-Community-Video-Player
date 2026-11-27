package app.tgplayer.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The 10-band graphic equalizer, run on the decoded PCM inside the audio sink (see [PlaybackService]),
 * so it sounds the same on every phone and for every decoder, FFmpeg included. The phone's own
 * android.media.audiofx.Equalizer differs per maker and some ignore it.
 *
 * Each band is an RBJ "Audio EQ Cookbook" peaking filter; settings are read from [eq] on every buffer.
 * 16-bit and float PCM are processed; other encodings, and passthrough (AC3/DTS to a receiver), go through untouched.
 */
@OptIn(UnstableApi::class)
class EqualizerProcessor(private val eq: () -> AudioFx.Eq = { AudioFx.eq }) : BaseAudioProcessor() {
    private var applied: AudioFx.Eq? = null
    private var coeffs = Array(AudioFx.BANDS.size) { IDENTITY }
    private var active = IntArray(0) // bands with a gain other than 0 dB
    private var gain = 1.0 // preamp, linear
    private var on = false
    private var z1 = DoubleArray(0) // filter state per band and channel: [band * channels + channel]
    private var z2 = DoubleArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) inputAudioFormat
        else AudioFormat.NOT_SET

    // New format or a seek: start the filters from silence.
    override fun onFlush() {
        z1 = DoubleArray(AudioFx.BANDS.size * inputAudioFormat.channelCount.coerceAtLeast(0))
        z2 = DoubleArray(z1.size)
        applied = null
    }

    override fun onReset() {
        z1 = DoubleArray(0)
        z2 = DoubleArray(0)
        applied = null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val settings = eq()
        if (settings !== applied) apply(settings)
        val out = replaceOutputBuffer(size)
        // Bulk-copy the PCM into a primitive array, filter in place, bulk-copy back (OPT-5): the per-sample
        // ByteBuffer.short/putShort calls ran ~96k times a second on the audio thread. The per-sample math
        // in process() and the channel interleaving are unchanged, so the output bytes are identical.
        if (!on) {
            out.put(inputBuffer)
        } else if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            val channels = inputAudioFormat.channelCount
            val samples = ShortArray(size / 2)
            inputBuffer.asShortBuffer().get(samples)
            var c = 0
            for (j in samples.indices) {
                val y = process(samples[j] / 32768.0, c, channels)
                samples[j] = (y * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                if (++c == channels) c = 0
            }
            out.asShortBuffer().put(samples)
            inputBuffer.position(inputBuffer.limit())
            out.position(size)
        } else {
            val channels = inputAudioFormat.channelCount
            val samples = FloatArray(size / 4)
            inputBuffer.asFloatBuffer().get(samples)
            var c = 0
            for (j in samples.indices) {
                samples[j] = process(samples[j].toDouble(), c, channels).toFloat()
                if (++c == channels) c = 0
            }
            out.asFloatBuffer().put(samples)
            inputBuffer.position(inputBuffer.limit())
            out.position(size)
        }
        out.flip()
        if (on) flushDenormals()
    }

    private fun apply(settings: AudioFx.Eq) {
        val rate = inputAudioFormat.sampleRate
        val bands = ArrayList<Int>()
        for (i in AudioFx.BANDS.indices) {
            val db = settings.gains[i].toDouble()
            // A band at or above Nyquist can't be filtered (16 kHz in 22 kHz audio, say): leave it out.
            if (db == 0.0 || AudioFx.BANDS[i] >= rate * 0.45) {
                if (i in active) zeroState(i) // so it starts clean if it comes back
                continue
            }
            coeffs[i] = peaking(AudioFx.BANDS[i].toDouble(), db, rate.toDouble())
            bands += i
        }
        active = bands.toIntArray()
        gain = 10.0.pow(settings.preampDb / 20.0)
        on = settings.enabled && (active.isNotEmpty() || settings.preampDb != 0f)
        applied = settings
    }

    /** One sample through every active band (transposed direct form II), the preamp and a soft clip. */
    private fun process(x: Double, channel: Int, channels: Int): Double {
        var y = x
        for (b in active) {
            val k = coeffs[b]
            val i = b * channels + channel
            val o = k[0] * y + z1[i]
            z1[i] = k[1] * y - k[3] * o + z2[i]
            z2[i] = k[2] * y - k[4] * o
            y = o
        }
        return softClip(y * gain)
    }

    private fun zeroState(band: Int) {
        val channels = inputAudioFormat.channelCount
        for (c in 0 until channels) {
            val i = band * channels + c
            if (i < z1.size) { z1[i] = 0.0; z2[i] = 0.0 }
        }
    }

    // Filter tails decaying in silence turn into denormal numbers, which are very slow on some CPUs.
    private fun flushDenormals() {
        for (i in z1.indices) {
            if (abs(z1[i]) < 1e-15) z1[i] = 0.0
            if (abs(z2[i]) < 1e-15) z2[i] = 0.0
        }
    }

    companion object {
        private val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)

        /** Octave-wide bands (Q = √2) overlap just enough to stay smooth between band centres. */
        private const val Q = 1.4142135623730951

        /** RBJ peaking EQ, normalised: [b0, b1, b2, a1, a2]. */
        fun peaking(freq: Double, db: Double, sampleRate: Double): DoubleArray {
            val a = 10.0.pow(db / 40.0)
            val w0 = 2 * PI * freq / sampleRate
            val alpha = sin(w0) / (2 * Q)
            val a0 = 1 + alpha / a
            return doubleArrayOf(
                (1 + alpha * a) / a0, -2 * cos(w0) / a0, (1 - alpha * a) / a0,
                -2 * cos(w0) / a0, (1 - alpha / a) / a0,
            )
        }

        /**
         * The whole equalizer's gain at [freq] in dB, preamp included, as the filters really respond
         * (neighbouring bands add up), for the curve in the equalizer screen.
         */
        fun responseDb(eq: AudioFx.Eq, freq: Double, sampleRate: Double = 48_000.0): Double {
            var db = eq.preampDb.toDouble()
            val w = 2 * PI * freq / sampleRate
            for (i in AudioFx.BANDS.indices) {
                if (eq.gains[i] == 0f) continue
                val k = peaking(AudioFx.BANDS[i].toDouble(), eq.gains[i].toDouble(), sampleRate)
                val nr = k[0] + k[1] * cos(w) + k[2] * cos(2 * w)
                val ni = -(k[1] * sin(w) + k[2] * sin(2 * w))
                val dr = 1 + k[3] * cos(w) + k[4] * cos(2 * w)
                val di = -(k[3] * sin(w) + k[4] * sin(2 * w))
                db += 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
            }
            return db
        }

        private const val KNEE = 0.891 // -1 dBFS

        /**
         * Boosted peaks bend smoothly towards full scale instead of clipping hard.
         * tails: a static soft clip, not a look-ahead limiter; add one if heavy boosts sound gritty.
         */
        fun softClip(x: Double): Double {
            val m = abs(x)
            if (m <= KNEE) return x
            val y = KNEE + (1 - KNEE) * tanh((m - KNEE) / (1 - KNEE))
            return if (x < 0) -y else y
        }
    }
}
