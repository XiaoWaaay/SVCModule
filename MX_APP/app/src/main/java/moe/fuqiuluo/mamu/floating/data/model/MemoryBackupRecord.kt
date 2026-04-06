package moe.fuqiuluo.mamu.floating.data.model

/**
 * Memory修改备份记录
 */
data class MemoryBackupRecord(
    val address: Long,
    val originalValue: String,      // 修改前的值（用于Restore）
    val originalType: DisplayValueType,
    val firstModifiedTime: Long     // 修改时间
)
