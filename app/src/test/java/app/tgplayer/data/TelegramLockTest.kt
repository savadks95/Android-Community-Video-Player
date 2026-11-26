package app.tgplayer.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TelegramLockTest {
    private val salt = ByteArray(16) { it.toByte() }

    @Test
    fun hash_matchesOnlyTheSamePasswordAndSalt() {
        val a = hash("open sesame", salt, 1_000)
        assertEquals(32, a.size)
        assertArrayEquals(a, hash("open sesame", salt, 1_000))
        assertFalse(a.contentEquals(hash("open sesame!", salt, 1_000)))
        assertFalse(a.contentEquals(hash("open sesame", ByteArray(16), 1_000)))
    }

    @Test
    fun waitAfter_givesFiveFreeTriesThenBacksOffToFifteenMinutes() {
        assertEquals(0, waitAfter(5))
        assertEquals(30_000, waitAfter(6))
        assertEquals(60_000, waitAfter(7))
        assertEquals(120_000, waitAfter(8))
        assertEquals(15 * 60_000L, waitAfter(30))
    }
}
