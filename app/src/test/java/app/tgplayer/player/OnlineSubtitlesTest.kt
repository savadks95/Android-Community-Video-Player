package app.tgplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class OnlineSubtitlesTest {
    @Test
    fun movieHash_matchesReferenceImplementation() {
        // Expected value computed independently with Python's struct.unpack('<8192Q', …) over the same bytes.
        val file = File.createTempFile("hash", ".bin").apply { deleteOnExit() }
        file.writeBytes(ByteArray(200_003) { i -> (i * 31 + 7).toByte() })
        RandomAccessFile(file, "r").use { assertEquals("003f7fc000436d43", OnlineSubtitles.movieHash(it.channel, it.length())) }
        // Smaller than the site indexes, or the tail isn't actually there.
        RandomAccessFile(file, "r").use { assertNull(OnlineSubtitles.movieHash(it.channel, 100_000)) }
        RandomAccessFile(file, "r").use { assertNull(OnlineSubtitles.movieHash(it.channel, 400_000)) }
    }
}
