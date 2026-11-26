package app.tgplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueRailTest {
    @Test
    fun railSpan_keepsShrunkCardsEdgeToEdge() {
        // Centre-to-centre distance between neighbours = half of each one's scaled width.
        for (k in 1..5) {
            val step = railSpan(k.toFloat()) - railSpan(k - 1f)
            val halves = (railKnot(RAIL_SCALES, k - 1f) + railKnot(RAIL_SCALES, k.toFloat())) / 2
            assertEquals(halves, step, 1e-5f)
        }
        // Continuous through a swipe: no jump either side of a whole page.
        assertEquals(railSpan(1f), railSpan(0.9999f), 1e-3f)
        assertEquals(railSpan(1f), railSpan(1.0001f), 1e-3f)
        assertEquals(0f, railSpan(0f), 0f)
    }

    @Test
    fun nearestLoopPage_picksTheClosestCopyOfTheItem() {
        // 5 items, page 50 shows item 0: item 4 (the last) is the card on its left, item 1 on its right.
        assertEquals(49, nearestLoopPage(50, 5, 4))
        assertEquals(51, nearestLoopPage(50, 5, 1))
        assertEquals(50, nearestLoopPage(50, 5, 0))
        // From page 53 (item 3), item 0 is two to the right (55), not three to the left (50).
        assertEquals(55, nearestLoopPage(53, 5, 0))
        for (current in 40..60) for (i in 0 until 5) {
            val p = nearestLoopPage(current, 5, i)
            assertEquals(i, p.mod(5))
            assertTrue(kotlin.math.abs(p - current) <= 2)
        }
    }
}
