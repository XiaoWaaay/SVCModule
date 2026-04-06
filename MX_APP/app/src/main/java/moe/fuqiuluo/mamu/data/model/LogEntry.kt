package moe.fuqiuluo.mamu.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 日志来源类型
 */
enum class LogSource {
    LOGCAT,     // Android logcat
    DMESG       // 内核日志
}

/**
 * 日志级别
 */
enum class LogLevel(val label: String, val priority: Int) {
    VERBOSE("V", 0),
    DEBUG("D", 1),
    INFO("I", 2),
    WARNING("W", 3),
    ERROR("E", 4),
    FATAL("F", 5),
    SILENT("S", 6),
    UNKNOWN("?", -1);

    companion object {
        fun fromChar(char: Char): LogLevel = when (char.uppercaseChar()) {
            'V' -> VERBOSE
            'D' -> DEBUG
            'I' -> INFO
            'W' -> WARNING
            'E' -> ERROR
            'F' -> FATAL
            'S' -> SILENT
            else -> UNKNOWN
        }
    }
}

/**
 * 日志条目
 */
data class LogEntry(
    val id: Long = idGenerator.incrementAndGet(),
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val pid: Int = -1,
    val tid: Int = -1,
    val source: LogSource = LogSource.LOGCAT,
    val raw: String = ""
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))

    val formattedDate: String
        get() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))

    companion object {
        private val idGenerator = AtomicLong(0)
    }
}

/**
 * 日志过滤配置
 */
data class LogFilterConfig(
    val minLevel: LogLevel = LogLevel.VERBOSE,
    val tagFilter: String = "",
    val messageFilter: String = "",
    val pidFilter: Int? = null,
    val showVerbose: Boolean = true,
    val showDebug: Boolean = true,
    val showInfo: Boolean = true,
    val showWarning: Boolean = true,
    val showError: Boolean = true,
    val showFatal: Boolean = true
) {
    fun matches(entry: LogEntry): Boolean {
        // 级别过滤
        if (entry.level.priority < minLevel.priority) return false

        // 按级别开关过滤
        when (entry.level) {
            LogLevel.VERBOSE -> if (!showVerbose) return false
            LogLevel.DEBUG -> if (!showDebug) return false
            LogLevel.INFO -> if (!showInfo) return false
            LogLevel.WARNING -> if (!showWarning) return false
            LogLevel.ERROR -> if (!showError) return false
            LogLevel.FATAL -> if (!showFatal) return false
            else -> {}
        }

        // Tag 过滤
        if (tagFilter.isNotEmpty() && !entry.tag.contains(tagFilter, ignoreCase = true)) {
            return false
        }

        // Message过滤
        if (messageFilter.isNotEmpty() && !entry.message.contains(messageFilter, ignoreCase = true)) {
            return false
        }

        // PID 过滤
        if (pidFilter != null && entry.pid != pidFilter) {
            return false
        }

        return true
    }
}

/**
 * 日志捕获目标
 */
enum class LogCaptureTarget(val label: String, val description: String) {
    SELF("This app", "Capture only Mamu logs"),
    ALL("All apps", "Capture logs from all apps on the system"),
    CUSTOM("Custom", "Specify a package name or PID")
}
