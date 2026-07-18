package net.skailine.floatingmusic

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * 覆蓋整張卡片的透明觸控覆層。
 * 依觸碰落點分左 / 中 / 右三區觸發播控，並支援拖曳移動懸浮視窗。
 * 無任何視覺動畫。
 */
class CardTouchOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onPreviousClick()
        fun onPlayPauseClick()
        fun onNextClick()
        fun onDrag(dx: Float, dy: Float)
        fun onDragEnd()
    }

    var listener: Listener? = null

    private enum class Zone { LEFT, CENTER, RIGHT }

    private var touchedZone: Zone? = null
    private var isDragging = false

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastDragX = 0f
    private var lastDragY = 0f

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        background = null
        foreground = null
        stateListAnimator = null
        setWillNotDraw(true)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                lastDragX = event.rawX
                lastDragY = event.rawY
                touchedZone = detectZone(event.x)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    isDragging = true
                }
                if (isDragging) {
                    listener?.onDrag(event.rawX - lastDragX, event.rawY - lastDragY)
                    lastDragX = event.rawX
                    lastDragY = event.rawY
                }
            }

            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    listener?.onDragEnd()
                } else {
                    when (touchedZone) {
                        Zone.LEFT   -> listener?.onPreviousClick()
                        Zone.CENTER -> listener?.onPlayPauseClick()
                        Zone.RIGHT  -> listener?.onNextClick()
                        null        -> Unit
                    }
                }
                isDragging = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    listener?.onDragEnd()
                }
                isDragging = false
            }
        }
        return true
    }

    private fun detectZone(x: Float): Zone {
        val third = width / 3f
        return when {
            x < third      -> Zone.LEFT
            x > third * 2f -> Zone.RIGHT
            else           -> Zone.CENTER
        }
    }
}
