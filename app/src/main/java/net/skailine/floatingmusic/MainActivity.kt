package net.skailine.floatingmusic

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit

class MainActivity : ComponentActivity() {

    companion object {
        const val PREFS_NAME = "OverlayPrefs"
        const val KEY_CORNER_TOP_LEFT = "corner_top_left"
        const val KEY_CORNER_TOP_RIGHT = "corner_top_right"
        const val KEY_CORNER_BOTTOM_LEFT = "corner_bottom_left"
        const val KEY_CORNER_BOTTOM_RIGHT = "corner_bottom_right"
        const val KEY_TEXT_ALIGN_LEFT = "text_align_left"
        const val KEY_AUTO_SNAP_CORNER = "auto_snap_corner"
        const val KEY_OVERLAY_SIZE = "overlay_size"
        const val KEY_OVERLAY_SHOW = "overlay_should_show"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 啟用 Edge-to-edge
        enableEdgeToEdge()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    AdaptiveSettingsScreen(
                        prefs = prefs,
                        onStartClick = { checkPermissionsAndStart(prefs) },
                        onStopClick = { stopOverlay(prefs) }
                    )
                }
            }
        }
    }

    private fun stopOverlay(prefs: SharedPreferences) {
        // 使用 SharedPreferences 觸發隱藏
        prefs.edit().putBoolean(KEY_OVERLAY_SHOW, false).apply()
        
        val intent = Intent(this, MusicOverlayService::class.java).apply {
            action = MusicOverlayService.ACTION_HIDE_OVERLAY
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        Toast.makeText(this, "懸浮視窗已隱藏", Toast.LENGTH_SHORT).show()
    }

    private fun checkPermissionsAndStart(prefs: SharedPreferences) {
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
            Toast.makeText(this, "請允許「通知存取權」，才能讀取正在播放的音樂", Toast.LENGTH_SHORT)
                .show()
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.service.notification.NotificationListenerService.requestRebind(
                ComponentName(this, MusicOverlayService::class.java)
            )
        }

        // 記錄要顯示
        prefs.edit().putBoolean(KEY_OVERLAY_SHOW, true).apply()

        val intent = Intent(this, MusicOverlayService::class.java)
        try {
            startService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        Toast.makeText(this, "懸浮視窗已啟動！", Toast.LENGTH_SHORT).show()
        
        // 啟動後自動關閉主程式
        finish()
    }



    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun hasNotificationAccess(): Boolean {
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(this)
        return enabledListeners.contains(packageName)
    }
}
@Suppress("UnusedBoxWithConstraintsScope")
@Composable
fun AdaptiveSettingsScreen(
    prefs: SharedPreferences,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 利用實際可用寬度區分單欄與雙欄，而非裝置型號
        val isWide = maxWidth >= 600.dp

        Scaffold(
            bottomBar = {
                Surface(
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding() // 確保不被 Navigation Bar 擋住
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = onStopClick,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Text("停止")
                        }
                        Button(
                            onClick = onStartClick,
                            modifier = Modifier
                                .weight(2f)
                                .height(56.dp)
                        ) {
                            Text("啟動視窗")
                        }
                    }
                }
            },
            // 系統會自動處理 Insets 並透過 innerPadding 傳入
            contentWindowInsets = WindowInsets.safeDrawing
        ) { innerPadding ->
            val scrollState = rememberScrollState()

            // State Hoisting
            var topLeft by remember { mutableStateOf(prefs.getBoolean(MainActivity.KEY_CORNER_TOP_LEFT, false)) }
            var topRight by remember { mutableStateOf(prefs.getBoolean(MainActivity.KEY_CORNER_TOP_RIGHT, true)) }
            var bottomLeft by remember { mutableStateOf(prefs.getBoolean(MainActivity.KEY_CORNER_BOTTOM_LEFT, false)) }
            var bottomRight by remember { mutableStateOf(prefs.getBoolean(MainActivity.KEY_CORNER_BOTTOM_RIGHT, true)) }
            var alignLeft by remember { mutableStateOf(prefs.getBoolean(MainActivity.KEY_TEXT_ALIGN_LEFT, false)) }
            var autoSnapCorner by remember { mutableStateOf(prefs.getBoolean(MainActivity.KEY_AUTO_SNAP_CORNER, true)) }
            var overlaySize by remember { mutableFloatStateOf(prefs.getInt(MainActivity.KEY_OVERLAY_SIZE, 50).toFloat()) }

            DisposableEffect(prefs) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
                    when (key) {
                        MainActivity.KEY_CORNER_TOP_LEFT -> topLeft = sharedPreferences.getBoolean(key, false)
                        MainActivity.KEY_CORNER_TOP_RIGHT -> topRight = sharedPreferences.getBoolean(key, true)
                        MainActivity.KEY_CORNER_BOTTOM_LEFT -> bottomLeft = sharedPreferences.getBoolean(key, false)
                        MainActivity.KEY_CORNER_BOTTOM_RIGHT -> bottomRight = sharedPreferences.getBoolean(key, true)
                        MainActivity.KEY_TEXT_ALIGN_LEFT -> alignLeft = sharedPreferences.getBoolean(key, false)
                        MainActivity.KEY_AUTO_SNAP_CORNER -> autoSnapCorner = sharedPreferences.getBoolean(key, true)
                        MainActivity.KEY_OVERLAY_SIZE -> overlaySize = sharedPreferences.getInt(key, 50).toFloat()
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose {
                    prefs.unregisterOnSharedPreferenceChangeListener(listener)
                }
            }

            val updatePref: (String, Boolean) -> Unit = { key, value ->
                prefs.edit { putBoolean(key, value) }
            }
            
            val setSide: (Boolean) -> Unit = { isLeft ->
                // 左邊: 文字靠右(alignLeft=false), 左上左下關閉(false), 右上右下打開(true)
                // 右邊: 文字靠左(alignLeft=true), 左上左下打開(true), 右上右下關閉(false)
                topLeft = !isLeft; updatePref(MainActivity.KEY_CORNER_TOP_LEFT, !isLeft)
                bottomLeft = !isLeft; updatePref(MainActivity.KEY_CORNER_BOTTOM_LEFT, !isLeft)
                topRight = isLeft; updatePref(MainActivity.KEY_CORNER_TOP_RIGHT, isLeft)
                bottomRight = isLeft; updatePref(MainActivity.KEY_CORNER_BOTTOM_RIGHT, isLeft)
                alignLeft = !isLeft; updatePref(MainActivity.KEY_TEXT_ALIGN_LEFT, !isLeft)
            }

            // Modifier 處理 Padding 與捲動，讓最後一個元素不受 BottomBar 和鍵盤遮擋
            val contentModifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .verticalScroll(scrollState)
                .padding(24.dp)

            if (isWide) {
                // 平板 / 摺疊機 / 橫向 (雙欄設計)
                Column(modifier = contentModifier) {
                    HeaderSection()
                    Spacer(modifier = Modifier.height(16.dp))
                    MasterSwitchCard(onSelectSide = setSide)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            CornersCard(topLeft, topRight, bottomLeft, bottomRight) { key, value ->
                                when(key) {
                                    MainActivity.KEY_CORNER_TOP_LEFT -> topLeft = value
                                    MainActivity.KEY_CORNER_TOP_RIGHT -> topRight = value
                                    MainActivity.KEY_CORNER_BOTTOM_LEFT -> bottomLeft = value
                                    MainActivity.KEY_CORNER_BOTTOM_RIGHT -> bottomRight = value
                                }
                                updatePref(key, value)
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            OtherCard(alignLeft, overlaySize, autoSnapCorner, onAlignChange = { 
                                alignLeft = it; updatePref(MainActivity.KEY_TEXT_ALIGN_LEFT, it)
                            }, onSizeChange = {
                                overlaySize = it
                                prefs.edit { putInt(MainActivity.KEY_OVERLAY_SIZE, it.toInt()) }
                            }, onAutoSnapChange = {
                                autoSnapCorner = it; updatePref(MainActivity.KEY_AUTO_SNAP_CORNER, it)
                            })
                            Spacer(modifier = Modifier.height(24.dp))
                            HintSection()
                        }
                    }
                }
            } else {
                // 手機直向 (單欄設計)
                Column(modifier = contentModifier) {
                    HeaderSection()
                    Spacer(modifier = Modifier.height(16.dp))
                    MasterSwitchCard(onSelectSide = setSide)
                    Spacer(modifier = Modifier.height(16.dp))
                    CornersCard(topLeft, topRight, bottomLeft, bottomRight) { key, value ->
                        when(key) {
                            MainActivity.KEY_CORNER_TOP_LEFT -> topLeft = value
                            MainActivity.KEY_CORNER_TOP_RIGHT -> topRight = value
                            MainActivity.KEY_CORNER_BOTTOM_LEFT -> bottomLeft = value
                            MainActivity.KEY_CORNER_BOTTOM_RIGHT -> bottomRight = value
                        }
                        updatePref(key, value)
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    OtherCard(alignLeft, overlaySize, autoSnapCorner, onAlignChange = { 
                        alignLeft = it; updatePref(MainActivity.KEY_TEXT_ALIGN_LEFT, it)
                    }, onSizeChange = {
                        overlaySize = it
                        prefs.edit { putInt(MainActivity.KEY_OVERLAY_SIZE, it.toInt()) }
                    }, onAutoSnapChange = {
                        autoSnapCorner = it; updatePref(MainActivity.KEY_AUTO_SNAP_CORNER, it)
                    })
                    Spacer(modifier = Modifier.height(24.dp))
                    HintSection()
                }
            }
        }
    }
}

@Composable
fun HeaderSection() {
    Text(
        text = "🎵 音樂懸浮視窗",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold
    )
    Text(
        text = "設定與控制",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
    )
}

@Composable
fun MasterSwitchCard(onSelectSide: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "一鍵切換視窗位置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = { onSelectSide(true) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("貼齊螢幕左側")
                }
                Button(
                    onClick = { onSelectSide(false) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("貼齊螢幕右側")
                }
            }
        }
    }
}

@Composable
fun CornersCard(
    topLeft: Boolean,
    topRight: Boolean,
    bottomLeft: Boolean,
    bottomRight: Boolean,
    onUpdate: (String, Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "懸浮視窗圓角設定",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            SwitchRow("↖ 左上角圓角", topLeft) {
                onUpdate(MainActivity.KEY_CORNER_TOP_LEFT, it)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SwitchRow("↙ 左下角圓角", bottomLeft) {
                onUpdate(MainActivity.KEY_CORNER_BOTTOM_LEFT, it)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SwitchRow("↗ 右上角圓角", topRight) {
                onUpdate(MainActivity.KEY_CORNER_TOP_RIGHT, it)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SwitchRow("↘ 右下角圓角", bottomRight) {
                onUpdate(MainActivity.KEY_CORNER_BOTTOM_RIGHT, it)
            }
        }
    }
}

@Composable
fun OtherCard(
    alignLeft: Boolean,
    overlaySize: Float,
    autoSnapCorner: Boolean,
    onAlignChange: (Boolean) -> Unit,
    onSizeChange: (Float) -> Unit,
    onAutoSnapChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "其他設定",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            SwitchRow("邊緣自動調整圓角", autoSnapCorner) {
                onAutoSnapChange(it)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            SwitchRow("文字靠左顯示", alignLeft) {
                onAlignChange(it)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            val scale = 0.5f + (overlaySize / 100f)
            Text(
                text = String.format("視窗大小 (%.2fx)", scale),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Slider(
                value = overlaySize,
                onValueChange = onSizeChange,
                valueRange = 0f..100f,
                steps = 19
            )
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun HintSection() {
    Text(
        text = "💡 圓角設定套用至懸浮視窗外框，預設右側兩角為圓角。\n在任何地方滑動滑桿即可即時預覽大小變更。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}