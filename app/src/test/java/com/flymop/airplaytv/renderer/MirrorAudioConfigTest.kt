package com.flymop.airplaytv.renderer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Screen-mirror audio must not inherit the adaptive music cushion (up to ~1s),
 * or lip-sync drifts while video presents ASAP.
 */
class MirrorAudioConfigTest {
    @Test
    fun adaptiveBecomesFixedMirrorCushion() {
        val base = AudioConfig(cushionMs = 0, percentilePct = 95)
        val mirror = base.forScreenMirror(40)
        assertEquals(40, mirror.cushionMs)
        assertEquals(95, mirror.percentilePct)
    }

    @Test
    fun fixedCushionIsClampedToMirrorCap() {
        val base = AudioConfig(cushionMs = 200)
        assertEquals(40, base.forScreenMirror(40).cushionMs)
    }

    @Test
    fun fixedCushionBelowCapIsUnchanged() {
        val base = AudioConfig(cushionMs = 25)
        assertEquals(25, base.forScreenMirror(40).cushionMs)
    }

    @Test
    fun defaultMirrorConstantMatchesPrefsDefault() {
        assertEquals(40, MIRROR_AUDIO_CUSHION_MS)
    }
}
