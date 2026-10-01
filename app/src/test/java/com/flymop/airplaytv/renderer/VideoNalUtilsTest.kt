package com.flymop.airplaytv.renderer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class VideoNalUtilsTest {
    @Test
    fun avc_sps_and_idr_are_keyframes_p_slice_is_not() {
        val spsOnly = annexB(byteArrayOf(0x67.toByte(), 0x42, 0x00, 0x0A)) // type 7 SPS
        assertTrue(VideoNalUtils.isKeyframe(spsOnly, spsOnly.capacity(), isH265 = false))

        val idr = annexB(byteArrayOf(0x65.toByte(), 0x88.toByte(), 0x84.toByte())) // type 5 IDR
        assertTrue(VideoNalUtils.isKeyframe(idr, idr.capacity(), isH265 = false))

        val pSlice = annexB(byteArrayOf(0x41, 0x9A.toByte())) // type 1
        assertFalse(VideoNalUtils.isKeyframe(pSlice, pSlice.capacity(), isH265 = false))
    }

    @Test
    fun avc_sps_plus_idr_in_same_au_is_keyframe() {
        val au = ByteBuffer.allocate(32)
        au.put(byteArrayOf(0, 0, 0, 1, 0x67, 0x42))
        au.put(byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte()))
        val size = au.position()
        au.clear()
        assertTrue(VideoNalUtils.isKeyframe(au, size, isH265 = false))
    }

    @Test
    fun hevc_vps_and_idr_are_keyframes() {
        val vps = annexB(byteArrayOf(0x40, 0x01)) // type 32 VPS
        assertTrue(VideoNalUtils.isKeyframe(vps, vps.capacity(), isH265 = true))

        val idr = annexB(byteArrayOf(0x26, 0x01)) // type 19 IDR_W_RADL
        assertTrue(VideoNalUtils.isKeyframe(idr, idr.capacity(), isH265 = true))

        val trail = annexB(byteArrayOf(0x02, 0x01)) // type 1 TRAIL_R
        assertFalse(VideoNalUtils.isKeyframe(trail, trail.capacity(), isH265 = true))
    }

    private fun annexB(nal: ByteArray): ByteBuffer {
        val buf = ByteBuffer.allocate(4 + nal.size)
        buf.put(byteArrayOf(0, 0, 0, 1))
        buf.put(nal)
        buf.clear()
        return buf
    }
}
