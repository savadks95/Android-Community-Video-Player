package app.tgplayer.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

class EqualizerProcessorTest {
    private fun eq(vararg gains: Pair<Int, Float>, preamp: Float = 0f, enabled: Boolean = true) =
        AudioFx.Eq(enabled, FloatArray(AudioFx.BANDS.size).also { g -> gains.forEach { (i, db) -> g[i] = db } }, preamp)

    @Test
    fun response_hitsTheBandGainAtItsCentre_andIsFlatWhenFlat() {
        assertEquals(6.0, EqualizerProcessor.responseDb(eq(5 to 6f), 1000.0), 0.01)
        assertEquals(-9.0, EqualizerProcessor.responseDb(eq(2 to -9f), 125.0), 0.01)
        assertEquals(0.0, EqualizerProcessor.responseDb(eq(), 440.0), 1e-9)
        assertEquals(-3.0, EqualizerProcessor.responseDb(eq(preamp = -3f), 440.0), 1e-9)
        // Far from the band, it barely touches the sound.
        assertEquals(0.0, EqualizerProcessor.responseDb(eq(0 to 12f), 8000.0), 0.1)
    }

    @Test
    fun softClip_passesNormalLevels_andNeverExceedsFullScale() {
        assertEquals(0.5, EqualizerProcessor.softClip(0.5), 0.0)
        assertEquals(-0.8, EqualizerProcessor.softClip(-0.8), 0.0)
        assertTrue(EqualizerProcessor.softClip(4.0) <= 1.0)
        assertTrue(EqualizerProcessor.softClip(-4.0) >= -1.0)
        assertTrue(EqualizerProcessor.softClip(1.2) > EqualizerProcessor.softClip(1.0)) // still monotonic
    }

    /** A 1 kHz tone through the 1 kHz band at +6 dB comes out about twice as loud; off, it's untouched. */
    @Test
    fun processor_boostsA16BitSine() {
        assertEquals(6.0, gainDb(eq(5 to 6f)), 0.3)
        assertEquals(0.0, gainDb(eq(5 to 6f, enabled = false)), 0.01)
        assertEquals(-6.0, gainDb(eq(preamp = -6f)), 0.3)
    }

    private fun gainDb(settings: AudioFx.Eq): Double {
        val rate = 48_000
        val p = EqualizerProcessor { settings }
        p.configure(AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        val frames = rate / 2
        val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        repeat(frames) { n ->
            val s = (0.25 * 32767 * sin(2 * PI * 1000 * n / rate)).toInt().toShort()
            input.putShort(s); input.putShort(s)
        }
        input.flip()
        p.queueInput(input)
        val out = p.output
        // Peak of the left channel after the filter has settled (skip the first 0.1 s).
        var peak = 0
        for (n in 0 until frames) {
            val l = out.short.toInt(); out.short
            if (n > rate / 10) peak = maxOf(peak, abs(l))
        }
        return 20 * log10(peak / (0.25 * 32767))
    }
}
