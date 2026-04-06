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
import moe.fuqiuluo.mamu.ui.screen.DriverInstallScreen
import moe.fuqiuluo.mamu.ui.theme.MXTheme

/**
 * 驱动安装Activity
 */
class DriverInstallActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MXTheme {
                val windowSizeClass = calculateWindowSizeClass(this)
                DriverInstallScreen(
                    windowSizeClass = windowSizeClass,
                    onNavigateBack = { finish() }
                )
            }
        }

        lifecycleScope.launch(Dispatchers.Main) {
            RootFileSystem.connect(this@DriverInstallActivity)
        }
    }
}