package moe.fuqiuluo.mamu.floating.event

/**
 * Address值变更事件
 */
data class AddressValueChangedEvent(
    val address: Long,
    val newValue: String,
    val valueType: Int,
    val source: Source
) {
    enum class Source {
        SEARCH,         // 来自Search结果界面
        SAVED_ADDRESS,  // 来自保存Address界面
        MEMORY_PREVIEW  // 来自Memory预览界面
    }
}
