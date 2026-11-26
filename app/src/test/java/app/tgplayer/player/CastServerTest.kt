package app.tgplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastServerTest {
    @Test
    fun parseRange_coversOpenEndedSuffixClampedAndBadHeaders() {
        val total = 1_000L
        assertNull(CastServer.parseRange(null, total))
        assertEquals(0L..999L, CastServer.parseRange("bytes=0-", total))
        assertEquals(100L..199L, CastServer.parseRange("bytes=100-199", total))
        assertEquals(900L..999L, CastServer.parseRange("bytes=900-5000", total)) // end past the file is clamped
        assertEquals(500L..999L, CastServer.parseRange("bytes=-500", total)) // the final 500 bytes
        assertEquals(0L..999L, CastServer.parseRange("bytes=-5000", total))
        assertTrue(CastServer.parseRange("bytes=1000-", total)!!.isEmpty()) // 416
        assertTrue(CastServer.parseRange("bytes=-0", total)!!.isEmpty())
        assertNull(CastServer.parseRange("bytes=200-100", total)) // invalid: ignored, whole file
        assertNull(CastServer.parseRange("bytes=0-1,5-6", total)) // several ranges: whole file
        assertNull(CastServer.parseRange("items=0-1", total))
    }

    @Test
    fun toWebVtt_convertsTimingLinesOnly() {
        assertEquals(
            "WEBVTT\n\n1\n00:00:01.000 --> 00:00:02.500\nAt 12:00:00,000 sharp\n",
            CastServer.toWebVtt("﻿1\r\n00:00:01,000 --> 00:00:02,500\r\nAt 12:00:00,000 sharp\r\n"),
        )
    }
}
