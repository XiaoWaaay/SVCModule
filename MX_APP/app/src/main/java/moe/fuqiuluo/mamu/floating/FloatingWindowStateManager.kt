package moe.fuqiuluo.mamu.floating

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.fuqiuluo.mamu.service.FloatingWindowService

/**
 * 悬浮窗状态管理器
 * 用于在 Service 和 Compose UI 之间同步悬浮窗的开启/Close状态
 */
object FloatingWindowStateManager {
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    fun setActive(active: Boolean) {
        _isActive.value = active
    }

    /**
     * 检查悬浮窗服务是否真正在运行，并同步状态
     * 应在 app 启动时调用，确保 UI 状态与服务实际状态一致
     */
    @Suppress("DEPRECATION")
    fun syncWithServiceState(context: Context) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val isRunning = activityManager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == FloatingWindowService::class.java.name }
        _isActive.value = isRunning
    }
}