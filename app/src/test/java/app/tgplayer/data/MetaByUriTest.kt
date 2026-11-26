package app.tgplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MetaByUriTest {
    private fun meta(id: Int) = Meta(id, "T$id", null, "", 0.0, "/p$id.jpg", "/b$id.jpg", emptyList())

    @Test
    fun metaByUri_movieKeepsItsOwn_episodeTakesItsShows_unmatchedLeftOut() {
        val movie = LibraryMovie("file:///m/Algiers (1938).mp4", "Algiers", 1938, meta(1))
        val unmatched = LibraryMovie("file:///m/clip.mp4", "clip", null, null)
        val episodes = (1..2).map { LibraryEpisode("file:///dark/S1E$it.mkv", 1, it, null) }
        val catalog = Catalog(listOf(movie, unmatched), listOf(LibraryShow("Dark", null, meta(2), episodes)))
        assertEquals(
            mapOf(movie.uri to meta(1), episodes[0].uri to meta(2), episodes[1].uri to meta(2)),
            catalog.metaByUri,
        )
    }
}
