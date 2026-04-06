package moe.fuqiuluo.mamu.floating.event

/**
 * 批量Address值变更事件
 */
data class BatchAddressValueChangedEvent(
    val changes: List<AddressChange>,
    val source: AddressValueChangedEvent.Source
) {
    data class AddressChange(
        val address: Long,
        val newValue: String,
        val valueType: Int
    )
}
