package moe.fuqiuluo.mamu.driver


/**
 * Search模式枚举
 * 对应 Rust 层的 SearchResultMode
 */
enum class SearchMode(val nativeValue: Int) {
    /**
     * 精确Search（包含联合Search/RangeSearch）
     */
    EXACT(0),

    /**
     * 模糊Search
     */
    FUZZY(1);

    companion object {
        fun fromNativeValue(value: Int): SearchMode {
            return entries.firstOrNull { it.nativeValue == value } ?: EXACT
        }
    }
}