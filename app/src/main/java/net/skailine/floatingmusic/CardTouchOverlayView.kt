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
        fun onDoubleTap()
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
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private var lastUpTime = 0L
    private val clickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSingleClick: Runnable? = null

    init {
        background = null
        foreground = null
        stateListAnimator = null
        setWillNotDraw(true)
    }

    var doubleTapEnabled = true

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
                    // 如果開始拖曳，取消正在等待的單擊事件
                    pendingSingleClick?.let { clickHandler.removeCallbacks(it) }
                    pendingSingleClick = null
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
                    val zone = touchedZone
                    val action = Runnable {
                        when (zone) {
                            Zone.LEFT   -> listener?.onPreviousClick()
                            Zone.CENTER -> listener?.onPlayPauseClick()
                            Zone.RIGHT  -> listener?.onNextClick()
                            null -> {}
                        }
                    }

                    if (!doubleTapEnabled) {
                        action.run()
                    } else {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastUpTime < doubleTapTimeout) {
                            // 雙擊觸發
                            pendingSingleClick?.let { clickHandler.removeCallbacks(it) }
                            pendingSingleClick = null
                            listener?.onDoubleTap()
                            lastUpTime = 0L
                        } else {
                            // 單擊，延遲觸發以等待是否有雙擊
                            lastUpTime = currentTime
                            pendingSingleClick = action
                            clickHandler.postDelayed(action, doubleTapTimeout)
                        }
                    }
                }
                touchedZone = null
                isDragging = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    listener?.onDragEnd()
                }
                pendingSingleClick?.let { clickHandler.removeCallbacks(it) }
                pendingSingleClick = null
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
