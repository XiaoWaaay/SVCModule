package moe.fuqiuluo.mamu.floating.event

import moe.fuqiuluo.mamu.driver.SearchResultItem
import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry

/**
 * 保存Search结果事件
 */
data class SaveSearchResultsEvent(
    val selectedItems: List<SearchResultItem>,
    val ranges: List<DisplayMemRegionEntry>?
)
