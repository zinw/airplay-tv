package io.github.jqssun.airplay.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 32
    private val barHeights = FloatArray(barCount) { 0.1f }
    private val targetHeights = FloatArray(barCount) { 0.1f }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var shader: LinearGradient? = null
    private var isPlaying = false

    fun setPlaying(playing: Boolean) {
        isPlaying = playing
        if (playing) {
            updateWave()
        } else {
            for (i in barHeights.indices) {
                targetHeights[i] = 0.05f
            }
            invalidate()
        }
    }

    fun updateAmplitude(rms: Float) {
        if (!isPlaying) return
        val clamped = rms.coerceIn(0.05f, 1.0f)
        for (i in barHeights.indices) {
            val variation = (Random.nextFloat() * 0.4f - 0.2f)
            targetHeights[i] = (clamped + variation).coerceIn(0.05f, 1.0f)
        }
        invalidate()
    }

    private fun updateWave() {
        if (!isPlaying) return
        for (i in barHeights.indices) {
            targetHeights[i] = Random.nextFloat() * 0.8f + 0.1f
        }
        postInvalidateDelayed(80)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shader = LinearGradient(
            0f, h.toFloat(), 0f, 0f,
            Color.parseColor("#00E5FF"),
            Color.parseColor("#7C4DFF"),
            Shader.TileMode.CLAMP
        )
        paint.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val barWidth = (w / barCount) * 0.7f
        val gap = (w / barCount) * 0.3f

        for (i in 0 until barCount) {
            barHeights[i] += (targetHeights[i] - barHeights[i]) * 0.3f
            val barH = barHeights[i] * h
            val left = i * (barWidth + gap)
            val top = h - barH
            val right = left + barWidth
            val bottom = h
            canvas.drawRoundRect(left, top, right, bottom, 8f, 8f, paint)
        }

        if (isPlaying) {
            invalidate()
        }
    }
}
