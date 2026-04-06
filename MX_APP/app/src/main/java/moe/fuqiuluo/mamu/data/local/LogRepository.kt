package moe.fuqiuluo.mamu.data.local

import android.os.Process
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import moe.fuqiuluo.mamu.data.model.LogCaptureTarget
import moe.fuqiuluo.mamu.data.model.LogEntry
import moe.fuqiuluo.mamu.data.model.LogLevel
import moe.fuqiuluo.mamu.data.model.LogSource
import moe.fuqiuluo.mamu.utils.RootConfigManager
import moe.fuqiuluo.mamu.utils.RootShellExecutor
import moe.fuqiuluo.mamu.utils.ShellResult
import java.io.BufferedReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 日志仓库
 * 负责通过 root shell 捕获 logcat 和 dmesg 日志
 */
class LogRepository {
    companion object {
        private const val TAG = "LogRepository"
        private val DATE_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    private val isCapturing = AtomicBoolean(false)
    private var currentProcess: java.lang.Process? = null

    val myPid: Int = Process.myPid()

    /**
     * 使用表达式捕获 logcat 日志流
     * 支持 logcat 原生表达式如: package:mine, tag:MyTag, level:error 等
     */
    fun captureLogcatWithExpression(expression: String): Flow<LogEntry> = callbackFlow {
        isCapturing.set(true)

        val command = buildLogcatCommandFromExpression(expression)
        Log.d(TAG, "Starting logcat capture: $command")

        val outputList = object : MutableList<String> by mutableListOf() {
            override fun add(element: String): Boolean {
                if (isCapturing.get() && isActive) {
                    parseLogcatLine(element)?.let { entry ->
                        trySend(entry)
                    }
                }
                return true
            }
        }

        // 异步获取 shell 并执行
        Shell.getShell { shell ->
            shell.newJob()
                .add(command)
                .to(outputList)
                .submit { result ->
                    Log.d(TAG, "Logcat process ended with code: ${result.code}")
                    if (!result.isSuccess && isCapturing.get()) {
                        close(Exception("Logcat failed: ${result.err.joinToString()}"))
                    }
                }
        }

        awaitClose {
            isCapturing.set(false)
            // 异步终止 logcat
            Shell.getShell { shell ->
                shell.newJob().add("pkill -f 'logcat'").submit()
            }
            Log.d(TAG, "Logcat capture stopped")
        }
    }

    /**
     * 开始捕获 logcat 日志流
     */
    fun captureLogcat(
        target: LogCaptureTarget,
        customPid: Int? = null,
        customPackage: String? = null
    ): Flow<LogEntry> = callbackFlow {
        isCapturing.set(true)

        val command = buildLogcatCommand(target, customPid, customPackage)
        Log.d(TAG, "Starting logcat capture: $command")

        try {
            val suCmd = RootConfigManager.getCustomRootCommand()
            val shell = if (suCmd == RootConfigManager.DEFAULT_ROOT_COMMAND) {
                Shell.getShell()
            } else {
                RootShellExecutor.getShellBuilder(suCmd).build()
            }

            val outputList = object : MutableList<String> by mutableListOf() {
                override fun add(element: String): Boolean {
                    if (isCapturing.get() && isActive) {
                        parseLogcatLine(element)?.let { entry ->
                            trySend(entry)
                        }
                    }
                    return true
                }
            }

            shell.newJob()
                .add(command)
                .to(outputList)
                .submit { result ->
                    Log.d(TAG, "Logcat process ended with code: ${result.code}")
                }

            awaitClose {
                isCapturing.set(false)
                shell.newJob().add("pkill -f 'logcat'").submit()
                Log.d(TAG, "Logcat capture stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start logcat capture", e)
            close(e)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 开始捕获 dmesg 内核日志流
     */
    fun captureDmesg(): Flow<LogEntry> = callbackFlow {
        isCapturing.set(true)
        Log.d(TAG, "Starting dmesg capture")

        try {
            val suCmd = RootConfigManager.getCustomRootCommand()
            val shell = if (suCmd == RootConfigManager.DEFAULT_ROOT_COMMAND) {
                Shell.getShell()
            } else {
                RootShellExecutor.getShellBuilder(suCmd).build()
            }

            val outputList = object : MutableList<String> by mutableListOf() {
                override fun add(element: String): Boolean {
                    if (isCapturing.get() && isActive) {
                        parseDmesgLine(element)?.let { entry ->
                            trySend(entry)
                        }
                    }
                    return true
                }
            }

            // dmesg -w 持续监听内核日志
            shell.newJob()
                .add("dmesg -w")
                .to(outputList)
                .submit { result ->
                    Log.d(TAG, "Dmesg process ended with code: ${result.code}")
                }

            awaitClose {
                isCapturing.set(false)
                shell.newJob().add("pkill -f 'dmesg -w'").submit()
                Log.d(TAG, "Dmesg capture stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start dmesg capture", e)
            close(e)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 获取History logcat 日志
     */
    suspend fun getLogcatHistory(
        target: LogCaptureTarget,
        customPid: Int? = null,
        customPackage: String? = null,
        lineCount: Int = 500
    ): List<LogEntry> = withContext(Dispatchers.IO) {
        val command = buildLogcatCommand(target, customPid, customPackage, dump = true, lineCount = lineCount)
        Log.d(TAG, "Getting logcat history: $command")

        val result = RootShellExecutor.exec(
            suCmd = RootConfigManager.getCustomRootCommand(),
            command = command,
            timeoutMs = 10000
        )

        when (result) {
            is ShellResult.Success -> {
                result.output.lines()
                    .mapNotNull { parseLogcatLine(it) }
            }
            else -> {
                Log.e(TAG, "Failed to get logcat history: $result")
                emptyList()
            }
        }
    }

    /**
     * 获取History dmesg 日志
     */
    suspend fun getDmesgHistory(lineCount: Int = 500): List<LogEntry> = withContext(Dispatchers.IO) {
        val command = "dmesg | tail -n $lineCount"
        Log.d(TAG, "Getting dmesg history: $command")

        val result = RootShellExecutor.exec(
            suCmd = RootConfigManager.getCustomRootCommand(),
            command = command,
            timeoutMs = 10000
        )

        when (result) {
            is ShellResult.Success -> {
                result.output.lines()
                    .mapNotNull { parseDmesgLine(it) }
            }
            else -> {
                Log.e(TAG, "Failed to get dmesg history: $result")
                emptyList()
            }
        }
    }

    /**
     * 清除 logcat 缓冲区
     */
    suspend fun clearLogcat(): Boolean = withContext(Dispatchers.IO) {
        val result = RootShellExecutor.exec(
            suCmd = RootConfigManager.getCustomRootCommand(),
            command = "logcat -c"
        )
        result is ShellResult.Success
    }

    /**
     * 清除 dmesg 缓冲区
     */
    suspend fun clearDmesg(): Boolean = withContext(Dispatchers.IO) {
        val result = RootShellExecutor.exec(
            suCmd = RootConfigManager.getCustomRootCommand(),
            command = "dmesg -c > /dev/null"
        )
        result is ShellResult.Success
    }

    /**
     * 停止捕获
     */
    fun stopCapture() {
        isCapturing.set(false)
        currentProcess?.destroy()
        currentProcess = null
    }

    private fun buildLogcatCommand(
        target: LogCaptureTarget,
        customPid: Int?,
        customPackage: String?,
        dump: Boolean = false,
        lineCount: Int = 500
    ): String {
        val baseCmd = buildString {
            append("logcat")
            append(" -v threadtime") // 详细时间格式

            if (dump) {
                append(" -d") // dump 模式，输出后退出
                append(" -t $lineCount") // 限制行数
            }

            when (target) {
                LogCaptureTarget.SELF -> {
                    append(" --pid=$myPid")
                }
                LogCaptureTarget.CUSTOM -> {
                    customPid?.let { append(" --pid=$it") }
                    // 包名过滤需要额外处理
                }
                LogCaptureTarget.ALL -> {
                    // 不添加过滤，捕获所有
                }
            }
        }
        return baseCmd
    }

    /**
     * From表达式构建 logcat 命令
     * 支持: package:mine, package:com.xxx, tag:xxx, level:v/d/i/w/e, pid:xxx
     * 以及直接的 logcat 参数
     */
    private fun buildLogcatCommandFromExpression(expression: String): String {
        val cmd = StringBuilder("logcat -v threadtime -T 1") // -T 1 只获取新日志，避免History日志刷屏
        
        if (expression.isBlank()) {
            return cmd.toString()
        }

        val expr = expression.trim()
        
        // 解析表达式
        when {
            expr.equals("package:mine", ignoreCase = true) -> {
                cmd.append(" --pid=$myPid")
                // 排除 SHELLOUT 避免无限循环
                cmd.append(" SHELLOUT:S *:V")
            }
            expr.startsWith("package:", ignoreCase = true) -> {
                val pkg = expr.substringAfter(":")
                cmd.append(" --pid=\$(pidof $pkg)")
            }
            expr.startsWith("pid:", ignoreCase = true) -> {
                val pid = expr.substringAfter(":")
                cmd.append(" --pid=$pid")
            }
            expr.startsWith("tag:", ignoreCase = true) -> {
                val tag = expr.substringAfter(":")
                cmd.append(" -s '$tag:*'")
            }
            expr.startsWith("level:", ignoreCase = true) -> {
                val level = expr.substringAfter(":").uppercase()
                cmd.append(" '*:$level'")
            }
            expr.startsWith("--") || expr.startsWith("-") -> {
                cmd.append(" $expr")
            }
            else -> {
                cmd.append(" $expr")
            }
        }

        return cmd.toString()
    }

    /**
     * 解析 logcat 行
     * 格式: MM-DD HH:MM:SS.mmm PID TID LEVEL TAG: MESSAGE
     * 例如: 01-12 10:30:45.123  1234  5678 D MyTag: Hello World
     */
    private fun parseLogcatLine(line: String): LogEntry? {
        if (line.isBlank()) return null

        // threadtime 格式正则
        val regex = Regex("""^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEFS])\s+(.+?):\s*(.*)$""")
        val match = regex.find(line)

        return if (match != null) {
            val (dateStr, pidStr, tidStr, levelChar, tag, message) = match.destructured
            val timestamp = try {
                val date = DATE_FORMAT.parse(dateStr)
                // 补充年份
                val calendar = Calendar.getInstance()
                val currentYear = calendar.get(Calendar.YEAR)
                calendar.time = date!!
                calendar.set(Calendar.YEAR, currentYear)
                calendar.timeInMillis
            } catch (e: Exception) {
                System.currentTimeMillis()
            }

            LogEntry(
                timestamp = timestamp,
                level = LogLevel.fromChar(levelChar.first()),
                tag = tag.trim(),
                message = message,
                pid = pidStr.toIntOrNull() ?: -1,
                tid = tidStr.toIntOrNull() ?: -1,
                source = LogSource.LOGCAT,
                raw = line
            )
        } else {
            // 无法解析的行，作为 UNKNOWN 级别
            LogEntry(
                timestamp = System.currentTimeMillis(),
                level = LogLevel.UNKNOWN,
                tag = "",
                message = line,
                source = LogSource.LOGCAT,
                raw = line
            )
        }
    }

    /**
     * 解析 dmesg 行
     * 格式: [timestamp] message 或 <level>[timestamp] message
     * 例如: [12345.678901] init: Service started
     */
    private fun parseDmesgLine(line: String): LogEntry? {
        if (line.isBlank()) return null

        // dmesg 格式正则
        val regex = Regex("""^(?:<(\d)>)?\[\s*(\d+\.\d+)\]\s*(.*)$""")
        val match = regex.find(line)

        return if (match != null) {
            val levelNum = match.groupValues[1].toIntOrNull()
            val timestampSec = match.groupValues[2].toDoubleOrNull() ?: 0.0
            val message = match.groupValues[3]

            // 内核日志级别映射
            val level = when (levelNum) {
                0, 1, 2 -> LogLevel.ERROR   // EMERG, ALERT, CRIT
                3 -> LogLevel.ERROR         // ERR
                4 -> LogLevel.WARNING       // WARNING
                5, 6 -> LogLevel.INFO       // NOTICE, INFO
                7 -> LogLevel.DEBUG         // DEBUG
                else -> LogLevel.INFO
            }

            // FromMessage中提取 tag（通常是第一个冒号前的部分）
            val tagMatch = Regex("""^(\S+?):\s*(.*)$""").find(message)
            val (tag, msg) = if (tagMatch != null) {
                tagMatch.groupValues[1] to tagMatch.groupValues[2]
            } else {
                "kernel" to message
            }

            // 将内核时间戳转换为系统时间（近似）
            val bootTime = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
            val timestamp = bootTime + (timestampSec * 1000).toLong()

            LogEntry(
                timestamp = timestamp,
                level = level,
                tag = tag,
                message = msg,
                source = LogSource.DMESG,
                raw = line
            )
        } else {
            LogEntry(
                timestamp = System.currentTimeMillis(),
                level = LogLevel.UNKNOWN,
                tag = "kernel",
                message = line,
                source = LogSource.DMESG,
                raw = line
            )
        }
    }
}
