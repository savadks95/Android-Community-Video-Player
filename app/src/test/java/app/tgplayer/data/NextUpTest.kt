package app.tgplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NextUpTest {
    private fun ep(show: String, s: Int, e: Int) = LibraryEpisode("file:///$show/S${s}E$e.mkv", s, e, null)
    private fun watched(e: LibraryEpisode) = Store.HistoryEntry(e.uri, "", 100, 100)
    private fun started(e: LibraryEpisode) = Store.HistoryEntry(e.uri, "", 30, 100)

    @Test
    fun nextUp_isTheEpisodeAfterTheLastFinished_newestShowFirst() {
        val dark = (1..3).map { ep("dark", 1, it) } + ep("dark", 2, 1)
        val lost = (1..2).map { ep("lost", 1, it) }
        val office = (1..2).map { ep("office", 1, it) }
        val catalog = Catalog(
            emptyList(),
            listOf(LibraryShow("Dark", null, null, dark), LibraryShow("Lost", null, null, lost), LibraryShow("Office", null, null, office)),
        )
        val history = listOf(
            watched(lost[0]), // newest: Lost E1 done → E2
            watched(dark[2]), // Dark E3 done → S2E1 (crosses the season)
            watched(dark[0]),
            started(office[0]), // half-watched: Continue watching, not Next up
        )
        assertEquals(listOf("Lost" to lost[1], "Dark" to dark[3]), nextUp(catalog, history).map { (s, e) -> s.name to e })
        // Everything after the last finished one already watched → nothing up next for that show.
        assertEquals(emptyList<Any>(), nextUp(catalog, listOf(watched(lost[0]), watched(lost[1]))))
    }
}
