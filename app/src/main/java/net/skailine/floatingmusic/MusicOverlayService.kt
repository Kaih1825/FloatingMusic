package net.skailine.floatingmusic
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.view.ContextThemeWrapper

class MusicOverlayService : NotificationListenerService() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var overlayParams: WindowManager.LayoutParams

    private var currentController: MediaController? = null
    private val controllerCallbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private var isOverlayAdded = false

    // 不再需要進度更新 Runnable


    // 我們不再使用單一的 mediaCallback，而是針對每個 Session 建立獨立的 callback


    override fun onCreate() {
        super.onCreate()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        initMediaListener()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        showOverlay()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun showOverlay() {
        if (isOverlayAdded) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val themeContext = ContextThemeWrapper(this, com.google.android.material.R.style.Theme_MaterialComponents_DayNight)
        overlayView = LayoutInflater.from(themeContext).inflate(R.layout.overlay, null)
        
        // 預設先隱藏，直到有真正的音樂播放才顯示
        overlayView.visibility = View.GONE

        val prefs = getSharedPreferences("OverlayPrefs", Context.MODE_PRIVATE)
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

        setupTouchOverlay()
    }

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
                val prefs = getSharedPreferences("OverlayPrefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putInt("x", overlayParams.x)
                    .putInt("y", overlayParams.y)
                    .apply()
            }
        }
    }

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
        // 清除舊的 callbacks
        controllerCallbacks.forEach { (controller, callback) ->
            controller.unregisterCallback(callback)
        }
        controllerCallbacks.clear()

        // 註冊所有活躍的 controllers
        controllers.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    super.onMetadataChanged(metadata)
                    // 如果這個 app 正在播放，或是它已經是我們當前追蹤的對象，就更新
                    if (controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING || currentController == controller) {
                        currentController = controller
                        updateUI(metadata)
                    }
                }

                override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
                    super.onPlaybackStateChanged(state)
                    // 如果有任何 app 開始播放，立刻把焦點切換給它
                    if (state?.state == android.media.session.PlaybackState.STATE_PLAYING) {
                        currentController = controller
                        updateUI(controller.metadata)
                    } else if (currentController == controller) {
                        // 如果是當前追蹤的 app 暫停或停止了，也要更新介面（可能會自動隱藏）
                        updateUI(controller.metadata)
                    }
                }
            }
            controller.registerCallback(callback)
            controllerCallbacks[controller] = callback

            // 預設先抓第一個，但如果有正在播放的，就優先選它
            if (currentController == null || controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) {
                currentController = controller
            }
        }
        
        updateUI(currentController?.metadata)
    }

    private fun updateUI(metadata: MediaMetadata?) {
        if (!::overlayView.isInitialized || !isOverlayAdded) return

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val state = currentController?.playbackState?.state

        // 如果沒有音樂名稱、名稱是未知，或者是完全停止/無狀態，就隱藏整個懸浮窗
        val isInvalidState = state == android.media.session.PlaybackState.STATE_NONE || 
                             state == android.media.session.PlaybackState.STATE_STOPPED
        
        if (title.isNullOrBlank() || title == "未知歌曲" || isInvalidState) {
            overlayView.post {
                overlayView.visibility = View.GONE
            }
            return
        }

        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "未知歌手"
        val albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        overlayView.post {
            // 有有效的音樂資訊，確保懸浮窗顯示出來
            overlayView.visibility = View.VISIBLE

            val tvTitle = overlayView.findViewById<TextView>(R.id.tvSongTitle)
            val tvArtist = overlayView.findViewById<TextView>(R.id.tvArtist)
            
            tvTitle.text = title
            tvArtist.text = artist
            
            // 必須設為 selected，跑馬燈才會開始動
            tvTitle.isSelected = true
            tvArtist.isSelected = true

            val ivAlbumCover = overlayView.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.ivAlbumCover)
            if (albumArt != null) {
                ivAlbumCover.setImageBitmap(albumArt)
            } else {
                ivAlbumCover.setBackgroundColor(android.graphics.Color.DKGRAY)
                ivAlbumCover.setImageDrawable(null)
            }
        }
    }


    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 當使用者從多工選單滑掉 App 時，主動把懸浮窗拆掉並停止服務
        if (::overlayView.isInitialized && isOverlayAdded) {
            windowManager.removeView(overlayView)
            isOverlayAdded = false
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            requestUnbind()
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerCallbacks.forEach { (controller, callback) ->
            controller.unregisterCallback(callback)
        }
        controllerCallbacks.clear()
        
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }
}