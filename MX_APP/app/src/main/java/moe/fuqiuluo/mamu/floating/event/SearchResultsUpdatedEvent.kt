package moe.fuqiuluo.mamu.floating.event

import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry

/**
 * Search结果更新事件（From保存Address界面Search后发送）
 */
data class SearchResultsUpdatedEvent(
    val totalCount: Long,
    val ranges: List<DisplayMemRegionEntry>
)
