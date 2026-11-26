package app.tgplayer.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser

/**
 * Makes a Matroska file without Cues (its seek index) seekable, which Media3 plays from the start only: ffmpeg
 * writing to a pipe, cam rips, some remuxes.
 *
 * Only an unseekable seek map with a known duration is replaced, by one that guesses a byte position from the time.
 * A seek then reads forward to the next Cluster, reads its start time, and guesses again (at most [MAX_GUESSES]
 * times) until that Cluster starts a little before the time asked for; the player decodes from there without
 * showing the frames before it, so the seek lands where asked. Each guess reopens the source there, which for a
 * Telegram file moves the download. Files with Cues, and every other format, are untouched.
 *
 * tails: a file with no duration in its header (ffmpeg to a pipe) stays unseekable; Telegram's own duration could
 * stand in for it.
 */
@OptIn(UnstableApi::class)
class CuelessMatroskaExtractor(private val inner: Extractor) : Extractor {
    private var length = C.LENGTH_UNSET.toLong()
    private var durationUs = C.TIME_UNSET // set once the guessing seek map is in use
    private var timecodeScaleNs = DEFAULT_TIMECODE_SCALE_NS
    private var targetUs = C.TIME_UNSET // a seek through the guessing map, not yet at its Cluster
    private var guesses = 0
    private val buffer = ByteArray(SCAN_CHUNK)

    override fun sniff(input: ExtractorInput) = inner.sniff(input)
    override fun getSniffFailureDetails() = inner.sniffFailureDetails
    override fun getUnderlyingImplementation() = inner.underlyingImplementation
    override fun release() = inner.release()

    override fun init(output: ExtractorOutput) {
        inner.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int) = output.track(id, type)
            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: SeekMap) {
                if (seekMap.isSeekable || seekMap.durationUs == C.TIME_UNSET || length <= 0) return output.seekMap(seekMap)
                durationUs = seekMap.durationUs
                output.seekMap(GuessingSeekMap())
            }
        })
    }

    override fun seek(position: Long, timeUs: Long) {
        inner.seek(position, timeUs)
        targetUs = if (durationUs != C.TIME_UNSET) timeUs else C.TIME_UNSET
        guesses = 0
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        length = input.length
        if (input.position == 0L) readTimecodeScale(input)
        if (targetUs == C.TIME_UNSET) return inner.read(input, seekPosition)

        val (at, clusterUs) = findCluster(input) ?: return Extractor.RESULT_END_OF_INPUT.also { targetUs = C.TIME_UNSET }
        val offUs = targetUs - clusterUs
        if (++guesses <= MAX_GUESSES && (offUs < 0 || offUs > CLOSE_ENOUGH_US)) {
            val next = (at + (offUs - BACK_OFF_US) * length.toDouble() / durationUs).toLong().coerceIn(0, length - 1)
            if (next != at) {
                seekPosition.position = next
                return Extractor.RESULT_SEEK
            }
        }
        targetUs = C.TIME_UNSET
        seekPosition.position = at // the Matroska reader starts at the Cluster itself
        return Extractor.RESULT_SEEK
    }

    /** Reads forward to the next Cluster: its position and start time, or null at the end of the file. */
    private fun findCluster(input: ExtractorInput): Pair<Long, Long>? {
        var bufferAt = input.position
        var kept = 0
        while (true) {
            val n = input.read(buffer, kept, buffer.size - kept)
            if (n == C.RESULT_END_OF_INPUT) return null
            val end = kept + n
            var i = 0
            while (i + CLUSTER_HEADER_MAX <= end) {
                if (isClusterId(buffer, i)) {
                    clusterTimeUs(buffer, i + 4, end, timecodeScaleNs)
                        ?.takeIf { it <= durationUs + DURATION_SLACK_US } // a chance match inside frame data fails this
                        ?.let { return (bufferAt + i) to it }
                }
                i++
            }
            System.arraycopy(buffer, i, buffer, 0, end - i)
            bufferAt += i
            kept = end - i
        }
    }

    private fun readTimecodeScale(input: ExtractorInput) {
        val head = ByteArray(HEAD_SCAN)
        var n = 0
        while (n < head.size) {
            val r = input.peek(head, n, head.size - n)
            if (r == C.RESULT_END_OF_INPUT) break
            n += r
        }
        input.resetPeekPosition()
        timecodeScaleNs = timecodeScale(head, n) ?: DEFAULT_TIMECODE_SCALE_NS
    }

    private inner class GuessingSeekMap : SeekMap {
        override fun isSeekable() = true
        private val totalUs get() = this@CuelessMatroskaExtractor.durationUs
        override fun getDurationUs() = totalUs
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val at = ((timeUs - BACK_OFF_US).coerceAtLeast(0) * length.toDouble() / totalUs).toLong()
            return SeekMap.SeekPoints(SeekPoint(timeUs, at.coerceIn(0, length - 1)))
        }
    }

    /** [DefaultExtractorsFactory], with its Matroska extractors wrapped. Java default methods are forwarded by hand. */
    @OptIn(ExperimentalApi::class)
    class Factory(private val inner: DefaultExtractorsFactory = DefaultExtractorsFactory()) : ExtractorsFactory {
        override fun createExtractors(): Array<Extractor> = wrap(inner.createExtractors())
        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
            wrap(inner.createExtractors(uri, responseHeaders))
        override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean) =
            apply { inner.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled) }
        override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory) =
            apply { inner.setSubtitleParserFactory(subtitleParserFactory) }
        override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int) =
            apply { inner.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies) }
        override fun setParseHagcMetadata(parseHagcMetadata: Boolean) = apply { inner.setParseHagcMetadata(parseHagcMetadata) }

        private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
            Array(extractors.size) { extractors[it].let { e -> if (e is MatroskaExtractor) CuelessMatroskaExtractor(e) else e } }
    }

    private companion object {
        const val DEFAULT_TIMECODE_SCALE_NS = 1_000_000L
        const val MAX_GUESSES = 4
        /** A Cluster starting this much before the target is used; the frames in between are decoded, not shown. */
        const val CLOSE_ENOUGH_US = 10_000_000L
        /** Guesses aim this far before the target, so they land before it rather than after. */
        const val BACK_OFF_US = 3_000_000L
        const val DURATION_SLACK_US = 60_000_000L
        const val SCAN_CHUNK = 64 * 1024
        const val HEAD_SCAN = 64 * 1024
    }
}

private const val CLUSTER_HEADER_MAX = 4 + 8 + 1 + 6 + 1 + 8 + 8 // ID, size, a CRC-32 or Void, Timestamp element

internal fun isClusterId(b: ByteArray, i: Int) =
    b[i] == 0x1F.toByte() && b[i + 1] == 0x43.toByte() && b[i + 2] == 0xB6.toByte() && b[i + 3] == 0x75.toByte()

/** An EBML variable-length integer at [i]: (value, byte count), or null if malformed. Unknown sizes come out as all ones. */
internal fun readVint(b: ByteArray, i: Int, end: Int): Pair<Long, Int>? {
    if (i >= end) return null
    val first = b[i].toInt() and 0xFF
    if (first == 0) return null
    val count = Integer.numberOfLeadingZeros(first) - 23 // 1..8
    if (i + count > end) return null
    var value = (first and (0xFF shr count)).toLong()
    for (k in 1 until count) value = (value shl 8) or (b[i + k].toLong() and 0xFF)
    return value to count
}

private fun readUint(b: ByteArray, i: Int, size: Int): Long {
    var value = 0L
    for (k in 0 until size) value = (value shl 8) or (b[i + k].toLong() and 0xFF)
    return value
}

/**
 * The start time of the Cluster whose size field is at [i] (just past its ID): its Timestamp element, the first
 * child (after a CRC-32 or Void, if any), times [timecodeScaleNs]. Null when the bytes aren't a Cluster header.
 */
internal fun clusterTimeUs(b: ByteArray, i: Int, end: Int, timecodeScaleNs: Long): Long? {
    var p = i + (readVint(b, i, end) ?: return null).second
    if (p < end && (b[p] == 0xBF.toByte() || b[p] == 0xEC.toByte())) { // CRC-32 or Void before the Timestamp
        val (size, n) = readVint(b, p + 1, end) ?: return null
        if (size > 64) return null
        p += 1 + n + size.toInt()
    }
    if (p >= end || b[p] != 0xE7.toByte()) return null
    val (size, n) = readVint(b, p + 1, end) ?: return null
    if (size !in 1..8 || p + 1 + n + size > end) return null
    val timecode = readUint(b, p + 1 + n, size.toInt())
    return timecode * timecodeScaleNs / 1000
}

/** The TimecodeScale (ns per Cluster/Block tick) from a file's first bytes, if they hold it. */
internal fun timecodeScale(b: ByteArray, length: Int): Long? {
    for (i in 0..length - 4) {
        if (b[i] != 0x2A.toByte() || b[i + 1] != 0xD7.toByte() || b[i + 2] != 0xB1.toByte()) continue
        val (size, n) = readVint(b, i + 3, length) ?: continue
        if (size !in 1..8 || i + 3 + n + size > length) continue
        return readUint(b, i + 3 + n, size.toInt()).takeIf { it > 0 }
    }
    return null
}
