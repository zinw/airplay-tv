package com.flymop.airplaytv.renderer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class VideoNalUtilsTest {
    @Test
    fun avc_idr_detected_sps_alone_not_idr() {
        val spsOnly = annexB(byteArrayOf(0x67.toByte(), 0x42, 0x00, 0x0A)) // type 7 SPS
        assertFalse(VideoNalUtils.containsIdr(spsOnly, spsOnly.capacity(), isH265 = false))
        assertTrue(VideoNalUtils.containsParamSets(spsOnly, spsOnly.capacity(), isH265 = false))

        val idr = annexB(byteArrayOf(0x65.toByte(), 0x88.toByte(), 0x84.toByte())) // type 5 IDR
        assertTrue(VideoNalUtils.containsIdr(idr, idr.capacity(), isH265 = false))
    }

    @Test
    fun avc_sps_plus_idr_in_same_au_is_idr() {
        val au = ByteBuffer.allocate(32)
        // start code + SPS
        au.put(byteArrayOf(0, 0, 0, 1, 0x67, 0x42))
        // start code + IDR
        au.put(byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte()))
        val size = au.position()
        au.clear()
        assertTrue(VideoNalUtils.containsIdr(au, size, isH265 = false))
        assertTrue(VideoNalUtils.containsParamSets(au, size, isH265 = false))
    }

    @Test
    fun hevc_idr_and_vps_distinction() {
        val vps = annexB(byteArrayOf(0x40, 0x01)) // type 32 VPS ((0x40>>1)&0x3F == 32)
        assertFalse(VideoNalUtils.containsIdr(vps, vps.capacity(), isH265 = true))
        assertTrue(VideoNalUtils.containsParamSets(vps, vps.capacity(), isH265 = true))

        val idr = annexB(byteArrayOf(0x26, 0x01)) // type 19 IDR_W_RADL
        assertTrue(VideoNalUtils.containsIdr(idr, idr.capacity(), isH265 = true))
    }

    private fun annexB(nal: ByteArray): ByteBuffer {
        val buf = ByteBuffer.allocate(4 + nal.size)
        buf.put(byteArrayOf(0, 0, 0, 1))
        buf.put(nal)
        buf.clear()
        return buf
    }
}
