package com.flymop.airplaytv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: PIN pairing must be off by default, and the overlay must not
 * appear unless both the preference and a live PIN value are present.
 */
class PrefsPinAuthTest {
    @Test
    fun requirePin_disabledByDefaultForFreshInstalls() {
        assertFalse(
            "Fresh installs must not require AirPlay PIN pairing",
            Prefs.DEF_REQUIRE_PIN
        )
        assertEquals("require_pin", Prefs.REQUIRE_PIN)
    }

    @Test
    fun shouldShowPinOverlay_requiresPreferenceAndPin() {
        assertFalse(Prefs.shouldShowPinOverlay(requirePin = false, pin = "1234"))
        assertFalse(Prefs.shouldShowPinOverlay(requirePin = true, pin = null))
        assertFalse(Prefs.shouldShowPinOverlay(requirePin = true, pin = ""))
        assertFalse(Prefs.shouldShowPinOverlay(requirePin = true, pin = "   "))
        assertTrue(Prefs.shouldShowPinOverlay(requirePin = true, pin = "1234"))
    }
}
