package com.flymop.airplaytv.ui

/**
 * Formats the bottom-right mirror diagnostic line to match the competitor layout:
 * `decoder | rec=X.X dec=Y.Y | WxH | 5G`
 *
 * Missing metrics become `—` or the segment is omitted — never invented numbers.
 */
object MirrorHudFormatter {

    fun formatLine(
        mediaCodecName: String?,
        receivedFps: Float?,
        decodedFps: Float?,
        width: Int,
        height: Int,
        wifiBand: String?,
    ): String {
        val decoder = mediaCodecName?.takeIf { it.isNotBlank() } ?: "—"
        val rec = formatFps(receivedFps)
        val dec = formatFps(decodedFps)
        val fpsSeg = "rec=$rec dec=$dec"
        val res = if (width > 0 && height > 0) "${width}x${height}" else "—"
        val band = wifiBand?.takeIf { it.isNotBlank() } ?: "—"
        return "$decoder | $fpsSeg | $res | $band"
    }

    fun formatFps(fps: Float?): String {
        if (fps == null || fps < 0f) return "—"
        return "%.1f".format(fps)
    }
}
