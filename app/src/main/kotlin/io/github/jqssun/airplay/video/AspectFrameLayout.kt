package io.github.jqssun.airplay.video

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

class AspectFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var targetAspectRatio: Float = 16f / 9f

    fun setAspectRatio(ratio: Float) {
        if (ratio > 0 && Math.abs(targetAspectRatio - ratio) > 0.01f) {
            targetAspectRatio = ratio
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (targetAspectRatio <= 0f) return

        val initialWidth = measuredWidth
        val initialHeight = measuredHeight

        var viewAspectRatio = initialWidth.toFloat() / initialHeight.toFloat()
        val aspectDiff = targetAspectRatio / viewAspectRatio - 1

        if (Math.abs(aspectDiff) <= 0.01) {
            return
        }

        var newWidth = initialWidth
        var newHeight = initialHeight

        if (aspectDiff > 0) {
            newHeight = (initialWidth / targetAspectRatio).toInt()
        } else {
            newWidth = (initialHeight * targetAspectRatio).toInt()
        }

        super.onMeasure(
            MeasureSpec.makeMeasureSpec(newWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(newHeight, MeasureSpec.EXACTLY)
        )
    }
}
