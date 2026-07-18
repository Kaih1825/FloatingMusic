package net.skailine.floatingmusic
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 為了簡潔，這裡以程式碼建立按鈕，你也可以換成 setContentView(R.layout.activity_main)
        val btnStart = Button(this).apply {
            text = "啟動音樂懸浮視窗"
            setOnClickListener { checkPermissionsAndStart() }
        }
        setContentView(btnStart)
    }

    override fun onResume() {
        super.onResume()
        // 每次回到畫面時可選擇性自動檢查，此處交由按鈕觸發較不擾人
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
        finish() // 啟動後關閉 MainActivity，保持背景運行
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