package app.tgplayer.player

/**
 * Moves every cue in a subtitle file by [ms] (positive = later), for the player's subtitle timing:
 * Media3 has no timing offset of its own, so the player plays a re-timed copy. SRT and WebVTT timing
 * lines ("00:01:02,500 --> …") and ASS/SSA "Dialogue:" start/end fields; times stop at zero.
 * tails: TTML isn't shifted (the copy plays unchanged).
 */
object SubtitleShift {
    private val srtTime = Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})([,.])(\d{3})""")
    private val assTime = Regex("""(\d):(\d{2}):(\d{2})\.(\d{2})""")

    fun shift(text: String, ms: Long, extension: String): String = when (extension.lowercase()) {
        "ass", "ssa" -> text.lines().joinToString("\n") { line ->
            if (!line.startsWith("Dialogue:")) line
            else {
                // Dialogue: Layer,Start,End,… — only fields 2 and 3 are times.
                val parts = line.split(',', limit = 4)
                if (parts.size < 4) line
                else listOf(parts[0], shiftAss(parts[1], ms), shiftAss(parts[2], ms), parts[3]).joinToString(",")
            }
        }
        "srt", "vtt" -> text.lines().joinToString("\n") { line ->
            if ("-->" !in line) line
            else srtTime.replace(line) { m ->
                val (h, mi, s, sep, frac) = m.destructured
                val total = ((h.ifEmpty { "0" }.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 1000 + frac.toLong() + ms
                format(total.coerceAtLeast(0), sep, withHours = h.isNotEmpty() || extension.lowercase() == "srt")
            }
        }
        else -> text
    }

    private fun shiftAss(time: String, ms: Long): String {
        val m = assTime.matchEntire(time.trim()) ?: return time
        val (h, mi, s, cs) = m.destructured
        val total = (((h.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 100 + cs.toLong()) * 10 + ms
        val t = total.coerceAtLeast(0) / 10
        return "%d:%02d:%02d.%02d".format(t / 360_000, t / 6_000 % 60, t / 100 % 60, t % 100)
    }

    private fun format(totalMs: Long, sep: String, withHours: Boolean): String {
        val h = totalMs / 3_600_000
        val m = totalMs / 60_000 % 60
        val s = totalMs / 1000 % 60
        val f = totalMs % 1000
        return if (withHours || h > 0) "%02d:%02d:%02d%s%03d".format(h, m, s, sep, f) else "%02d:%02d%s%03d".format(m, s, sep, f)
    }
}
