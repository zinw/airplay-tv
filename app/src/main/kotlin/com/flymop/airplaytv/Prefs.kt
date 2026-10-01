package com.flymop.airplaytv

import android.content.SharedPreferences
import android.media.MediaFormat

/** Centralized preference keys and defaults. */
object Prefs {
    const val NAME = "settings"
    const val AUTO = "auto"; const val ON = "on"; const val OFF = "off"

    const val SERVER_NAME = "server_name"
    const val DEF_SERVER_NAME = "Airplay TV"

    fun getServerName(prefs: SharedPreferences): String {
        return prefs.getString(SERVER_NAME, DEF_SERVER_NAME) ?: DEF_SERVER_NAME
    }

    const val FALLBACK_MAC_ADDRESS = "fallback_mac_address"
    const val SERVER_PORT = "server_port"; const val DEF_SERVER_PORT = 7000
    const val AUTO_START = "auto_start"; const val DEF_AUTO_START = true
    const val BOOT_AUTO_START = "boot_auto_start"; const val DEF_BOOT_AUTO_START = true
    const val RUN_IN_BACKGROUND = "run_in_background"; const val DEF_RUN_IN_BACKGROUND = true
    const val H265_ENABLED = "h265_enabled"; const val DEF_H265_ENABLED = true
    const val ENFORCE_SDR = "enforce_sdr"; const val DEF_ENFORCE_SDR = true
    val KEY_ALLOW_FRAME_DROP: String = MediaFormat.KEY_ALLOW_FRAME_DROP; const val DEF_KEY_ALLOW_FRAME_DROP = true
    val KEY_PRIORITY: String = MediaFormat.KEY_PRIORITY; const val DEF_KEY_PRIORITY = true
    const val LOW_LATENCY = "low_latency"; const val DEF_LOW_LATENCY = true
    const val OPERATING_RATE = "operating_rate"; const val DEF_OPERATING_RATE = AUTO
    const val SCHEDULED_OUTPUT_BUFFER_RELEASE = "scheduled_output_buffer_release"; const val DEF_SCHEDULED_OUTPUT_BUFFER_RELEASE = false
    const val AUDIO_AUTO_BUFFER = "audio_auto_buffer"; const val DEF_AUDIO_AUTO_BUFFER = true
    // fixed cushion ms, used only when AUDIO_AUTO_BUFFER is off
    const val AUDIO_CUSHION_MS = "audio_cushion_ms"; const val DEF_AUDIO_CUSHION_MS = 40
    // slider step 0..4 mapping to arrival-delay percentile the cushion targets;
    // lower = less latency, higher = more stable
    const val AUDIO_ADAPTIVE_STEP = "audio_adaptive_step"; const val DEF_AUDIO_ADAPTIVE_STEP = 3
    val ADAPTIVE_PERCENTILES = intArrayOf(80, 85, 90, 95, 99)

    /** Cycle 0→1→2→3→4→0 for TV settings (lower = less latency). */
    fun nextAudioAdaptiveStep(current: Int): Int {
        val clamped = current.coerceIn(0, ADAPTIVE_PERCENTILES.lastIndex)
        return (clamped + 1) % ADAPTIVE_PERCENTILES.size
    }

    /**
     * Coarse label bucket for the adaptive cushion step.
     * Steps 0–1 → low latency, 2–3 → balanced, 4 → stable.
     */
    fun audioStabilityBucket(step: Int): Int = when (step.coerceIn(0, ADAPTIVE_PERCENTILES.lastIndex)) {
        0, 1 -> 0
        2, 3 -> 1
        else -> 2
    }
    const val OBOE_BUFFER_FRAMES = "oboe_buffer_frames"; const val DEF_OBOE_BUFFER_FRAMES = 0
    const val ALAC_ENABLED = "alac_enabled"; const val DEF_ALAC_ENABLED = true
    const val FORCE_SW_ALAC = "force_sw_alac"; const val DEF_FORCE_SW_ALAC = false
    const val AAC_ENABLED = "aac_enabled"; const val DEF_AAC_ENABLED = true
    const val RESOLUTION = "resolution"; const val DEF_RESOLUTION = AUTO
    const val MAX_FPS = "max_fps"; const val DEF_MAX_FPS = 60
    const val OVERSCANNED = "overscanned"; const val DEF_OVERSCANNED = false
    /** Preference key for on-screen AirPlay PIN pairing. */
    const val REQUIRE_PIN = "require_pin"
    /** Fresh installs: PIN pairing is disabled until the user turns it on. */
    const val DEF_REQUIRE_PIN = false

    fun isRequirePin(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(REQUIRE_PIN, DEF_REQUIRE_PIN)

    /**
     * TV PIN overlay / notification should appear only when the user enabled PIN
     * pairing and native auth actually supplied a code.
     */
    fun shouldShowPinOverlay(requirePin: Boolean, pin: String?): Boolean =
        requirePin && !pin.isNullOrBlank()

    const val ALLOW_NEW_CONN = "allow_new_conn"; const val DEF_ALLOW_NEW_CONN = true
    const val AUDIO_LATENCY_MS = "audio_latency_ms"; const val DEF_AUDIO_LATENCY_MS = -1
    const val DEBUG_ENABLED = "debug_enabled"; const val DEF_DEBUG_ENABLED = false
    const val DEVELOPER_OPTIONS = "developer_options"; const val DEF_DEVELOPER_OPTIONS = false
    const val BENCHMARK_LOG = "benchmark_log"; const val DEF_BENCHMARK_LOG = false
    const val IDLE_PREVIEW = "idle_preview"; const val DEF_IDLE_PREVIEW = false
    const val AUTO_FULLSCREEN = "auto_fullscreen"; const val DEF_AUTO_FULLSCREEN = true
    const val KEEP_SCREEN_ON = "keep_screen_on"; const val DEF_KEEP_SCREEN_ON = true
    const val ADVERTISE_VIDEO = "advertise_video"; const val DEF_ADVERTISE_VIDEO = true
    const val ADVERTISE_AUDIO = "advertise_audio"; const val DEF_ADVERTISE_AUDIO = true
    const val LAUNCH_ON_CONNECT = "launch_on_connect"; const val DEF_LAUNCH_ON_CONNECT = true
    /**
     * UI language preference: `"system"`, `"en"`, or `"zh-CN"`.
     * Applied via AppCompat per-app locales (`system` clears the override).
     */
    const val LANGUAGE = "language"; const val DEF_LANGUAGE = "en"
}
