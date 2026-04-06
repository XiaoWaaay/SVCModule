package moe.fuqiuluo.mamu.floating.event

/**
 * 导航ToMemoryAddress事件
 * 用于FromSearch界面跳Go toMemory预览界面指定Address
 */
data class NavigateToMemoryAddressEvent(
    val address: Long
)
