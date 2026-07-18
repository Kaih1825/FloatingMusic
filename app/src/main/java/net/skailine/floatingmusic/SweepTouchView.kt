package net.skailine.floatingmusic

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 按下時以方向性掃光動畫回饋觸控的透明 View。
 *
 * 支援三種方向（透過 XML attr `sweepDirection`）：
 *   - leftToRight  (0)：光從左邊掃向右邊
 *   - rightToLeft  (1)：光從右邊掃向左邊
 *   - centerOut    (2)：光從中間向兩側擴散
 *
 * 外部的 setOnClickListener / setOnTouchListener 仍然正常運作，
 * 因為 onTouchEvent 最終會呼叫 super，保留點擊事件傳遞。
 */
class SweepTouchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class SweepDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT, CENTER_OUT }

    var sweepDirection: SweepDirection = SweepDirection.CENTER_OUT
    var sweepColor: Int = Color.argb(70, 255, 255, 255)

    /** 目前動畫進度 0f（完全收起）~ 1f（完全展開） */
    private var animProgress = 0f
    private var releaseAnimator: ValueAnimator? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 按下時展開動畫 */
    private val pressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 220
        interpolator = DecelerateInterpolator(1.5f)
        addUpdateListener {
            animProgress = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        // 讀取 XML 自訂屬性
        context.obtainStyledAttributes(attrs, R.styleable.SweepTouchView).use { ta ->
            sweepDirection = SweepDirection.entries[
                ta.getInt(R.styleable.SweepTouchView_sweepDirection, 2)
            ]
            sweepColor = ta.getColor(
                R.styleable.SweepTouchView_sweepColor,
                Color.argb(70, 255, 255, 255)
            )
        }
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (animProgress <= 0f) return

        fillPaint.color = sweepColor
        val w = width.toFloat()
        val h = height.toFloat()

        when (sweepDirection) {
            SweepDirection.LEFT_TO_RIGHT ->
                canvas.drawRect(0f, 0f, w * animProgress, h, fillPaint)

            SweepDirection.RIGHT_TO_LEFT ->
                canvas.drawRect(w * (1f - animProgress), 0f, w, h, fillPaint)

            SweepDirection.CENTER_OUT -> {
                val half = w / 2f
                val spread = half * animProgress
                canvas.drawRect(half - spread, 0f, half + spread, h, fillPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                releaseAnimator?.cancel()
                pressAnimator.cancel()
                pressAnimator.start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressAnimator.cancel()
                // 從目前進度淡出回 0
                releaseAnimator = ValueAnimator.ofFloat(animProgress, 0f).apply {
                    duration = 180
                    interpolator = DecelerateInterpolator()
                    addUpdateListener {
                        animProgress = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
        }
        // 必須呼叫 super，保留 click listener 與外部 touch listener 的正常運作
        return super.onTouchEvent(event)
    }
}
