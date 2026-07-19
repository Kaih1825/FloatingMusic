package net.skailine.floatingmusic

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "OverlayPrefs"
        const val KEY_CORNER_TOP_LEFT     = "corner_top_left"
        const val KEY_CORNER_TOP_RIGHT    = "corner_top_right"
        const val KEY_CORNER_BOTTOM_LEFT  = "corner_bottom_left"
        const val KEY_CORNER_BOTTOM_RIGHT = "corner_bottom_right"
        const val KEY_TEXT_ALIGN_LEFT     = "text_align_left"
        const val KEY_OVERLAY_SIZE        = "overlay_size"
    }

    private lateinit var switchTopLeft: Switch
    private lateinit var switchTopRight: Switch
    private lateinit var switchBottomLeft: Switch
    private lateinit var switchBottomRight: Switch
    private lateinit var switchTextAlignLeft: Switch
    private lateinit var seekOverlaySize: android.widget.SeekBar
    private lateinit var tvOverlaySizeLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 取得 Switch 參照
        switchTopLeft     = findViewById(R.id.switchCornerTopLeft)
        switchTopRight    = findViewById(R.id.switchCornerTopRight)
        switchBottomLeft  = findViewById(R.id.switchCornerBottomLeft)
        switchBottomRight = findViewById(R.id.switchCornerBottomRight)
        switchTextAlignLeft = findViewById(R.id.switchTextAlignLeft)
        seekOverlaySize = findViewById(R.id.seekOverlaySize)
        tvOverlaySizeLabel = findViewById(R.id.tvOverlaySizeLabel)

        // 載入已儲存的設定
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        switchTopLeft.isChecked     = prefs.getBoolean(KEY_CORNER_TOP_LEFT,     false)
        switchTopRight.isChecked    = prefs.getBoolean(KEY_CORNER_TOP_RIGHT,    true)
        switchBottomLeft.isChecked  = prefs.getBoolean(KEY_CORNER_BOTTOM_LEFT,  false)
        switchBottomRight.isChecked = prefs.getBoolean(KEY_CORNER_BOTTOM_RIGHT, true)
        switchTextAlignLeft.isChecked = prefs.getBoolean(KEY_TEXT_ALIGN_LEFT,   false)
        
        val savedProgress = prefs.getInt(KEY_OVERLAY_SIZE, 50)
        seekOverlaySize.progress = savedProgress
        updateSizeLabel(savedProgress)

        // 每個 Switch 切換時立即儲存。
        // Service 透過 OnSharedPreferenceChangeListener 監聽，會自動即時套用，不需廣播。
        val saveCorners: () -> Unit = {
            prefs.edit()
                .putBoolean(KEY_CORNER_TOP_LEFT,     switchTopLeft.isChecked)
                .putBoolean(KEY_CORNER_TOP_RIGHT,    switchTopRight.isChecked)
                .putBoolean(KEY_CORNER_BOTTOM_LEFT,  switchBottomLeft.isChecked)
                .putBoolean(KEY_CORNER_BOTTOM_RIGHT, switchBottomRight.isChecked)
                .putBoolean(KEY_TEXT_ALIGN_LEFT,     switchTextAlignLeft.isChecked)
                .putInt(KEY_OVERLAY_SIZE,            seekOverlaySize.progress)
                .apply()
        }

        switchTopLeft.setOnCheckedChangeListener     { _, _ -> saveCorners() }
        switchTopRight.setOnCheckedChangeListener    { _, _ -> saveCorners() }
        switchBottomLeft.setOnCheckedChangeListener  { _, _ -> saveCorners() }
        switchBottomRight.setOnCheckedChangeListener { _, _ -> saveCorners() }
        switchTextAlignLeft.setOnCheckedChangeListener { _, _ -> saveCorners() }

        seekOverlaySize.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                // 每 5 單位為一刻度 (0.05倍)
                val snappedProgress = Math.round(progress / 5f) * 5
                updateSizeLabel(snappedProgress)
                if (fromUser) {
                    // 即時儲存吸附後的數值，讓 Service 立即反應
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putInt(KEY_OVERLAY_SIZE, snappedProgress)
                        .apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                // 放開手指時，讓滑桿的物理位置也吸附過去
                seekBar?.let {
                    val snappedProgress = Math.round(it.progress / 5f) * 5
                    it.progress = snappedProgress
                }
            }
        })

        // 啟動按鈕
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            checkPermissionsAndStart()
        }

        // 停止按鈕
        // MusicOverlayService 繼承自 NotificationListenerService，由系統管理綁定，
        // stopService() 對它無效。改用自訂 action 讓 Service 自己移除 overlay。
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            val intent = Intent(this, MusicOverlayService::class.java).apply {
                action = MusicOverlayService.ACTION_HIDE_OVERLAY
            }
            startService(intent)
            Toast.makeText(this, "懸浮視窗已隱藏", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSizeLabel(progress: Int) {
        val scale = 0.5f + (progress / 100f)
        tvOverlaySizeLabel.text = String.format("視窗大小 (%.2fx)", scale)
    }

    private fun checkPermissionsAndStart() {
        if (!hasOverlayPermission()) {
            Toast.makeText(this, "請開啟「顯示在其他應用程式上層」權限", Toast.LENGTH_SHORT).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        if (!hasNotificationAccess()) {
            Toast.makeText(this, "請允許「通知存取權」，才能讀取正在播放的音樂", Toast.LENGTH_SHORT).show()
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
            return
        }

        // 權限皆具備，啟動 Service 並請求系統重新綁定（因為之前滑掉時主動解綁了）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.service.notification.NotificationListenerService.requestRebind(
                ComponentName(this, MusicOverlayService::class.java)
            )
        }

        val intent = Intent(this, MusicOverlayService::class.java)
        startService(intent)
        Toast.makeText(this, "懸浮視窗已啟動！", Toast.LENGTH_SHORT).show()
    }

    // 檢查懸浮視窗權限
    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    // 檢查通知存取（讀取音樂）權限
    private fun hasNotificationAccess(): Boolean {
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(this)
        return enabledListeners.contains(packageName)
    }
}