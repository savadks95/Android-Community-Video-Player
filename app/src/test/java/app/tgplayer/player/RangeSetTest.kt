package app.tgplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-logic check for the read-ahead range store (OPT-2). No Android dependencies. */
class RangeSetTest {

    @Test fun contiguousAppendsMergeToOneRange() {
        val r = RangeSet()
        r.add(0, 100)
        r.add(100, 200) // tail-contiguous (the download loop's common case)
        assertEquals(200, r.downloaded)
        assertEquals(150, r.available(50))
        assertEquals(100, r.available(100))
        assertNull(r.nextMissing(0, 200)) // whole file present
        assertNull(r.nextRangeStart(0))   // no range starts after 0
    }

    @Test fun overlappingExtendCountsEachByteOnce() {
        val r = RangeSet()
        r.add(0, 100)
        r.add(50, 150) // overlaps
        assertEquals(150, r.downloaded)
        assertEquals(150, r.available(0))
    }

    @Test fun subsetAddIsNoOp() {
        val r = RangeSet()
        r.add(0, 200)
        r.add(50, 100) // already covered
        assertEquals(200, r.downloaded)
    }

    @Test fun gapThenFillMerges() {
        val r = RangeSet()
        r.add(100, 200)
        r.add(0, 50) // out-of-order, leaves a gap 50..100
        assertEquals(150, r.downloaded)
        assertEquals(50L, r.nextMissing(0, 200)) // nullable Long? — keep the literal a Long so the boxed compare matches
        assertEquals(100L, r.nextRangeStart(0))
        r.add(50, 100) // closes the gap
        assertEquals(200, r.downloaded)
        assertNull(r.nextMissing(0, 200))
    }

    @Test fun insertSpanningSeveralRangesCoalesces() {
        val r = RangeSet()
        r.add(0, 100)
        r.add(200, 300)
        r.add(50, 250) // bridges both, overlapping the middle gap
        assertEquals(300, r.downloaded)
        assertEquals(300, r.available(0))
        assertNull(r.nextMissing(0, 300))
    }

    @Test fun availableIsZeroOutsideAnyRange() {
        val r = RangeSet()
        r.add(0, 100)
        assertEquals(0, r.available(100)) // half-open: 100 is not covered
        assertEquals(0, r.available(500))
        assertEquals(100L, r.nextMissing(100, -1)) // length unknown: next missing is right here
    }
}
