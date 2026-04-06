package moe.fuqiuluo.mamu.floating.data.model

import androidx.annotation.ColorInt
import androidx.core.graphics.toColorInt

/**
 * MemoryRange类型枚举
 */
enum class MemoryRange(
    val code: String,
    val displayName: String,
    @field:ColorInt
    val color: Int,
    val dangerous: Boolean = false,
    val slow: Boolean = false,
) {
    Jh("Jh", "Java Heap", "#00FF7F".toColorInt()),        // SpringGreen - Java堆
    Ch("Ch", "C++ heap", "#00FF7F".toColorInt()),         // SpringGreen - C++堆
    Ca("Ca", "C++ alloc", "#00FF7F".toColorInt()),        // SpringGreen - C++分配
    Cd("Cd", "C++ .data", "#00FF7F".toColorInt()),        // SpringGreen - C++数据段
    Cb("Cb", "C++ .bss", "#00FF7F".toColorInt()),         // SpringGreen - C++ BSS段
    Ps("Ps", "PPSSPP", "#00FF7F".toColorInt()),           // SpringGreen - PPSSPP
    An("An", "Anonymous", "#00FF7F".toColorInt()),        // SpringGreen - 匿名
    J("J", "Java", "#FFFACD".toColorInt()),               // LemonChiffon - Java
    S("S", "Stack", "#FFFACD".toColorInt()),              // LemonChiffon - 栈
    As("As", "Ashmem", "#FFFACD".toColorInt()),           // LemonChiffon - Ashmem
    V("V", "Video", "#FFFACD".toColorInt()),              // LemonChiffon - 视频
    O("O", "Other", "#FFFF00".toColorInt(), slow = true), // Yellow - 其他(慢)
    B("B", "Bad", "#FF0000".toColorInt(), dangerous = true), // Red - Bad(危险)
    Xa("Xa", "Code app", "#9370DB".toColorInt(), dangerous = true),   // MediumPurple - 应用代码(危险)
    Xs("Xs", "Code system", "#BA55D3".toColorInt(), dangerous = true), // MediumOrchid - 系统代码(危险)
    Dx("Dx", "DEX", "#DDA0DD".toColorInt(), dangerous = true),        // Plum - DEX代码(危险)
    Jc("Jc", "JIT cache code", "#FFB6C1".toColorInt(), dangerous = true),  // LightPink - JIT代码缓存(危险)
    Oa("Oa", "OAT Code", "#D8BFD8".toColorInt(), dangerous = true),        // Thistle - OAT代码(危险)
    Vx("Vx", "VDEX", "#E6E6FA".toColorInt()),                         // Lavender - VDEX
    Ts("Ts", "Thread stack", "#F0E68C".toColorInt()),                 // Khaki - 线程栈
    Xx("Xx", "No perm", "#808080".toColorInt(), dangerous = true);    // Gray - 无Permissions区域(危险)
    companion object {
        /**
         * From代码获取枚举值
         */
        fun fromCode(code: String): MemoryRange? {
            return entries.find { it.code == code }
        }

        /**
         * From显示名称获取枚举值
         */
        fun fromDisplayName(displayName: String): MemoryRange? {
            return entries.find { it.displayName == displayName }
        }

        /**
         * 获取所有显示名称
         */
        fun getAllDisplayNames(): Array<String> {
            return entries.map { it.displayName }.toTypedArray()
        }

        /**
         * 获取所有代码
         */
        fun getAllCodes(): Array<String> {
            return entries.map { it.code }.toTypedArray()
        }
    }
}