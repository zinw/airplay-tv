package com.flymop.airplaytv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

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

    @Test
    fun candidateUrls_prefersGithubThenMirrors() {
        val primary = "https://github.com/zinw/airplay-tv/releases/download/v1.0.3/AirPlayTV-1.0.3-arm64-v8a.apk"
        val urls = ApkInstaller.candidateUrls(primary)
        assertEquals(primary, urls.first())
        assertTrue(urls.contains("https://ghproxy.net/$primary"))
        assertTrue(urls.contains("https://ghfast.top/$primary"))
        assertEquals(1 + ApkInstaller.MIRROR_PREFIXES.size, urls.size)
    }

    @Test
    fun candidateUrls_skipsMirrorsForNonGithub() {
        val primary = "https://example.com/app.apk"
        assertEquals(listOf(primary), ApkInstaller.candidateUrls(primary))
    }

    @Test
    fun sourceLabel_identifiesMirrors() {
        assertEquals("GitHub", ApkInstaller.sourceLabel("https://github.com/a/b/releases/download/v1/a.apk"))
        assertEquals("ghproxy.net", ApkInstaller.sourceLabel("https://ghproxy.net/https://github.com/a/b/x.apk"))
        assertEquals("ghfast.top", ApkInstaller.sourceLabel("https://ghfast.top/https://github.com/a/b/x.apk"))
    }

    @Test
    fun validateApkFile_rejectsEmptyAndBadMagic() {
        val empty = File.createTempFile("apk-empty", ".apk")
        empty.writeBytes(ByteArray(0))
        try {
            ApkInstaller.validateApkFile(empty, expectedSizeBytes = -1L)
            fail("expected empty rejection")
        } catch (_: Exception) {
            // expected
        } finally {
            empty.delete()
        }

        val tiny = File.createTempFile("apk-tiny", ".apk")
        tiny.writeBytes(ByteArray(8) { 0 })
        try {
            ApkInstaller.validateApkFile(tiny, expectedSizeBytes = -1L)
            fail("expected size rejection")
        } catch (_: Exception) {
            // expected
        } finally {
            tiny.delete()
        }

        val badMagic = File.createTempFile("apk-bad", ".apk")
        badMagic.writeBytes(ByteArray(70 * 1024) { 1 })
        try {
            ApkInstaller.validateApkFile(badMagic, expectedSizeBytes = -1L)
            fail("expected magic rejection")
        } catch (_: Exception) {
            // expected
        } finally {
            badMagic.delete()
        }
    }

    @Test
    fun validateApkFile_acceptsZipLocalHeaderAndExactSize() {
        val size = 70 * 1024
        val apk = File.createTempFile("apk-ok", ".apk")
        val bytes = ByteArray(size)
        // PK\u0003\u0004 little-endian
        bytes[0] = 0x50
        bytes[1] = 0x4b
        bytes[2] = 0x03
        bytes[3] = 0x04
        apk.writeBytes(bytes)
        try {
            ApkInstaller.validateApkFile(apk, expectedSizeBytes = size.toLong())
        } finally {
            apk.delete()
        }
    }
}
