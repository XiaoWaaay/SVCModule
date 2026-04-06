package moe.fuqiuluo.mamu.utils

import android.content.Context
import android.os.Build
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

/**
 * 究极免杀保护管理器
 * 通过一系列系统级命令防止应用被系统杀死
 * 默认Close，需要用户主动启用
 */
object AntiKillProtectionManager {
    private const val TAG = "AntiKillProtection"
    private const val MMKV_ID = "anti_kill_config"
    private const val KEY_ANTI_KILL_ENABLED = "anti_kill_protection_enabled"

    private val mmkv by lazy {
        PreviewSafeMMKV.mmkvWithID(MMKV_ID)
    }

    /**
     * 保护措施配置
     * 每个保护措施包含命令模板和适用的最低 Android 版本
     */
    private data class ProtectionCommand(
        val name: String,
        val commandTemplate: (String) -> String,
        val minSdkVersion: Int = Build.VERSION_CODES.N, // 默认 Android 7.0+
        val description: String
    )

    /**
     * 所有可用的保护命令
     */
    private val protectionCommands = listOf(
        ProtectionCommand(
            name = "Battery Optimization Whitelist",
            commandTemplate = { pkg -> "dumpsys deviceidle whitelist +$pkg" },
            minSdkVersion = Build.VERSION_CODES.M, // Android 6.0+
            description = "Disable battery optimization (most important)"
        ),
        ProtectionCommand(
            name = "App Standby",
            commandTemplate = { pkg -> "am set-inactive $pkg false" },
            minSdkVersion = Build.VERSION_CODES.M, // Android 6.0+
            description = "Disable app standby mode"
        ),
        ProtectionCommand(
            name = "Background Restriction",
            commandTemplate = { pkg -> "cmd appops set $pkg RUN_IN_BACKGROUND allow" },
            minSdkVersion = Build.VERSION_CODES.N, // Android 7.0+
            description = "Disable background restrictions"
        ),
        ProtectionCommand(
            name = "Power Saving Restriction",
            commandTemplate = { pkg -> "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow" },
            minSdkVersion = Build.VERSION_CODES.P, // Android 9.0+
            description = "Disable power-saving restrictions (Android 9+)"
        ),
        ProtectionCommand(
            name = "Auto Revoke Permissions",
            commandTemplate = { pkg -> "cmd appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore" },
            minSdkVersion = Build.VERSION_CODES.S, // Android 12+
            description = "Mark the app as unrestricted (Android 12+)"
        )
    )

    /**
     * 获取当前设备支持的保护命令列表
     */
    private fun getSupportedCommands(): List<ProtectionCommand> {
        return protectionCommands.filter { Build.VERSION.SDK_INT >= it.minSdkVersion }
    }

    /**
     * 检查是否启用了究极免杀保护
     */
    fun isEnabled(): Boolean {
        return mmkv.decodeBool(KEY_ANTI_KILL_ENABLED, false)
    }

    /**
     * 设置究极免杀保护开关状态
     */
    fun setEnabled(enabled: Boolean) {
        mmkv.encode(KEY_ANTI_KILL_ENABLED, enabled)
        Log.d(TAG, "Anti-kill protection ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * 应用保护设置（异步）
     * @param context 上下文
     * @param onProgress 进度回调 (current, total, commandName)
     * @return Pair<成功数量, 总数量>
     */
    suspend fun applyProtection(
        context: Context,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (!isEnabled()) {
            Log.d(TAG, "Anti-kill protection is disabled, skipping")
            return@withContext Pair(0, 0)
        }

        val packageName = context.packageName
        val supportedCommands = getSupportedCommands()
        var successCount = 0

        Log.d(TAG, "Applying anti-kill protection for $packageName")
        Log.d(TAG, "Supported commands on this device (SDK ${Build.VERSION.SDK_INT}): ${supportedCommands.size}")

        RootShellExecutor.withPersistentShell(suCmd = RootConfigManager.getCustomRootCommand()) {
            supportedCommands.forEachIndexed { index, protectionCmd ->
                val current = index + 1
                val total = supportedCommands.size

                Log.d(TAG, "Applying: ${protectionCmd.name} - ${protectionCmd.description}")
                onProgress?.invoke(current, total, protectionCmd.name)

                val command = protectionCmd.commandTemplate(packageName)
                val result = executeProtectionCommand(this, command)

                if (result) {
                    successCount++
                    Log.d(TAG, "✓ Successfully applied: ${protectionCmd.name}")
                } else {
                    Log.w(TAG, "✗ Failed to apply: ${protectionCmd.name}")
                }
            }

            null
        }

        Log.d(TAG, "Applied $successCount/${supportedCommands.size} protection measures")
        return@withContext Pair(successCount, supportedCommands.size)
    }

    /**
     * 移除保护设置（Restore默认行为）
     * @param context 上下文
     * @return Pair<成功数量, 总数量>
     */
    suspend fun removeProtection(
        context: Context,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val packageName = context.packageName
        val supportedCommands = getSupportedCommands()
        var successCount = 0

        Log.d(TAG, "Removing anti-kill protection for $packageName")

        RootShellExecutor.withPersistentShell(suCmd = RootConfigManager.getCustomRootCommand()) {
            supportedCommands.forEachIndexed { index, protectionCmd ->
                val current = index + 1
                val total = supportedCommands.size

                Log.d(TAG, "Removing: ${protectionCmd.name}")
                onProgress?.invoke(current, total, "Removing ${protectionCmd.name}")

                val command = getReverseCommand(packageName, protectionCmd)
                if (command != null) {
                    val result = executeProtectionCommand(this, command)
                    if (result) {
                        successCount++
                        Log.d(TAG, "✓ Successfully removed: ${protectionCmd.name}")
                    } else {
                        Log.w(TAG, "✗ Failed to remove: ${protectionCmd.name}")
                    }
                } else {
                    // 某些命令没有明确的反向操作，跳过
                    Log.d(TAG, "⊘ No reverse command for: ${protectionCmd.name}")
                }
            }

            null
        }

        Log.d(TAG, "Removed $successCount protection measures")
        return@withContext Pair(successCount, supportedCommands.size)
    }

    /**
     * 获取反向命令（用于移除保护）
     */
    private fun getReverseCommand(packageName: String, protectionCmd: ProtectionCommand): String? {
        return when (protectionCmd.name) {
            "Battery Optimization Whitelist" -> "dumpsys deviceidle whitelist -$packageName"
            "App Standby" -> "am set-inactive $packageName true"
            "Background Restriction" -> "cmd appops set $packageName RUN_IN_BACKGROUND default"
            "Power Saving Restriction" -> "cmd appops set $packageName RUN_ANY_IN_BACKGROUND default"
            "Auto Revoke Permissions" -> "cmd appops set $packageName AUTO_REVOKE_PERMISSIONS_IF_UNUSED default"
            else -> null
        }
    }

    /**
     * 执行单个保护命令
     */
    private suspend fun executeProtectionCommand(
        shell: Shell,
        command: String
    ): Boolean {
        return withTimeoutOrNull(5.seconds) {
            suspendCancellableCoroutine { continuation ->
                shell.executeAsync(
                    suCmd = RootConfigManager.getCustomRootCommand(),
                    command = command
                ) { result ->
                    when (result) {
                        is ShellResult.Success -> {
                            Log.d(TAG, "Command executed successfully: $command")
                            continuation.resume(true)
                        }
                        is ShellResult.Error -> {
                            // 某些命令可能会返回非零退出码但实际成功
                            // 例如，如果应用已经在白名单中
                            Log.w(TAG, "Command returned error: ${result.message}, code: ${result.exitCode}")
                            continuation.resume(false)
                        }
                        is ShellResult.Timeout -> {
                            Log.e(TAG, "Command timeout: $command")
                            continuation.resume(false)
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    continuation.resume(false)
                }
            }
        } ?: false
    }

    /**
     * 快速检查当前保护状态（仅检查配置，不检查系统实际状态）
     */
    fun quickCheck(): ProtectionStatus {
        val enabled = isEnabled()
        val supportedCommandsCount = getSupportedCommands().size

        return ProtectionStatus(
            enabled = enabled,
            supportedMeasures = supportedCommandsCount,
            sdkVersion = Build.VERSION.SDK_INT
        )
    }

    /**
     * 保护状态数据类
     */
    data class ProtectionStatus(
        val enabled: Boolean,
        val supportedMeasures: Int,
        val sdkVersion: Int
    ) {
        fun getSummary(): String {
            return if (enabled) {
                "Enabled ($supportedMeasures protection measures)"
            } else {
                "Disabled"
            }
        }
    }

    /**
     * 获取所有保护措施的描述（用于UI显示）
     */
    fun getProtectionInfo(): List<ProtectionInfo> {
        return protectionCommands.map { cmd ->
            ProtectionInfo(
                name = cmd.name,
                description = cmd.description,
                supported = Build.VERSION.SDK_INT >= cmd.minSdkVersion,
                minSdkVersion = cmd.minSdkVersion
            )
        }
    }

    /**
     * 保护措施信息
     */
    data class ProtectionInfo(
        val name: String,
        val description: String,
        val supported: Boolean,
        val minSdkVersion: Int
    )
}