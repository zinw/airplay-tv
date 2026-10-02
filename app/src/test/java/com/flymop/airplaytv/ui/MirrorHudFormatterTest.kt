package com.flymop.airplaytv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorHudFormatterTest {

    @Test
    fun formatLine_matchesCompetitorLayout() {
        val line = MirrorHudFormatter.formatLine(
            mediaCodecName = "OMX.hisi.video.decoder.avc",
            receivedFps = 29.9f,
            decodedFps = 29.9f,
            width = 505,
            height = 1084,
            wifiBand = "5G",
        )
        assertEquals(
            "OMX.hisi.video.decoder.avc | rec=29.9 dec=29.9 | 505x1084 | 5G",
            line,
        )
    }

    @Test
    fun formatLine_usesHonestFallbacks() {
        val line = MirrorHudFormatter.formatLine(
            mediaCodecName = null,
            receivedFps = null,
            decodedFps = null,
            width = 0,
            height = 0,
            wifiBand = null,
        )
        assertEquals("— | rec=— dec=— | — | —", line)
    }

    @Test
    fun formatFps_oneDecimal() {
        assertEquals("30.0", MirrorHudFormatter.formatFps(30f))
        assertEquals("29.9", MirrorHudFormatter.formatFps(29.9f))
        assertEquals("—", MirrorHudFormatter.formatFps(null))
    }
}
