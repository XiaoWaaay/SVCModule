package moe.fuqiuluo.mamu.floating.ext

import android.annotation.SuppressLint

/**
 * 格式化耗时，自动选择合适的单位
 * @param millis 毫秒数
 * @return 格式化后的字符串，例如 "150ms" 或 "2.5s" 或 "1m 30s"
 */
@SuppressLint("DefaultLocale")
fun formatElapsedTime(millis: Long): String {
    return when {
        millis < 1000 -> "${millis}ms"
        millis < 60_000 -> {
            val seconds = millis / 1000.0
            String.format("%.1fs", seconds)
        }
        else -> {
            val minutes = millis / 60_000
            val seconds = (millis % 60_000) / 1000
            if (seconds == 0L) {
                "${minutes}m"
            } else {
                "${minutes}m ${seconds}s"
            }
        }
    }
}