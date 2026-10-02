package com.flymop.airplaytv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiLinkMetricsTest {

    @Test
    fun bandLabel_mapsCommonWifiBands() {
        assertEquals("2.4G", WifiLinkMetrics.bandLabelFromFrequencyMhz(2412))
        assertEquals("5G", WifiLinkMetrics.bandLabelFromFrequencyMhz(5180))
        assertEquals("5G", WifiLinkMetrics.bandLabelFromFrequencyMhz(5745))
        assertEquals("6G", WifiLinkMetrics.bandLabelFromFrequencyMhz(6115))
        assertNull(WifiLinkMetrics.bandLabelFromFrequencyMhz(0))
        assertNull(WifiLinkMetrics.bandLabelFromFrequencyMhz(-1))
    }

    @Test
    fun signalBars_fromRssi() {
        assertEquals(3, WifiLinkMetrics.signalBarsFromRssi(-40))
        assertEquals(3, WifiLinkMetrics.signalBarsFromRssi(-55))
        assertEquals(2, WifiLinkMetrics.signalBarsFromRssi(-60))
        assertEquals(1, WifiLinkMetrics.signalBarsFromRssi(-70))
        assertEquals(0, WifiLinkMetrics.signalBarsFromRssi(-100))
        assertNull(WifiLinkMetrics.signalBarsFromRssi(0))
        assertNull(WifiLinkMetrics.signalBarsFromRssi(1))
    }
}
