package moe.fuqiuluo.mamu.floating.data.model

/**
 * SearchHistory记录项
 * @param expression Search表达式
 * @param valueType 值类型
 * @param timestamp Search时间戳
 */
data class SearchHistoryItem(
    val expression: String,
    val valueType: DisplayValueType,
    val timestamp: Long = System.currentTimeMillis()
)