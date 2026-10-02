package com.flymop.airplaytv.net

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Live Wi‑Fi link facts for the mirror HUD (band label + 0–3 signal bars).
 * Uses [WifiManager.connectionInfo] only — no scanning, no fabricated values.
 */
data class WifiLinkSnapshot(
    /** Display band: `5G`, `2.4G`, `6G`, or null when unknown / not on Wi‑Fi. */
    val bandLabel: String?,
    /** 0–3 bars from RSSI; null when RSSI is unavailable. */
    val signalBars: Int?,
)

object WifiLinkMetrics {

    fun snapshot(context: Context): WifiLinkSnapshot {
        return try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return WifiLinkSnapshot(null, null)
            @Suppress("DEPRECATION")
            val info = wifi.connectionInfo ?: return WifiLinkSnapshot(null, null)
            val freq = info.frequency
            val rssi = info.rssi
            WifiLinkSnapshot(
                bandLabel = bandLabelFromFrequencyMhz(freq),
                signalBars = signalBarsFromRssi(rssi),
            )
        } catch (_: SecurityException) {
            WifiLinkSnapshot(null, null)
        } catch (_: Exception) {
            WifiLinkSnapshot(null, null)
        }
    }

    /** Maps MHz to competitor-style short labels (`5G` = 5 GHz Wi‑Fi, not cellular). */
    fun bandLabelFromFrequencyMhz(frequencyMhz: Int): String? = when {
        frequencyMhz <= 0 -> null
        frequencyMhz in 2400..2500 -> "2.4G"
        frequencyMhz in 4900..5900 -> "5G"
        frequencyMhz in 5925..7125 -> "6G"
        else -> null
    }

    /**
     * Four-level RSSI → 0–3 bars.
     * Thresholds align with common Android Wi‑Fi icon buckets (not OEM-specific).
     * Pure logic so unit tests do not need [WifiManager].
     */
    fun signalBarsFromRssi(rssi: Int): Int? {
        if (rssi == 0 || rssi == Int.MIN_VALUE || rssi > 0) return null
        return when {
            rssi >= -55 -> 3
            rssi >= -66 -> 2
            rssi >= -77 -> 1
            else -> 0
        }
    }
}
