package moe.fuqiuluo.mamu

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.lifecycle.lifecycleScope
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.fuqiuluo.mamu.data.local.RootFileSystem
import moe.fuqiuluo.mamu.data.settings.autoStartFloatingWindow
import moe.fuqiuluo.mamu.floating.FloatingWindowStateManager
import moe.fuqiuluo.mamu.service.FloatingWindowService
import moe.fuqiuluo.mamu.ui.screen.MainScreen
import moe.fuqiuluo.mamu.ui.theme.MXTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 同步悬浮窗状态，确保 UI 显示与服务实际状态一致
        FloatingWindowStateManager.syncWithServiceState(this)

        setContent {
            MXTheme {
                // 步骤3: 在Activity中计算WindowSizeClass并传递给Composable
                val windowSizeClass = calculateWindowSizeClass(this)
                MainScreen(windowSizeClass = windowSizeClass)
            }
        }

        lifecycleScope.launch(Dispatchers.Main) {
            RootFileSystem.connect(applicationContext)
        }

        lifecycleScope.launch(Dispatchers.Main) {
            // 检查是否需要自动启动悬浮窗
            checkAutoStartFloatingWindow()
        }
    }

    private fun checkAutoStartFloatingWindow() {
        val mmkv = MMKV.defaultMMKV()
        if (mmkv.autoStartFloatingWindow) {
            val intent = Intent(this, FloatingWindowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }
}