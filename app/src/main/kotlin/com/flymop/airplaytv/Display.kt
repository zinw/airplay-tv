package com.flymop.airplaytv

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager

// physical panel resolution
fun Context.realDisplaySize(): Pair<Int, Int> {
    val dm = getSystemService(DisplayManager::class.java)
    val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)

    // 1. Check active mode physical size
    val mode = display?.mode
    if (mode != null && mode.physicalWidth > 0 && mode.physicalHeight > 0) {
        return mode.physicalWidth to mode.physicalHeight
    }

    // 2. Check supported modes for best resolution
    val maxMode = display?.supportedModes?.maxByOrNull { it.physicalWidth * it.physicalHeight }
    if (maxMode != null && maxMode.physicalWidth > 0 && maxMode.physicalHeight > 0) {
        return maxMode.physicalWidth to maxMode.physicalHeight
    }

    // 3. WindowManager maximum metrics (API 30+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val bounds = wm?.maximumWindowMetrics?.bounds
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
            return bounds.width() to bounds.height()
        }
    }

    // 4. DisplayMetrics fallback
    val metrics = resources.displayMetrics
    if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
        return metrics.widthPixels to metrics.heightPixels
    }

    return 1920 to 1080
}
