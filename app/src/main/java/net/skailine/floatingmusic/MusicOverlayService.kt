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
import android.widget.Toast

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
    
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgress()
            if (currentController?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) {
                uiHandler.postDelayed(this, 1000)
            }
        }
    }

    // 監聽 SharedPreferences 變化，即時套用圓角（同 process 直接觸發，不需廣播）
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->
        if (key == MainActivity.KEY_OVERLAY_SHOW) {
            val shouldShow = sharedPrefs.getBoolean(key, false)
            if (shouldShow) {
                uiHandler.post { showOverlay() }
            } else {
                uiHandler.post { hideOverlay() }
            }
        }
        
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

    private fun createOverlayView() {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = savedX
            y = savedY
        }
        
        setupTouchOverlay()
    }

    private fun showOverlay() {
        if (isOverlayAdded) return
        
        // 為了避免 WindowManager 重複 addView 造成的崩潰或失效，每次顯示都重新建立 View
        if (::overlayView.isInitialized) {
            try {
                windowManager.removeView(overlayView)
            } catch (e: Exception) {
                // 忽略
            }
        }
        
        createOverlayView()
        
        try {
            windowManager.addView(overlayView, overlayParams)
            isOverlayAdded = true
            
            // 每次顯示時，強制套用一次設定
            applyCornerSettings()
            applyTextAlignmentSettings()
            applySizeSettings()
            
        } catch (e: Exception) {
            e.printStackTrace()
        }

        updateUI(currentController?.metadata)
    }

    private fun hideOverlay() {
        if (::overlayView.isInitialized && isOverlayAdded) {
            try {
                windowManager.removeView(overlayView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
        val layoutTitleWrapper = overlayView.findViewById<android.view.View>(R.id.layoutTitleWrapper)
        val tvArtist = overlayView.findViewById<TextView>(R.id.tvArtist)
        val albumScrim = overlayView.findViewById<View>(R.id.albumScrim)

        if (layoutText == null || layoutTitleWrapper == null || tvArtist == null || albumScrim == null) return

        val params = layoutText.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        
        if (isAlignLeft) {
            // 文字在左，留出右邊 110dp 給封面，左邊 20dp padding
            params.marginStart = (20 * resources.displayMetrics.density).toInt()
            layoutText.setPadding(0, 0, (110 * resources.displayMetrics.density).toInt(), 0)
            
            // 讓整個 layoutText 內的元件靠左對齊
            layoutText.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            
            // 單獨設定文字元件本身的 gravity 確保多行/跑馬燈方向正確
            val paramsTitle = layoutTitleWrapper.layoutParams as android.widget.LinearLayout.LayoutParams
            val paramsArtist = tvArtist.layoutParams as android.widget.LinearLayout.LayoutParams
            paramsTitle.gravity = android.view.Gravity.START
            paramsArtist.gravity = android.view.Gravity.START
            
            // 漸層遮罩反轉，讓深色在左邊
            albumScrim.scaleX = -1f
        } else {
            // 文字在右，留出左邊 110dp 給封面，右邊 20dp padding
            params.marginStart = 0
            layoutText.setPadding((110 * resources.displayMetrics.density).toInt(), 0, (20 * resources.displayMetrics.density).toInt(), 0)
            
            layoutText.gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            
            val paramsTitle = layoutTitleWrapper.layoutParams as android.widget.LinearLayout.LayoutParams
            val paramsArtist = tvArtist.layoutParams as android.widget.LinearLayout.LayoutParams
            paramsTitle.gravity = android.view.Gravity.END
            paramsArtist.gravity = android.view.Gravity.END

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

            override fun onDoubleTap() {
                toggleFavorite()
            }

            override fun onDrag(dx: Float, dy: Float) {
                overlayParams.x += dx.toInt()
                overlayParams.y += dy.toInt()
                windowManager.updateViewLayout(overlayView, overlayParams)
                
                // 準確計算「貼齊邊緣」的位置
                // Gravity.CENTER_HORIZONTAL 讓 x=0 代表置中。
                // 當懸浮窗貼齊左側緣時，x 會是 -(screenWidth/2 - viewWidth/2)
                val screenWidth = resources.displayMetrics.widthPixels
                val actualWidth = if (overlayParams.width > 0) overlayParams.width else overlayView.width
                val viewHalfWidth = actualWidth / 2f
                val maxSafeX = (screenWidth / 2f) - viewHalfWidth
                
                // 改為：必須「完全碰到」邊緣（或稍微超過）才當作邊緣吸附
                // 這樣就不會發生「還沒碰到就提早判斷」的問題
                val edgeThreshold = if (maxSafeX > 0) maxSafeX else 0f
                
                val topLeft = prefs.getBoolean(MainActivity.KEY_CORNER_TOP_LEFT, false)
                val topRight = prefs.getBoolean(MainActivity.KEY_CORNER_TOP_RIGHT, true)
                val isSnappedLeft = !topLeft && topRight
                val isSnappedRight = topLeft && !topRight
                val isAllRounded = topLeft && topRight
                
                // 如果被推到極左側邊緣
                if (overlayParams.x < -edgeThreshold) {
                    if (!isSnappedLeft) {
                        prefs.edit()
                            .putBoolean(MainActivity.KEY_TEXT_ALIGN_LEFT, false)
                            .putBoolean(MainActivity.KEY_CORNER_TOP_LEFT, false)
                            .putBoolean(MainActivity.KEY_CORNER_BOTTOM_LEFT, false)
                            .putBoolean(MainActivity.KEY_CORNER_TOP_RIGHT, true)
                            .putBoolean(MainActivity.KEY_CORNER_BOTTOM_RIGHT, true)
                            .apply()
                    }
                } 
                // 如果被推到極右側邊緣
                else if (overlayParams.x > edgeThreshold) {
                    if (!isSnappedRight) {
                        prefs.edit()
                            .putBoolean(MainActivity.KEY_TEXT_ALIGN_LEFT, true)
                            .putBoolean(MainActivity.KEY_CORNER_TOP_LEFT, true)
                            .putBoolean(MainActivity.KEY_CORNER_BOTTOM_LEFT, true)
                            .putBoolean(MainActivity.KEY_CORNER_TOP_RIGHT, false)
                            .putBoolean(MainActivity.KEY_CORNER_BOTTOM_RIGHT, false)
                            .apply()
                    }
                }
                // 在中間區域
                else {
                    if (!isAllRounded) {
                        prefs.edit()
                            .putBoolean(MainActivity.KEY_CORNER_TOP_LEFT, true)
                            .putBoolean(MainActivity.KEY_CORNER_BOTTOM_LEFT, true)
                            .putBoolean(MainActivity.KEY_CORNER_TOP_RIGHT, true)
                            .putBoolean(MainActivity.KEY_CORNER_BOTTOM_RIGHT, true)
                            .apply()
                    }
                }
            }

            override fun onDragEnd() {
                prefs.edit()
                    .putInt("x", overlayParams.x)
                    .putInt("y", overlayParams.y)
                    .apply()
            }
        }
    }

    private fun toggleFavorite() {
        val controller = currentController ?: return
        
        // 尋找可能的 CustomAction (許多音樂播放器透過 CustomAction 提供「加入最愛」功能)
        val customActions = controller.playbackState?.customActions
        if (customActions != null && customActions.isNotEmpty()) {
            val likeAction = customActions.find { 
                val actionName = it.action.lowercase()
                val name = it.name?.toString()?.lowercase() ?: ""
                actionName.contains("favorite") || actionName.contains("heart") || 
                actionName.contains("like") || actionName.contains("thumb") ||
                actionName.contains("collection") || actionName.contains("add_to") || actionName.contains("remove_from") || actionName.contains("check_fill") ||
                name.contains("favorite") || name.contains("heart") || 
                name.contains("like") || name.contains("thumb") || name.contains("collection") || name.contains("add_to") || name.contains("remove_from") || name.contains("check_fill")
            }
            
            if (likeAction != null) {
                controller.transportControls.sendCustomAction(likeAction, null)
                android.util.Log.d("FloatingMusic", "已切換最愛, Action: ${likeAction.action}")
                return
            } else {
                val allActions = customActions.joinToString { it.action.substringAfterLast('.') }
                android.util.Log.d("FloatingMusic", "找不到最愛按鈕, 可用 Action: $allActions")
            }
        } else {
            android.util.Log.d("FloatingMusic", "無 CustomActions, 播放器未公開最愛功能")
        }
        
        // 若找不到合適的 CustomAction，嘗試使用 Android 標準的 Rating API
        controller.transportControls.setRating(android.media.Rating.newHeartRating(true))
        android.util.Log.d("FloatingMusic", "已傳送標準最愛指令")
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
                
                // Extract vibrant color using Palette
                androidx.palette.graphics.Palette.from(albumArt).generate { palette ->
                    // 優先使用 LightVibrant 確保在暗色遮罩上夠亮夠顯眼
                    val color = palette?.lightVibrantSwatch?.rgb
                        ?: palette?.vibrantSwatch?.rgb
                        ?: palette?.lightMutedSwatch?.rgb
                        ?: android.graphics.Color.WHITE
                        
                    val pb = overlayView.findViewById<android.widget.ProgressBar>(R.id.pbMusicProgress)
                    pb?.progressTintList = android.content.res.ColorStateList.valueOf(color)
                    // 為了質感，完全移除底色軌道，只保留推進中的細線
                    pb?.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                }
            } else {
                ivAlbumCover.setBackgroundColor(android.graphics.Color.DKGRAY)
                ivAlbumCover.setImageDrawable(null)
                
                val pb = overlayView.findViewById<android.widget.ProgressBar>(R.id.pbMusicProgress)
                pb?.progressTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                pb?.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            }
            
            uiHandler.removeCallbacks(progressUpdater)
            if (state == android.media.session.PlaybackState.STATE_PLAYING) {
                uiHandler.post(progressUpdater)
            } else {
                updateProgress()
            }
        }
        
        updateFavoriteIcon()
    }

    private fun updateFavoriteIcon() {
        if (!::overlayView.isInitialized || !isOverlayAdded) return
        val controller = currentController ?: return
        val customActions = controller.playbackState?.customActions ?: emptyList()
        val metadata = controller.metadata
        
        var isFavorited = false
        var hasFavoriteFeature = false
        
        customActions.forEach { action ->
            val actionName = action.action.lowercase()
            val name = action.name?.toString()?.lowercase() ?: ""
            if (actionName.contains("check_fill") || actionName.contains("remove_from") || name.contains("remove") || name.contains("dislike") || actionName.contains("unthumb")) {
                isFavorited = true
                hasFavoriteFeature = true
            } else if (actionName.contains("favorite") || actionName.contains("heart") || actionName.contains("like") || actionName.contains("thumb") || actionName.contains("collection") || actionName.contains("add_to") ||
                       name.contains("favorite") || name.contains("heart") || name.contains("like") || name.contains("thumb") || name.contains("collection") || name.contains("add_to")) {
                hasFavoriteFeature = true
            }
        }
        
        // YouTube Music 常常把按讚狀態放在 Metadata 的 Rating 裡面
        if (metadata != null) {
            val rating = metadata.getRating(android.media.MediaMetadata.METADATA_KEY_USER_RATING)
            if (rating != null) {
                hasFavoriteFeature = true
                if (rating.isRated) {
                    if (rating.hasHeart() || rating.isThumbUp) {
                        isFavorited = true
                    }
                }
            }
        }
        
        overlayView.post {
            val ivFavorite = overlayView.findViewById<android.widget.ImageView>(R.id.ivFavoriteIcon)
            if (ivFavorite != null) {
                if (hasFavoriteFeature) {
                    ivFavorite.visibility = View.VISIBLE
                    if (isFavorited) {
                        ivFavorite.setImageResource(R.drawable.ic_check_circle)
                    } else {
                        ivFavorite.setImageResource(R.drawable.ic_add_circle)
                    }
                } else {
                    ivFavorite.visibility = View.GONE
                }
            }
        }
    }

    private fun updateProgress() {
        if (!::overlayView.isInitialized || !isOverlayAdded) return
        val metadata = currentController?.metadata ?: return
        val state = currentController?.playbackState ?: return
        
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (duration <= 0) {
            overlayView.findViewById<android.widget.ProgressBar>(R.id.pbMusicProgress)?.progress = 0
            return
        }
        
        var position = state.position
        if (state.state == android.media.session.PlaybackState.STATE_PLAYING) {
            val timeDelta = android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            position += (timeDelta * state.playbackSpeed).toLong()
        }
        
        val progress = (position.toFloat() / duration.toFloat() * 1000).toInt()
        overlayView.findViewById<android.widget.ProgressBar>(R.id.pbMusicProgress)?.progress = progress.coerceIn(0, 1000)
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
        uiHandler.removeCallbacks(progressUpdater)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        controllerCallbacks.forEach { (controller, callback) ->
            controller.unregisterCallback(callback)
        }
        controllerCallbacks.clear()
        hideOverlay()
    }
}