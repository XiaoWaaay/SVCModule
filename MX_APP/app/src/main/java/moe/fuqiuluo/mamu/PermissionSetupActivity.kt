package moe.fuqiuluo.mamu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.fuqiuluo.mamu.data.local.RootFileSystem
import moe.fuqiuluo.mamu.ui.screen.PermissionSetupScreen
import moe.fuqiuluo.mamu.ui.theme.MXTheme

/**
 * Permissions设置启动页面
 * 用于在应用启动时检查和授予必要的Permissions
 */
class PermissionSetupActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MXTheme {
                val windowSizeClass = calculateWindowSizeClass(this)
                PermissionSetupScreen(windowSizeClass = windowSizeClass)
            }
        }
    }
}