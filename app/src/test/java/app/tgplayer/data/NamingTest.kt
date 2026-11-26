package app.tgplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NamingTest {
    @Test
    fun episodes_fromFileNamesAndSeasonFolders() {
        assertEquals(Parsed.Episode("Breaking Bad", null, 1, 2), Naming.parse("/Shows/Breaking Bad/Season 1/Breaking.Bad.S01E02.720p.mkv"))
        assertEquals(Parsed.Episode("The Office US", null, 2, 3), Naming.parse("/Download/The.Office.US.S02E03.HDTV.x264.mkv"))
        assertEquals(Parsed.Episode("Doctor Who", 2005, 2, 1), Naming.parse("/TV/Doctor Who (2005)/Season 02/01 - New Earth.mkv"))
        assertEquals(Parsed.Episode("Friends", null, 1, 5), Naming.parse("/TV/Friends/1x05.avi"))
        assertEquals(Parsed.Episode("Dark", null, 3, 4), Naming.parse("/TV/Dark/Season 3 Episode 4.mkv"))
        assertEquals(Parsed.Episode("Lost", null, 0, 1), Naming.parse("/TV/Lost/Specials/Lost E01.mkv"))
        assertEquals(Parsed.Episode("Show", null, 1, 1), Naming.parse("/TV/[Group] Show - S01E01 [1080p].mkv"))
    }

    @Test
    fun movies_cleanedLikeJellyfin() {
        assertEquals(Parsed.Movie("The Matrix", 1999), Naming.parse("/Movies/The.Matrix.1999.1080p.BluRay.x264.mkv"))
        assertEquals(Parsed.Movie("Inception", 2010), Naming.parse("/Movies/Inception (2010)/inception-720p.mkv"))
        assertEquals(Parsed.Movie("Avatar", 2009), Naming.parse("/Movies/Avatar [2009] [1080p].mkv"))
        assertEquals(Parsed.Movie("Blade Runner 2049", 2017), Naming.parse("/Movies/Blade.Runner.2049.2017.2160p.mkv"))
        assertEquals(Parsed.Movie("1920x1080 test", null), Naming.parse("/Videos/1920x1080 test.mp4"))
    }

    @Test
    fun extras_areSkipped() {
        assertNull(Naming.parse("/Movies/Movie.2010.1080p-sample.mkv"))
        assertNull(Naming.parse("/Movies/Movie (2010)/Extras/Making of.mkv"))
    }
}
