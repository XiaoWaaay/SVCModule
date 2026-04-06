package moe.fuqiuluo.mamu.floating.dialog

import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType

// 用于保存过滤对话框的状态
data class FilterDialogState(
    var maxDisplayCount: Int = 100,
    var enableAddressFilter: Boolean = false,
    var addressRangeStart: String = "",
    var addressRangeEnd: String = "",
    var enableDataTypeFilter: Boolean = false,
    var selectedDataTypes: MutableSet<DisplayValueType> = mutableSetOf(),
) {
    /**
     * 判断是否启用了任何过滤
     */
    fun isFilterEnabled(): Boolean {
        return enableAddressFilter || enableDataTypeFilter
    }

    /**
     * 获取过滤描述文本
     */
    fun getFilterDescription(): String {
        val filters = mutableListOf<String>()
        if (enableAddressFilter) filters.add("Address")
        if (enableDataTypeFilter) filters.add("Type")
        return if (filters.isEmpty()) "" else filters.joinToString("+")
    }
}