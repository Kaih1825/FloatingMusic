package net.skailine.floatingmusic

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Outline
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.TextView

class MusicOverlayService : NotificationListenerService() {

    companion object {
        const val ACTION_HIDE_OVERLAY = "net.skailine.floatingmusic.HIDE_OVERLAY"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var overlayParams: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences

    private var currentController: MediaController? = null
    private val controllerCallbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private var isOverlayAdded = false

    // 監聽 SharedPreferences 變化，即時套用圓角（同 process 直接觸發，不需廣播）
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        val settingKeys = setOf(
            MainActivity.KEY_CORNER_TOP_LEFT,
            MainActivity.KEY_CORNER_TOP_RIGHT,
            MainActivity.KEY_CORNER_BOTTOM_LEFT,
            MainActivity.KEY_CORNER_BOTTOM_RIGHT,
            MainActivity.KEY_TEXT_ALIGN_LEFT,
            MainActivity.KEY_OVERLAY_SIZE
        )
        if (key in settingKeys && isOverlayAdded) {
            overlayView.post {
                applyCornerSettings()
                applyTextAlignmentSettings()
                applySizeSettings()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        initMediaListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE_OVERLAY -> hideOverlay()
            else                -> showOverlay()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    // ── Overlay 顯示 / 隱藏 ──────────────────────────────────────────────────

    private fun showOverlay() {
        if (isOverlayAdded) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val themeContext = ContextThemeWrapper(
            this, com.google.android.material.R.style.Theme_MaterialComponents_DayNight
        )
        overlayView = LayoutInflater.from(themeContext).inflate(R.layout.overlay, null)
        overlayView.visibility = View.GONE

        val savedX = prefs.getInt("x", 0)
        val savedY = prefs.getInt("y", 100)

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = savedX
            y = savedY
        }

        windowManager.addView(overlayView, overlayParams)
        isOverlayAdded = true

        // 等 View 完成 layout 後再套用設定，確保 width/height 已有值
        overlayView.post { 
            applyCornerSettings() 
            applyTextAlignmentSettings()
            applySizeSettings()
        }
        setupTouchOverlay()
        updateUI(currentController?.metadata)
    }

    private fun hideOverlay() {
        if (::overlayView.isInitialized && isOverlayAdded) {
            windowManager.removeView(overlayView)
            isOverlayAdded = false
        }
    }

    // ── 圓角套用 ──────────────────────────────────────────────────────────────

    /**
     * 同步更新三個地方，讓圓角設定完全生效：
     *
     *   1. cardOverlay 背景（GradientDrawable）→ 控制黑色外框的視覺圓角
     *   2. cardOverlay 的自訂 ViewOutlineProvider → 控制 clipToOutline 的裁切形狀
     *      ※ 不能依賴 GradientDrawable.getOutline()：帶有 cornerRadii 陣列時，
     *        內部 mPath 在首次繪製前尚未建立，invalidateOutline() 會靜默失敗。
     *   3. ShapeableImageView.shapeAppearanceModel → 控制專輯封面的自身裁切形狀
     */
    private fun applyCornerSettings() {
        val card = overlayView.findViewById<View>(R.id.cardOverlay) ?: return
        val ivAlbumCover = overlayView
            .findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.ivAlbumCover)

        val topLeft     = prefs.getBoolean(MainActivity.KEY_CORNER_TOP_LEFT,     false)
        val topRight    = prefs.getBoolean(MainActivity.KEY_CORNER_TOP_RIGHT,    true)
        val bottomLeft  = prefs.getBoolean(MainActivity.KEY_CORNER_BOTTOM_LEFT,  false)
        val bottomRight = prefs.getBoolean(MainActivity.KEY_CORNER_BOTTOM_RIGHT, true)

        val r = resources.displayMetrics.density * 35f // 35dp → px
        // cornerRadii 順序：TL, TR, BR, BL（順時針，每角兩個值 x/y）
        val radii = floatArrayOf(
            if (topLeft)     r else 0f, if (topLeft)     r else 0f,
            if (topRight)    r else 0f, if (topRight)    r else 0f,
            if (bottomRight) r else 0f, if (bottomRight) r else 0f,
            if (bottomLeft)  r else 0f, if (bottomLeft)  r else 0f
        )

        // 1. 更新視覺背景
        card.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xD9101820.toInt())
            cornerRadii = radii
        }

        // 2. 自訂 ViewOutlineProvider：直接建立 Path，不走 GradientDrawable.getOutline()
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val path = Path().apply {
                    addRoundRect(
                        RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()),
                        radii,
                        Path.Direction.CW
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    outline.setPath(path)
                } else {
                    @Suppress("DEPRECATION")
                    outline.setConvexPath(path)
                }
            }
        }
        card.clipToOutline = true
        card.invalidateOutline()

        // 3. 同步更新 ShapeableImageView 裁切形狀
        ivAlbumCover?.shapeAppearanceModel =
            com.google.android.material.shape.ShapeAppearanceModel.builder()
                .setTopLeftCornerSize(if (topLeft)     r else 0f)
                .setTopRightCornerSize(if (topRight)    r else 0f)
                .setBottomRightCornerSize(if (bottomRight) r else 0f)
                .setBottomLeftCornerSize(if (bottomLeft)  r else 0f)
                .build()
    }

    private fun applyTextAlignmentSettings() {
        val isAlignLeft = prefs.getBoolean(MainActivity.KEY_TEXT_ALIGN_LEFT, false)
        val layoutText = overlayView.findViewById<android.widget.LinearLayout>(R.id.layoutText)
        val tvSongTitle = overlayView.findViewById<TextView>(R.id.tvSongTitle)
        val tvArtist = overlayView.findViewById<TextView>(R.id.tvArtist)
        val albumScrim = overlayView.findViewById<View>(R.id.albumScrim)

        if (layoutText == null || tvSongTitle == null || tvArtist == null || albumScrim == null) return

        val params = layoutText.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        
        if (isAlignLeft) {
            // 文字在左，留出右邊 110dp 給封面，左邊 20dp padding
            params.marginStart = (20 * resources.displayMetrics.density).toInt()
            layoutText.setPadding(0, 0, (110 * resources.displayMetrics.density).toInt(), 0)
            
            // TextView 靠左
            val titleParams = tvSongTitle.layoutParams as android.widget.LinearLayout.LayoutParams
            titleParams.gravity = Gravity.START
            tvSongTitle.layoutParams = titleParams
            
            val artistParams = tvArtist.layoutParams as android.widget.LinearLayout.LayoutParams
            artistParams.gravity = Gravity.START
            tvArtist.layoutParams = artistParams

            // 反轉遮罩，讓深色在左邊
            albumScrim.scaleX = -1f
        } else {
            // 文字在右，留出左邊 110dp 給封面，右邊 20dp padding
            params.marginStart = (110 * resources.displayMetrics.density).toInt()
            layoutText.setPadding(0, 0, (20 * resources.displayMetrics.density).toInt(), 0)
            
            // TextView 靠右
            val titleParams = tvSongTitle.layoutParams as android.widget.LinearLayout.LayoutParams
            titleParams.gravity = Gravity.END
            tvSongTitle.layoutParams = titleParams
            
            val artistParams = tvArtist.layoutParams as android.widget.LinearLayout.LayoutParams
            artistParams.gravity = Gravity.END
            tvArtist.layoutParams = artistParams

            // 恢復遮罩方向
            albumScrim.scaleX = 1f
        }
        layoutText.layoutParams = params
    }

    private fun applySizeSettings() {
        val progress = prefs.getInt(MainActivity.KEY_OVERLAY_SIZE, 50)
        // 50 是 1.0 倍大小 (範圍：0.5倍 ~ 1.5倍)
        val scale = 0.5f + (progress / 100f)

        val card = overlayView.findViewById<View>(R.id.cardOverlay) ?: return
        
        // 1. 放縮卡片本身（包含文字、圖片都會一起等比例縮放）
        card.scaleX = scale
        card.scaleY = scale

        // 2. 調整 WindowManager 的 LayoutParams 邊界，避免放大時被裁切
        val baseWidthPx = (340 * resources.displayMetrics.density).toInt()
        val baseHeightPx = (72 * resources.displayMetrics.density).toInt()

        overlayParams.width = (baseWidthPx * scale).toInt()
        overlayParams.height = (baseHeightPx * scale).toInt()

        if (isOverlayAdded) {
            windowManager.updateViewLayout(overlayView, overlayParams)
        }
    }

    // ── 觸控 ──────────────────────────────────────────────────────────────────

    /**
     * 對接 CardTouchOverlayView：
     *   - 左區  → 上一首
     *   - 中區  → 播放 / 暫停
     *   - 右區  → 下一首
     *   - 拖曳  → 移動懸浮視窗
     */
    private fun setupTouchOverlay() {
        overlayView.findViewById<CardTouchOverlayView>(R.id.cardTouchOverlay)
            .listener = object : CardTouchOverlayView.Listener {

            override fun onPreviousClick() {
                currentController?.transportControls?.skipToPrevious()
            }

            override fun onPlayPauseClick() {
                currentController?.playbackState?.let { state ->
                    if (state.state == android.media.session.PlaybackState.STATE_PLAYING) {
                        currentController?.transportControls?.pause()
                    } else {
                        currentController?.transportControls?.play()
                    }
                }
            }

            override fun onNextClick() {
                currentController?.transportControls?.skipToNext()
            }

            override fun onDrag(dx: Float, dy: Float) {
                overlayParams.x += dx.toInt()
                overlayParams.y += dy.toInt()
                windowManager.updateViewLayout(overlayView, overlayParams)
            }

            override fun onDragEnd() {
                prefs.edit()
                    .putInt("x", overlayParams.x)
                    .putInt("y", overlayParams.y)
                    .apply()
            }
        }
    }

    // ── Media Session ─────────────────────────────────────────────────────────

    private fun initMediaListener() {
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val componentName = ComponentName(this, MusicOverlayService::class.java)

        val activeSessions = mediaSessionManager.getActiveSessions(componentName)
        registerAllControllers(activeSessions)

        mediaSessionManager.addOnActiveSessionsChangedListener({ controllers ->
            registerAllControllers(controllers ?: emptyList())
        }, componentName)
    }

    private fun registerAllControllers(controllers: List<MediaController>) {
        controllerCallbacks.forEach { (controller, callback) ->
            controller.unregisterCallback(callback)
        }
        controllerCallbacks.clear()

        controllers.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    super.onMetadataChanged(metadata)
                    if (controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                        || currentController == controller
                    ) {
                        currentController = controller
                        updateUI(metadata)
                    }
                }

                override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
                    super.onPlaybackStateChanged(state)
                    if (state?.state == android.media.session.PlaybackState.STATE_PLAYING) {
                        currentController = controller
                        updateUI(controller.metadata)
                    } else if (currentController == controller) {
                        updateUI(controller.metadata)
                    }
                }
            }
            controller.registerCallback(callback)
            controllerCallbacks[controller] = callback

            if (currentController == null ||
                controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
            ) {
                currentController = controller
            }
        }

        updateUI(currentController?.metadata)
    }

    private fun updateUI(metadata: MediaMetadata?) {
        if (!::overlayView.isInitialized || !isOverlayAdded) return

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val state = currentController?.playbackState?.state

        val isInvalidState = state == android.media.session.PlaybackState.STATE_NONE ||
                             state == android.media.session.PlaybackState.STATE_STOPPED

        if (title.isNullOrBlank() || title == "未知歌曲" || isInvalidState) {
            overlayView.post { overlayView.visibility = View.GONE }
            return
        }

        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "未知歌手"
        val albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        overlayView.post {
            overlayView.visibility = View.VISIBLE

            overlayView.findViewById<TextView>(R.id.tvSongTitle).also {
                it.text = title
                it.isSelected = true
            }
            overlayView.findViewById<TextView>(R.id.tvArtist).also {
                it.text = artist
                it.isSelected = true
            }

            val ivAlbumCover = overlayView
                .findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.ivAlbumCover)
            if (albumArt != null) {
                ivAlbumCover.setImageBitmap(albumArt)
            } else {
                ivAlbumCover.setBackgroundColor(android.graphics.Color.DKGRAY)
                ivAlbumCover.setImageDrawable(null)
            }
        }
    }

    // ── 生命週期 ──────────────────────────────────────────────────────────────

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        hideOverlay()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) requestUnbind()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        controllerCallbacks.forEach { (controller, callback) ->
            controller.unregisterCallback(callback)
        }
        controllerCallbacks.clear()
        hideOverlay()
    }
}