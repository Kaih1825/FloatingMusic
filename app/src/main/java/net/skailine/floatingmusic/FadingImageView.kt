package net.skailine.floatingmusic

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * 在圖片上疊加一層「從透明到卡片背景色」的漸層遮罩。
 *
 * 不使用 DST_IN xfermode / saveLayer（在硬體加速下不穩定），
 * 改用標準 SRC_OVER 直接覆蓋。右側永遠是卡片底色，沒有任何殘影。
 *
 * overlayGradient 顏色需與 cardBackgroundColor (#D9101820) 一致。
 */
class FadingImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    // 卡片背景色：#D9101820 = argb(217, 16, 24, 32)
    private val cardBg = Color.argb(217, 16, 24, 32)

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var overlayGradient: LinearGradient? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 左→右：從透明逐漸過渡到卡片底色
        // 前 30% 完全透明（圖片完整顯示），之後以 ease-out 曲線趨近底色
        overlayGradient = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(
                Color.TRANSPARENT,          // 0%   — 圖片 100% 可見
                Color.TRANSPARENT,          // 30%  — 圖片 100% 可見
                Color.argb(80,  16, 24, 32), // 52%  — 圖片 ~69% 可見
                Color.argb(160, 16, 24, 32), // 72%  — 圖片 ~37% 可見
                Color.argb(210, 16, 24, 32), // 88%  — 圖片 ~8%  可見
                cardBg                       // 100% — 完全是卡片底色，零殘影
            ),
            floatArrayOf(0f, 0.30f, 0.52f, 0.72f, 0.88f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)                         // 先畫圖片
        overlayPaint.shader = overlayGradient
        canvas.drawRect(                             // 再疊上漸層遮罩
            0f, 0f, width.toFloat(), height.toFloat(), overlayPaint
        )
    }
}