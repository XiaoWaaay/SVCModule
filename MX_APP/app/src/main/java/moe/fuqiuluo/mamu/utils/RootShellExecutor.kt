package moe.fuqiuluo.mamu.utils

import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File
import java.util.concurrent.TimeoutException

/**
 * Root Shell 执行结果
 */
sealed class ShellResult {
    data class Success(val output: String, val exitCode: Int = 0) : ShellResult()
    data class Error(val message: String, val exitCode: Int = -1) : ShellResult()
    data class Timeout(val duration: Long) : ShellResult()
}

/**
 * Root 方案类型
 */
enum class RootProvider {
    MAGISK,      // Magisk - 支持 FLAG_MOUNT_MASTER
    KERNELSU,    // KernelSU/APatch/SukiSU - 不支持 FLAG_MOUNT_MASTER
    UNKNOWN      // 未知 - 使用保守配置
}

/**
 * Root Shell 执行器
 * 使用 libsu 实现，提供稳定可靠的 root shell 执行能力
 * 自动检测 Root 方案并使用兼容的配置
 */
object RootShellExecutor {
    private const val TAG = "RootShellExecutor"

    /**
     * 检测当前设备使用的 Root 方案
     */
    val rootProvider: RootProvider by lazy {
        detectRootProvider().also {
            Log.d(TAG, "Detected root provider: $it")
        }
    }

    /**
     * 是否为 Magisk 环境
     */
    val isMagisk: Boolean get() = rootProvider == RootProvider.MAGISK

    /**
     * 是否为 KernelSU 环境 (包括 KernelSU, APatch, SukiSU 等)
     */
    val isKernelSU: Boolean get() = rootProvider == RootProvider.KERNELSU

    private fun detectRootProvider(): RootProvider {
        // 检测 KernelSU (包括 SukiSU, APatch 等基于 KernelSU 的方案)
        // KernelSU 的特征Path
        val kernelSuPaths = listOf(
            "/data/adb/ksu",
            "/data/adb/ksud",
            "/data/adb/ksu/bin/su"
        )
        if (kernelSuPaths.any { File(it).exists() }) {
            return RootProvider.KERNELSU
        }

        // 检测 APatch
        val apatchPaths = listOf(
            "/data/adb/ap",
            "/data/adb/apd"
        )
        if (apatchPaths.any { File(it).exists() }) {
            return RootProvider.KERNELSU // APatch 与 KernelSU 兼容性相同
        }

        // 检测 Magisk
        val magiskPaths = listOf(
            "/data/adb/magisk",
            "/sbin/.magisk",
            "/data/adb/magisk.db"
        )
        if (magiskPaths.any { File(it).exists() }) {
            return RootProvider.MAGISK
        }

        // 通过环境变量检测
        try {
            val env = System.getenv("MAGISK_VER_CODE")
            if (!env.isNullOrEmpty()) {
                return RootProvider.MAGISK
            }
        } catch (_: Exception) {}

        // 默认使用保守配置 (与 KernelSU 相同，不使用 FLAG_MOUNT_MASTER)
        return RootProvider.UNKNOWN
    }

    init {
        // 配置 libsu，根据 Root 方案自动选择配置
        Shell.enableVerboseLogging = true

        val builder = Shell.Builder.create().setTimeout(10)

        // 只有 Magisk 环境才使用 FLAG_MOUNT_MASTER
        if (rootProvider == RootProvider.MAGISK) {
            builder.setFlags(Shell.FLAG_MOUNT_MASTER)
            Log.d(TAG, "Using Magisk configuration with FLAG_MOUNT_MASTER")
        } else {
            Log.d(TAG, "Using KernelSU/Universal configuration without FLAG_MOUNT_MASTER")
        }

        Shell.setDefaultBuilder(builder)
    }

    /**
     * 使用Custom su 命令配置 Shell
     */
    fun getShellBuilder(suCmd: String): Shell.Builder {
        val builder = Shell.Builder.create()
            .setTimeout(10)
            .setCommands(suCmd)

        // 只有 Magisk 环境才使用 FLAG_MOUNT_MASTER
        if (rootProvider == RootProvider.MAGISK) {
            builder.setFlags(Shell.FLAG_MOUNT_MASTER)
        }

        return builder
    }

    /**
     * 一次性执行命令
     * @param suCmd su 命令Path（如 "su" 或CustomPath）
     * @param command 要执行的命令
     * @param timeoutMs 超时时间（毫秒），libsu 内部会处理超时
     */
    fun exec(
        suCmd: String,
        command: String,
        timeoutMs: Long = 5000L
    ): ShellResult {
        return try {
            val shell = Shell.getShell()
            Log.d(TAG, "Shell status - isRoot: ${shell.isRoot}, isAlive: ${shell.isAlive}")
            
            val result = if (suCmd == RootConfigManager.DEFAULT_ROOT_COMMAND) {
                // 使用默认 shell
                Log.d(TAG, "Shell.cmd($command)")
                Shell.cmd(command).exec()
            } else {
                // 使用Custom su 命令创建新 shell
                val customShell = getShellBuilder(suCmd).build()
                Log.d(TAG, "Shell.cmd2($command) with custom su: $suCmd")
                customShell.newJob().add(command).exec()
            }

            val stdout = result.out.joinToString("\n")
            val stderr = result.err.joinToString("\n")
            val exitCode = result.code

            // 合并 stdout 和 stderr，确保都能被解析
            val combinedOutput = buildString {
                if (stdout.isNotEmpty()) append(stdout)
                if (stderr.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append(stderr)
                }
            }

            Log.d(TAG, "Command result - exitCode: $exitCode, stdout: $stdout, stderr: $stderr")

            if (result.isSuccess) {
                ShellResult.Success(combinedOutput, exitCode)
            } else {
                ShellResult.Error(
                    combinedOutput.ifEmpty { "Command failed with exit code $exitCode" },
                    exitCode
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute command: $command", e)
            ShellResult.Error(e.message ?: "Unknown error", -1)
        }
    }

    /**
     * 批量一次性执行命令
     */
    fun execBatch(
        suCmd: String,
        commands: List<String>,
        timeoutMs: Long = 5000L
    ): List<ShellResult> {
        return commands.map { exec(suCmd, it, timeoutMs) }
    }

    /**
     * Fire and forget - 不等待结果
     */
    fun execNoWait(suCmd: String, command: String) {
        try {
            if (suCmd == RootConfigManager.DEFAULT_ROOT_COMMAND) {
                Shell.cmd(command).submit()
            } else {
                val shell = getShellBuilder(suCmd).build()
                shell.newJob().add(command).submit()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute command (no wait): $command", e)
        }
    }

    /**
     * DSL 风格：使用 libsu Shell 执行多条命令
     * 注意：libsu 使用全局 Shell，无需手动管理生命周期
     */
    inline fun <T> withPersistentShell(
        suCmd: String,
        block: Shell.() -> T
    ): T {
        val shell = if (suCmd == RootConfigManager.DEFAULT_ROOT_COMMAND) {
            Shell.getShell()
        } else {
            getShellBuilder(suCmd).build()
        }
        return shell.block()
    }
}

/**
 * Shell 扩展：异步执行命令
 */
fun Shell.executeAsync(
    suCmd: String,
    command: String,
    callback: (ShellResult) -> Unit
) {
    newJob().add(command).submit { result ->
        val stdout = result.out.joinToString("\n")
        val stderr = result.err.joinToString("\n")
        val exitCode = result.code

        val combinedOutput = buildString {
            if (stdout.isNotEmpty()) append(stdout)
            if (stderr.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append(stderr)
            }
        }

        val shellResult = if (result.isSuccess) {
            ShellResult.Success(combinedOutput, exitCode)
        } else {
            ShellResult.Error(
                combinedOutput.ifEmpty { "Command failed with exit code $exitCode" },
                exitCode
            )
        }
        callback(shellResult)
    }
}

/**
 * String 扩展：直接作为 root 命令执行
 */
fun String.asRootCommand(suCmd: String, timeoutMs: Long = 5000L): ShellResult =
    RootShellExecutor.exec(suCmd, this, timeoutMs)

/**
 * ShellResult 扩展：成功时执行回调
 */
inline fun ShellResult.onSuccess(block: (String) -> Unit): ShellResult {
    if (this is ShellResult.Success) block(output)
    return this
}

/**
 * ShellResult 扩展：失败时执行回调
 */
inline fun ShellResult.onError(block: (String) -> Unit): ShellResult {
    if (this is ShellResult.Error) block(message)
    return this
}

/**
 * ShellResult 扩展：超时时执行回调
 */
inline fun ShellResult.onTimeout(block: (Long) -> Unit): ShellResult {
    if (this is ShellResult.Timeout) block(duration)
    return this
}

/**
 * ShellResult 扩展：获取结果或返回 null
 */
fun ShellResult.getOrNull(): String? =
    (this as? ShellResult.Success)?.output

/**
 * ShellResult 扩展：获取结果或返回默认值
 */
fun ShellResult.getOrDefault(default: String): String =
    (this as? ShellResult.Success)?.output ?: default

/**
 * ShellResult 扩展：获取结果或抛出异常
 */
fun ShellResult.getOrThrow(): String = when (this) {
    is ShellResult.Success -> output
    is ShellResult.Error -> throw RuntimeException("Command failed: $message (exit code: $exitCode)")
    is ShellResult.Timeout -> throw TimeoutException("Command timeout after ${duration}ms")
}

/**
 * ShellResult 扩展：判断是否成功
 */
fun ShellResult.isSuccess(): Boolean = this is ShellResult.Success

/**
 * ShellResult 扩展：判断是否失败
 */
fun ShellResult.isError(): Boolean = this is ShellResult.Error

/**
 * ShellResult 扩展：判断是否超时
 */
fun ShellResult.isTimeout(): Boolean = this is ShellResult.Timeout
