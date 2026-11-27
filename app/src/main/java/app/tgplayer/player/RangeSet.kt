package app.tgplayer.player

/**
 * Sorted, merged, half-open byte ranges `[start, end)` — the "what's on disk" map for [HttpDownload].
 *
 * The downloader appends bytes contiguously at the tail almost every write, so [add] handles that
 * case in O(1) (extend the last range) and keeps a running [downloaded] total; only a seek into a
 * fresh gap takes the O(n) insert-and-coalesce path. Not thread-safe: callers synchronize (the
 * previous inline version held `synchronized(ranges)` at every call site, kept here).
 */
internal class RangeSet {
    private val ranges = ArrayList<LongArray>() // sorted by start, non-overlapping, non-adjacent
    var downloaded = 0L; private set            // total covered bytes, kept in step with [ranges]

    val isEmpty: Boolean get() = ranges.isEmpty()

    /** Add `[start, end)`, coalescing with any touching/overlapping ranges. */
    fun add(start: Long, end: Long) {
        if (end <= start) return
        val last = ranges.lastOrNull()
        // Fast path: contiguous or overlapping extension of the tail (the download loop's every write).
        if (last != null && start >= last[0] && start <= last[1]) {
            if (end > last[1]) { downloaded += end - last[1]; last[1] = end }
            return
        }
        // Fast path: a clean append past the tail with a gap.
        if (last == null || start > last[1]) {
            ranges.add(longArrayOf(start, end)); downloaded += end - start; return
        }
        // General path (rare: aimed into an earlier gap). Insert in order, then merge neighbours.
        var i = ranges.binarySearch { it[0].compareTo(start) }
        if (i < 0) i = -(i + 1)
        ranges.add(i, longArrayOf(start, end))
        merge()
    }

    /** Bytes available on disk starting exactly at [pos] (0 if [pos] isn't downloaded). */
    fun available(pos: Long): Long = ranges.firstOrNull { pos >= it[0] && pos < it[1] }?.let { it[1] - pos } ?: 0

    /** First byte at or after [from] that isn't downloaded; null when the rest up to [length] is present. */
    fun nextMissing(from: Long, length: Long): Long? {
        var p = from
        for (r in ranges) {
            if (r[1] <= p) continue
            if (r[0] > p) break
            p = r[1]
        }
        return if (length >= 0 && p >= length) null else p
    }

    /** Start of the first range strictly after [after] — where the downloader should stop (data exists). */
    fun nextRangeStart(after: Long): Long? = ranges.firstOrNull { it[0] > after }?.get(0)

    /** Serialized body lines `"start end"`, in order (the `length` header is added by the caller). */
    fun lines(): String = ranges.joinToString("\n") { "${it[0]} ${it[1]}" }

    private fun merge() {
        val merged = ArrayList<LongArray>(ranges.size)
        for (r in ranges) {
            val last = merged.lastOrNull()
            if (last != null && r[0] <= last[1]) last[1] = maxOf(last[1], r[1]) else merged.add(r)
        }
        ranges.clear()
        ranges.addAll(merged)
        downloaded = merged.sumOf { it[1] - it[0] }
    }
}
