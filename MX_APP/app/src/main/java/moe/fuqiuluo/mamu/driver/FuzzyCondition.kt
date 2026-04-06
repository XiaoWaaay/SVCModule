package moe.fuqiuluo.mamu.driver

/**
 * 模糊Search条件枚举
 */
enum class FuzzyCondition(val nativeId: Int, val displayName: String) {
    /**
     * Initial Scan（记录所有值）
     */
    INITIAL(0, "Initial Scan"),

    /**
     * Value unchanged
     */
    UNCHANGED(1, "Value unchanged"),

    /**
     * Value changed
     */
    CHANGED(2, "Value changed"),

    /**
     * Value increased
     */
    INCREASED(3, "Value increased"),

    /**
     * Value decreased
     */
    DECREASED(4, "Value decreased"),

    /**
     * Value increased指定数量 (param1)
     */
    INCREASED_BY(5, "Value increased"),

    /**
     * Value decreased指定数量 (param1)
     */
    DECREASED_BY(6, "Value decreased"),

    /**
     * Value increased指定Range (param1 ~ param2)
     */
    INCREASED_BY_RANGE(7, "Value increased by range"),

    /**
     * Value decreased指定Range (param1 ~ param2)
     */
    DECREASED_BY_RANGE(8, "Value decreased by range"),

    /**
     * Value increased指定百分比 (param1 / 100.0)
     */
    INCREASED_BY_PERCENT(9, "Value increased by %"),

    /**
     * Value decreased指定百分比 (param1 / 100.0)
     */
    DECREASED_BY_PERCENT(10, "Value decreased by %");

    /**
     * 是否需要输入参数
     */
    fun needsParam(): Boolean {
        return when (this) {
            INCREASED_BY, DECREASED_BY, INCREASED_BY_PERCENT, DECREASED_BY_PERCENT -> true
            else -> false
        }
    }

    /**
     * 是否需要两个参数（Range）
     */
    fun needsTwoParams(): Boolean {
        return when (this) {
            INCREASED_BY_RANGE, DECREASED_BY_RANGE -> true
            else -> false
        }
    }

    companion object {
        fun fromNativeId(id: Int): FuzzyCondition? {
            return entries.firstOrNull { it.nativeId == id }
        }

        /**
         * 获取可用于细化Search的条件列表（排除 INITIAL）
         */
        fun getRefineConditions(): List<FuzzyCondition> {
            return entries.filter { it != INITIAL }
        }
    }
}
