package net.skailine.floatingmusic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout

class ClippedFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var cornerRadii: FloatArray? = null
        set(value) {
            field = value
            invalidate()
        }

    private val clipPath = Path()
    private val rectF = RectF()

    override fun dispatchDraw(canvas: Canvas) {
        val radii = cornerRadii
        if (radii != null) {
            rectF.set(0f, 0f, width.toFloat(), height.toFloat())
            clipPath.reset()
            clipPath.addRoundRect(rectF, radii, Path.Direction.CW)
            
            canvas.save()
            canvas.clipPath(clipPath)
            super.dispatchDraw(canvas)
            canvas.restore()
        } else {
            super.dispatchDraw(canvas)
        }
    }
}
