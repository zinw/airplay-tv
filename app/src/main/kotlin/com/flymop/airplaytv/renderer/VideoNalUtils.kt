package com.flymop.airplaytv.renderer

import java.nio.ByteBuffer

/**
 * Annex-B NAL helpers for mirror recovery.
 *
 * Important: SPS/PPS/VPS alone are **not** a safe decode restart point.
 * Treating them as keyframes lets P-frames land on an empty reference list
 * and produces mosaic / stale-tile corruption on HW decoders (Honor etc.).
 */
object VideoNalUtils {

    /** True if the access unit contains an IDR (AVC) or IDR/CRA (HEVC). */
    fun containsIdr(data: ByteBuffer, size: Int, isH265: Boolean): Boolean {
        forEachNalHeader(data, size) { header ->
            if (isH265) {
                val type = (header shr 1) and 0x3F
                // 19 IDR_W_RADL, 20 IDR_N_LP, 21 CRA_NUT (common AirPlay recovery)
                if (type in 19..21) return true
            } else {
                val type = header and 0x1F
                if (type == 5) return true // IDR slice
            }
        }
        return false
    }

    /** Parameter sets only — useful for logging, not for clearing await-IDR. */
    fun containsParamSets(data: ByteBuffer, size: Int, isH265: Boolean): Boolean {
        forEachNalHeader(data, size) { header ->
            if (isH265) {
                val type = (header shr 1) and 0x3F
                if (type == 32 || type == 33 || type == 34) return true // VPS/SPS/PPS
            } else {
                val type = header and 0x1F
                if (type == 7 || type == 8) return true // SPS/PPS
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
