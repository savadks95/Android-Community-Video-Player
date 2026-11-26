package app.tgplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CuelessMatroskaTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun readVint_decodesLengthAndValue() {
        assertEquals(1L to 1, readVint(bytes(0x81), 0, 1))
        assertEquals(2L to 2, readVint(bytes(0x40, 0x02), 0, 2))
        assertNull(readVint(bytes(0x00), 0, 1))
        assertNull(readVint(bytes(0x40), 0, 1)) // second byte missing
    }

    @Test
    fun clusterTimeUs_readsTheTimestamp() {
        // Cluster ID, unknown size (8-byte vint), Timestamp = 1000 ticks of 1 ms.
        val cluster = bytes(0x1F, 0x43, 0xB6, 0x75, 0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xE7, 0x82, 0x03, 0xE8)
        assert(isClusterId(cluster, 0))
        assertEquals(1_000_000L, clusterTimeUs(cluster, 4, cluster.size, 1_000_000))
        // A CRC-32 element before the Timestamp, and a 100 µs timecode scale.
        val withCrc = bytes(0x1F, 0x43, 0xB6, 0x75, 0x88, 0xBF, 0x84, 1, 2, 3, 4, 0xE7, 0x81, 0x05)
        assertEquals(0L, clusterTimeUs(withCrc, 4, withCrc.size, 100)) // 5 × 100 ns rounds down to 0 µs
        assertEquals(5L, clusterTimeUs(withCrc, 4, withCrc.size, 1_000))
        // Frame data that happens to contain the ID: no Timestamp after it.
        assertNull(clusterTimeUs(bytes(0x1F, 0x43, 0xB6, 0x75, 0x84, 0x12, 0x34), 4, 7, 1_000_000))
    }

    @Test
    fun timecodeScale_foundInTheHead() {
        val head = bytes(0x15, 0x49, 0xA9, 0x66, 0x2A, 0xD7, 0xB1, 0x83, 0x0F, 0x42, 0x40, 0x44, 0x89)
        assertEquals(1_000_000L, timecodeScale(head, head.size))
        assertNull(timecodeScale(bytes(1, 2, 3, 4, 5), 5))
    }
}
