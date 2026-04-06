package moe.fuqiuluo.mamu.utils

import kotlin.math.abs

object ByteFormatUtils {
    private const val KB = 1024L
    private const val MB = KB * 1024
    private const val GB = MB * 1024
    private const val TB = GB * 1024

    /**
     * 将字节数转换为可读的字符串格式
     * @param bytes 字节数
     * @param decimals 小数位数，默认2位
     * @return 格式化后的字符串，如 "1.23 MB"
     */
    fun formatBytes(bytes: Long, decimals: Int = 2): String {
        if (bytes == 0L) return "0 B"

        val absBytes = abs(bytes)
        val sign = if (bytes < 0) "-" else ""

        return when {
            absBytes >= TB -> String.format("%s%.${decimals}f TB", sign, absBytes.toDouble() / TB)
            absBytes >= GB -> String.format("%s%.${decimals}f GB", sign, absBytes.toDouble() / GB)
            absBytes >= MB -> String.format("%s%.${decimals}f MB", sign, absBytes.toDouble() / MB)
            absBytes >= KB -> String.format("%s%.${decimals}f KB", sign, absBytes.toDouble() / KB)
            else -> "$sign$absBytes B"
        }
    }

    /**
     * 将字节数转换为指定单位
     * @param bytes 字节数
     * @param unit 目标单位 (B, KB, MB, GB, TB)
     * @param decimals 小数位数，默认2位
     * @return 转换后的数值
     */
    fun convertTo(bytes: Long, unit: ByteUnit, decimals: Int = 2): String {
        val value = when (unit) {
            ByteUnit.B -> bytes.toDouble()
            ByteUnit.KB -> bytes.toDouble() / KB
            ByteUnit.MB -> bytes.toDouble() / MB
            ByteUnit.GB -> bytes.toDouble() / GB
            ByteUnit.TB -> bytes.toDouble() / TB
        }

        return if (decimals == 0) {
            "${value.toLong()} ${unit.name}"
        } else {
            String.format("%.${decimals}f ${unit.name}", value)
        }
    }

    /**
     * 字节单位枚举
     */
    enum class ByteUnit {
        B, KB, MB, GB, TB
    }
}
