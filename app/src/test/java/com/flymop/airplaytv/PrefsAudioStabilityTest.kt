package com.flymop.airplaytv

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefsAudioStabilityTest {
    @Test
    fun nextAudioAdaptiveStep_cyclesThroughAllSteps() {
        assertEquals(1, Prefs.nextAudioAdaptiveStep(0))
        assertEquals(2, Prefs.nextAudioAdaptiveStep(1))
        assertEquals(3, Prefs.nextAudioAdaptiveStep(2))
        assertEquals(4, Prefs.nextAudioAdaptiveStep(3))
        assertEquals(0, Prefs.nextAudioAdaptiveStep(4))
    }

    @Test
    fun nextAudioAdaptiveStep_clampsOutOfRange() {
        assertEquals(1, Prefs.nextAudioAdaptiveStep(-3))
        assertEquals(0, Prefs.nextAudioAdaptiveStep(99))
    }

    @Test
    fun audioStabilityBucket_mapsSteps() {
        assertEquals(0, Prefs.audioStabilityBucket(0))
        assertEquals(0, Prefs.audioStabilityBucket(1))
        assertEquals(1, Prefs.audioStabilityBucket(2))
        assertEquals(1, Prefs.audioStabilityBucket(3))
        assertEquals(2, Prefs.audioStabilityBucket(4))
    }
}
