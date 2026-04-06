package moe.fuqiuluo.mamu.floating.event

import moe.fuqiuluo.mamu.floating.data.model.DisplayProcessInfo
import moe.fuqiuluo.mamu.floating.data.model.SavedAddress

/**
 * UI 操作请求事件
 */
sealed class UIActionEvent {
    /** 请求显示进程选择对话框 */
    data object ShowProcessSelectionDialog : UIActionEvent()

    /** 请求显示Offset Calculator */
    data class ShowOffsetCalculatorDialog(val initialBaseAddress: Long? = null) : UIActionEvent()

    /** 请求显示MemoryRange选择对话框 */
    data object ShowMemoryRangeDialog : UIActionEvent()

    /** 请求显示偏移异或计算对话框 */
    data class ShowOffsetXorDialog(
        val selectedAddresses: List<SavedAddress>
    ) : UIActionEvent()

    /** 请求绑定进程 */
    data class BindProcessRequest(val process: DisplayProcessInfo) : UIActionEvent()

    /** 请求解绑进程（用户主动终止或解绑） */
    data object UnbindProcessRequest : UIActionEvent()

    /** 请求退出悬浮窗服务 */
    data object ExitOverlayRequest : UIActionEvent()

    /** 请求应用透明度设置 */
    data object ApplyOpacityRequest : UIActionEvent()

    /** 请求隐藏悬浮窗（Search时最小化） */
    data object HideFloatingWindow : UIActionEvent()

    /** 请求切换To设置 Tab */
    data object SwitchToSettingsTab : UIActionEvent()

    /** 请求切换ToSearch Tab */
    data object SwitchToSearchTab : UIActionEvent()

    /** 请求切换To保存Address Tab */
    data object SwitchToSavedAddressesTab : UIActionEvent()

    /** 请求切换ToMemory预览 Tab */
    data object SwitchToMemoryPreviewTab : UIActionEvent()

    /** 请求切换To断点 Tab */
    data object SwitchToBreakpointsTab : UIActionEvent()

    /** 请求跳Go toMemory预览并定位To指定Address */
    data class JumpToMemoryPreview(val address: Long) : UIActionEvent()

    /** 更新SearchTab的Badge数量 */
    data class UpdateSearchBadge(val count: Int, val total: Int?) : UIActionEvent()

    /** 更新保存AddressTab的Badge数量 */
    data class UpdateSavedAddressBadge(val count: Int) : UIActionEvent()

    /** 更新底部栏选中Address数量 */
    data class UpdateSelectedCount(val count: Int) : UIActionEvent()
}
