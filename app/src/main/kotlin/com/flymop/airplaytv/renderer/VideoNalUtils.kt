package com.flymop.airplaytv.renderer

import java.nio.ByteBuffer

/**
 * Annex-B NAL helpers matching the pre-perf / upstream flymop keyframe rule:
 * AVC IDR (5) or SPS (7); HEVC IDR/CRA (19–21) or VPS/SPS (32/33).
 *
 * SPS alone is treated as a restart point so codec-data AUs are not dropped before
 * an IDR — required for a live picture on typical iOS mirroring.
 */
object VideoNalUtils {

    fun isKeyframe(data: ByteBuffer, size: Int, isH265: Boolean): Boolean {
        forEachNalHeader(data, size) { header ->
            if (isH265) {
                val type = (header shr 1) and 0x3F
                if (type in 19..21 || type == 32 || type == 33) return true
            } else {
                val type = header and 0x1F
                if (type == 5 || type == 7) return true
            }
        }
        return false
    }

    private inline fun forEachNalHeader(data: ByteBuffer, size: Int, block: (header: Int) -> Unit) {
        if (size < 4) return
        val limit = minOf(size - 4, 8192)
        var i = 0
        while (i <= limit) {
            val b0 = data.get(i).toInt() and 0xFF
            val b1 = data.get(i + 1).toInt() and 0xFF
            val b2 = data.get(i + 2).toInt() and 0xFF
            val is4Byte = i + 4 <= size && b0 == 0 && b1 == 0 && b2 == 0 &&
                (data.get(i + 3).toInt() and 0xFF) == 1
            val is3Byte = !is4Byte && i + 3 <= size && b0 == 0 && b1 == 0 && b2 == 1
            if (is4Byte || is3Byte) {
                val headerOffset = if (is4Byte) i + 4 else i + 3
                if (headerOffset < size) {
                    block(data.get(headerOffset).toInt() and 0xFF)
                }
                i += if (is4Byte) 4 else 3
            } else {
                i++
            }
        }
    }
}
