package com.flymop.airplaytv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    @Test
    fun parseVersionCode_fromBody() {
        val body = "Notes\nversionCode: 12\nMore"
        assertEquals(12L, AppUpdateChecker.parseRemoteVersionCode("v1.0.1", body))
    }

    @Test
    fun parseVersionCode_fromTagBuildMeta() {
        assertEquals(12L, AppUpdateChecker.parseRemoteVersionCode("v1.0.1+12", ""))
    }

    @Test
    fun parseVersionName_fromTag() {
        assertEquals("1.0.1", AppUpdateChecker.parseRemoteVersionName("v1.0.1"))
        assertEquals("1.0.1", AppUpdateChecker.parseRemoteVersionName("v1.0.1+12"))
        assertNull(AppUpdateChecker.parseRemoteVersionName("v12"))
    }

    @Test
    fun isNewer_prefersVersionCode() {
        assertTrue(AppUpdateChecker.isNewer(2, "1.0.0", 1, "9.9.9"))
        assertFalse(AppUpdateChecker.isNewer(1, "9.9.9", 1, "1.0.0"))
    }

    @Test
    fun isNewer_fallsBackToSemVer() {
        assertTrue(AppUpdateChecker.isNewer(null, "1.0.1", 1, "1.0.0"))
        assertFalse(AppUpdateChecker.isNewer(null, "1.0.0", 1, "1.0.1"))
        assertEquals(1, AppUpdateChecker.compareSemVer("1.2.0", "1.1.9"))
    }
}
