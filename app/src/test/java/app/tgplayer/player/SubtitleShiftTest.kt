package app.tgplayer.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleShiftTest {
    @Test
    fun srt_movesTimingLinesOnly_andStopsAtZero() {
        val srt = "1\n00:00:01,000 --> 00:00:02,500\nHello 00:00:09,000\n\n2\n00:59:59,900 --> 01:00:00,400\nBye"
        assertEquals(
            "1\n00:00:02,500 --> 00:00:04,000\nHello 00:00:09,000\n\n2\n01:00:01,400 --> 01:00:01,900\nBye",
            SubtitleShift.shift(srt, 1_500, "srt"),
        )
        assertEquals("00:00:00,000 --> 00:00:00,500", SubtitleShift.shift("00:00:01,000 --> 00:00:02,500", -2_000, "srt"))
    }

    @Test
    fun vtt_keepsShortForm_andAssShiftsDialogueFields() {
        assertEquals("WEBVTT\n\n00:02.000 --> 01:01.250\nHi", SubtitleShift.shift("WEBVTT\n\n00:01.500 --> 01:00.750\nHi", 500, "vtt"))
        assertEquals(
            "Dialogue: 0,0:00:00.50,0:01:00.00,Default,,0,0,0,,Text, with, commas",
            SubtitleShift.shift("Dialogue: 0,0:00:01.00,0:01:00.50,Default,,0,0,0,,Text, with, commas", -500, "ass"),
        )
    }
}
