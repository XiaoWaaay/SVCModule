package moe.fuqiuluo.mamu.floating.event

import moe.fuqiuluo.mamu.floating.data.model.DisplayProcessInfo

/**
 * 进程状态变更事件
 */
data class ProcessStateEvent(
    val type: Type,
    val process: DisplayProcessInfo?
) {
    enum class Type {
        BOUND,      // 进程已绑定
        UNBOUND,    // 进程已解绑（用户主动解绑或终止）
        DIED        // 进程已死亡（异常退出）
    }
}
