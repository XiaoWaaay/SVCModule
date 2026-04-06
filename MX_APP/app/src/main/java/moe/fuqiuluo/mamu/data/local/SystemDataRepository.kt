package moe.fuqiuluo.mamu.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.fuqiuluo.mamu.data.model.SeLinuxMode
import moe.fuqiuluo.mamu.data.model.SeLinuxStatus
import moe.fuqiuluo.mamu.data.model.SystemInfo
import moe.fuqiuluo.mamu.utils.RootConfigManager
import moe.fuqiuluo.mamu.utils.RootShellExecutor
import moe.fuqiuluo.mamu.utils.ShellResult

/**
 * 系统信息数据源
 * 负责查询设备系统相关信息
 */
class SystemDataRepository {

    /**
     * 获取系统信息
     */
    fun getSystemInfo(): SystemInfo {
        return SystemInfo()
    }

    /**
     * 获取SELinux状态
     */
    suspend fun getSeLinuxStatus(): SeLinuxStatus = withContext(Dispatchers.IO) {
        val result = RootShellExecutor.exec(
            suCmd = RootConfigManager.getCustomRootCommand(),
            command = "getenforce"
        )

        when (result) {
            is ShellResult.Success -> {
                val modeString = result.output.trim()
                val mode = when (modeString.uppercase()) {
                    "ENFORCING" -> SeLinuxMode.ENFORCING
                    "PERMISSIVE" -> SeLinuxMode.PERMISSIVE
                    "DISABLED" -> SeLinuxMode.DISABLED
                    else -> SeLinuxMode.UNKNOWN
                }
                SeLinuxStatus(mode, modeString)
            }

            else -> SeLinuxStatus(SeLinuxMode.UNKNOWN, "Unknown")
        }
    }

    /**
     * 检查是否有RootPermissions
     */
    suspend fun hasRootAccess(): Boolean = withContext(Dispatchers.IO) {
        val result = RootShellExecutor.exec(suCmd = RootConfigManager.getCustomRootCommand(), "echo test")
        result is ShellResult.Success
    }
}
