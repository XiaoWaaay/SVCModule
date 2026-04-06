package moe.fuqiuluo.mamu.utils

import android.content.Context
import android.os.Process
import android.util.Log
import moe.fuqiuluo.mamu.driver.WuwaDriver
import org.json.JSONObject
import java.io.File

/**
 * 驱动安装器
 * 负责驱动的检查和安装操作
 */
object DriverInstaller {
    private const val TAG = "DriverInstaller"

    /**
     * 解析 supreme 输出，提取 status 和 driver_fd
     * 优先使用 JSON 解析，失败则回退To正则匹配
     */
    private inline fun parseSupremeOutput(output: String): Pair<Boolean, Int?> {
        // 先尝试 JSON 解析
        val jsonResult = tryParseJson(output)
        if (jsonResult != null) {
            return jsonResult
        }

        // JSON 解析失败，回退To正则匹配
        Log.d(TAG, "JSON parse failed, trying regex")
        return tryParseRegex(output)
    }

    private inline fun tryParseJson(output: String): Pair<Boolean, Int?>? {
        val jsonStart = output.indexOf('{')
        val jsonEnd = output.lastIndexOf('}')

        if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
            return null
        }

        return try {
            val jsonStr = output.substring(jsonStart, jsonEnd + 1)
            val json = JSONObject(jsonStr)
            val status = json.optString("status", "")

            if (status == "success") {
                val driverFd = json.getInt("driver_fd")
                Log.d(TAG, "Driver installed successfully (JSON), fd: $driverFd")
                WuwaDriver.setDriverFd(driverFd)
                Pair(true, driverFd)
            } else {
                Log.w(TAG, "Supreme status: $status")
                Pair(false, null)
            }
        } catch (e: Exception) {
            Log.d(TAG, "JSON parse exception: ${e.message}")
            null
        }
    }

    private inline fun tryParseRegex(output: String): Pair<Boolean, Int?> {
        val statusRegex = """"status"\s*:\s*"(\w+)"""".toRegex()
        val fdRegex = """"driver_fd"\s*:\s*(\d+)""".toRegex()

        val statusMatch = statusRegex.find(output)
        val fdMatch = fdRegex.find(output)

        if (statusMatch != null && statusMatch.groupValues[1] == "success" && fdMatch != null) {
            val driverFd = fdMatch.groupValues[1].toInt()
            Log.d(TAG, "Driver installed successfully (regex), fd: $driverFd")
            WuwaDriver.setDriverFd(driverFd)
            return Pair(true, driverFd)
        }

        Log.w(TAG, "Failed to parse supreme output")
        return Pair(false, null)
    }

    /**
     * 检查驱动是否已安装
     * @return 是否已安装
     */
    fun isDriverInstalled(app: Context): Boolean {
        if (checkAndSetupDriver(app).first)  {
            return true
        }
        return WuwaDriver.loaded
    }

    /**
     * 检查并设置驱动FD
     * @param app Application实例
     * @return Pair<是否已安装, 驱动FD>
     */
    fun checkAndSetupDriver(app: Context): Pair<Boolean, Int?> {
        return try {
            // 释放supreme可执行文件
            val supremeFile = extractSupremeExecutable(app) ?: return Pair(false, null)

            // 执行supreme检查驱动
            val pid = Process.myPid()
            val result = RootShellExecutor.exec(
                suCmd = RootConfigManager.getCustomRootCommand(),
                "${supremeFile.absolutePath} $pid"
            )

            when (result) {
                is ShellResult.Success -> {
                    Log.d(TAG, "Supreme output: ${result.output}")
                    parseSupremeOutput(result.output)
                }

                is ShellResult.Error -> {
                    // supreme 可能因为 stack corruption 返回非零退出码，但输出仍然有效
                    Log.d(TAG, "Supreme error output: ${result.message}")
                    parseSupremeOutput(result.message)
                }

                else -> Pair(false, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking driver", e)
            Pair(false, null)
        }
    }

    /**
     * Fromassets中释放supreme可执行文件
     */
    private fun extractSupremeExecutable(app: Context): File? {
        try {
            val assetName = when {
                android.os.Build.SUPPORTED_ABIS.any { it.contains("arm64") } -> "supreme_arm64"
                android.os.Build.SUPPORTED_ABIS.any { it.contains("x86_64") } -> "supreme_x64"
                else -> {
                    Log.e(
                        TAG,
                        "Unsupported architecture: ${android.os.Build.SUPPORTED_ABIS.joinToString()}"
                    )
                    return null
                }
            }

            val outputFile = File(app.filesDir, "supreme")

            // 如果文件已存在且可执行，直接返回
            if (outputFile.exists() && outputFile.canExecute()) {
                return outputFile
            }

            // FromassetsCopy文件
            app.assets.open(assetName).use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // 设置可执行Permissions
            val chmodResult = RootShellExecutor.exec(
                suCmd = RootConfigManager.getCustomRootCommand(),
                "chmod 755 ${outputFile.absolutePath}"
            )
            if (chmodResult !is ShellResult.Success) {
                Log.e(TAG, "Failed to chmod supreme")
                return null
            }

            return outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract supreme executable", e)
            return null
        }
    }
}