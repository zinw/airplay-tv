package com.flymop.airplaytv.renderer

/**
 * mirrors native AudioConfig struct; defaults must match preference defaults so a
 * freshly-constructed AudioRenderer is sane.
 *
 * lowLatency is accepted for JNI/configure compat but the native output path always
 * uses shared media Oboe (UxPlay-like). Do not reintroduce Exclusive/Game toggles.
 */
data class AudioConfig(
    val cushionMs: Int = 0,           // 0 = adaptive tuner, otherwise fixed cushion ms
    val percentilePct: Int = 95,
    val oboeBufferFrames: Int = 0,    // 0 = auto
    val forceSwAlac: Boolean = false,
    val realtimePriority: Boolean = false,
    val lowLatency: Boolean = false,  // ignored natively; kept for configure signature
    val benchmarkLog: Boolean = false,
)

/**
 * Screen-mirror A/V: keep audio cushion short so lip-sync tracks near-zero video latency.
 * Adaptive music mode can grow toward 1s; that is wrong when video presents ASAP.
 *
 * - Adaptive (cushionMs == 0) → fixed [mirrorCushionMs]
 * - User fixed cushion → clamp to at most [mirrorCushionMs]
 */
fun AudioConfig.forScreenMirror(mirrorCushionMs: Int = MIRROR_AUDIO_CUSHION_MS): AudioConfig =
    if (cushionMs <= 0) copy(cushionMs = mirrorCushionMs)
    else copy(cushionMs = minOf(cushionMs, mirrorCushionMs))

/** Default fixed cushion while mirroring (matches Prefs.DEF_AUDIO_CUSHION_MS). */
const val MIRROR_AUDIO_CUSHION_MS = 40
