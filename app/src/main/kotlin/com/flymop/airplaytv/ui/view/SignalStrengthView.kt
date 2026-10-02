package com.flymop.airplaytv.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.flymop.airplaytv.R

/**
 * Three vertical bars of increasing height — competitor-style mirror HUD signal icon.
 * Level 0–3; strong links light all bars neon green.
 */
class SignalStrengthView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.hud_signal_strong)
    }
    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.hud_signal_weak)
    }

    /** 0 = none lit, 3 = all bars (strong). */
    var level: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, BAR_COUNT)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val gap = w * 0.14f
        val barW = (w - gap * (BAR_COUNT - 1)) / BAR_COUNT
        val heights = floatArrayOf(h * 0.40f, h * 0.68f, h)

        for (i in 0 until BAR_COUNT) {
            val left = i * (barW + gap)
            val top = h - heights[i]
            val paint = if (i < level) activePaint else inactivePaint
            canvas.drawRect(left, top, left + barW, h, paint)
        }
    }

    companion object {
        private const val BAR_COUNT = 3
    }
}
