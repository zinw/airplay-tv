package com.flymop.airplaytv.renderer

import java.nio.ByteBuffer

/**
 * Annex-B NAL helpers for mirror recovery.
 *
 * Important: SPS/PPS/VPS alone are **not** a safe decode restart point for coded
 * slices, but they **must still be fed** to MediaCodec while awaiting IDR — AirPlay
 * often sends parameter sets in a separate AU before the IDR. Dropping them leaves
 * the decoder without CSD and yields a black Surface.
 */
object VideoNalUtils {

    /** True if the access unit contains an IDR (AVC) or IRAP IDR/CRA/BLA (HEVC). */
    fun containsIdr(data: ByteBuffer, size: Int, isH265: Boolean): Boolean {
        forEachNalHeader(data, size) { header ->
            if (isH265) {
                val type = (header shr 1) and 0x3F
                // 16–18 BLA, 19–20 IDR, 21 CRA — all clean random-access points
                if (type in 16..21) return true
            } else {
                val type = header and 0x1F
                if (type == 5) return true // IDR slice
            }
        }
        return false
    }

    /** Parameter sets — feed while awaiting IDR; do not clear the await gate alone. */
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

    /**
     * Safe to enqueue while [awaitingIdr]: real IDR/IRAP, or parameter-set AUs that
     * must reach the decoder before the recovery frame.
     */
    fun isRecoverableInput(data: ByteBuffer, size: Int, isH265: Boolean): Boolean =
        containsIdr(data, size, isH265) || containsParamSets(data, size, isH265)

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
