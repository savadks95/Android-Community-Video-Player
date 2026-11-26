package app.tgplayer.data

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryTest {
    @Test
    fun playableKind_usesMimeThenExtension() {
        assertEquals(MediaKind.VIDEO, playableKind("video/x-matroska", "Movie.2024.mkv"))
        assertEquals(MediaKind.VIDEO, playableKind("application/octet-stream", "Episode.01.MKV"))
        assertEquals(MediaKind.AUDIO, playableKind("audio/mpeg", "song"))
        assertEquals(MediaKind.AUDIO, playableKind(null, "track.flac"))
        assertNull(playableKind("application/pdf", "book.pdf"))
        assertNull(playableKind("application/vnd.android.package-archive", "app.apk"))
    }

    @Test
    fun containerType_usesExtensionThenMediaMime() {
        assertEquals("video/x-matroska", containerType("application/octet-stream", "Movie.2024.MKV"))
        assertEquals("video/mp4", containerType("video/quicktime", "clip.mov"))
        assertEquals("video/mp4", containerType("video/mp4", null))
        assertEquals("audio/mpeg", containerType("audio/mpeg", "song"))
        assertNull(containerType("application/octet-stream", "file.bin"))
        assertNull(containerType(null, null))
    }

    @Test
    fun titleOf_prefersFirstCaptionLine_thenCleanFileName() {
        val caption = TdApi.FormattedText("\n  The Movie (2024)  \n1080p HEVC", emptyArray())
        assertEquals("The Movie (2024)", titleOf(caption, "x.mkv", "Video"))
        assertEquals("The Movie 2024 1080p", titleOf(TdApi.FormattedText("", emptyArray()), "The.Movie_2024.1080p.mkv", "Video"))
        assertEquals("Video", titleOf(null, null, "Video"))
    }
}
