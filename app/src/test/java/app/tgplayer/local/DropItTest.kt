package app.tgplayer.local

import org.junit.Assert.assertEquals
import org.junit.Test

class DropItTest {
    @Test
    fun safeName_keepsFilesInTheDropFolder() {
        assertEquals("movie.mkv", DropIt.safeName("movie.mkv"))
        assertEquals("passwd", DropIt.safeName("../../etc/passwd"))
        assertEquals("evil.mp4", DropIt.safeName("C:\\Users\\evil.mp4"))
        assertEquals("file", DropIt.safeName(".."))
        assertEquals("hidden", DropIt.safeName(".hidden"))
        assertEquals("ab.mp3", DropIt.safeName("a\u0000b.mp3"))
        assertEquals("file", DropIt.safeName("   "))
    }
}
