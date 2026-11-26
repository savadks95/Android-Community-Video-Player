package app.tgplayer.data

import app.tgplayer.data.Tmdb.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbTest {
    @Test
    fun bestMatch_prefersExactTitleOverPopularity_likeJellyfin() {
        // TMDb's real order for a "Dark" TV search.
        val dark = listOf(Candidate("Dark Shadows", "Dark Shadows", 1966), Candidate("Dark", "Dark", 2017), Candidate("Dark Matter", "Dark Matter", 2024))
        assertEquals(1, Tmdb.bestMatch(dark, "Dark", null))
        // Year separates same-titled results; off by one still counts.
        val mulan = listOf(Candidate("Mulan", "Mulan", 1998), Candidate("Mulan", "Mulan", 2020))
        assertEquals(1, Tmdb.bestMatch(mulan, "Mulan", 2021))
        // Whole-word prefix only: "Wall" doesn't half-match "Wallace"; nothing scores → TMDb's first.
        assertEquals(1, Tmdb.bestMatch(listOf(Candidate("Wallace", "", null), Candidate("Wall Street", "", null)), "Wall", null))
        assertEquals(0, Tmdb.bestMatch(listOf(Candidate("A Christmas Number One", "", 2021)), "A Christmas No. 1", null))
        assertNull(Tmdb.bestMatch(emptyList(), "Anything", null))
    }

    @Test
    fun bestMatch_titleRequired_dropsResultsThatMatchOnlyByYearOrOrder() {
        // A camera clip's name: TMDb's top hit (or a same-year one) isn't this video.
        val hits = listOf(Candidate("Vid", "", 2026), Candidate("The Clip", "", 2026))
        assertNull(Tmdb.bestMatch(hits, "VID 20261109 144301677", 2026, titleRequired = true))
        // A real title still matches, and so does a whole-word prefix.
        val bethlehem = listOf(Candidate("Bethlehem", "", 2013), Candidate("Bethlehem Kudumba Unit", "", 2026))
        assertEquals(1, Tmdb.bestMatch(bethlehem, "bethlehem kudumba unit", 2026, titleRequired = true))
        assertEquals(1, Tmdb.bestMatch(listOf(Candidate("Wallace", "", null), Candidate("Wall Street", "", null)), "Wall", null, titleRequired = true))
    }
}
