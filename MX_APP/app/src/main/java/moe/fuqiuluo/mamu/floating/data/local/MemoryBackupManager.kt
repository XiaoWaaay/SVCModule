package moe.fuqiuluo.mamu.floating.data.local

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import moe.fuqiuluo.mamu.floating.data.model.MemoryBackupRecord
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType

private const val TAG = "MemoryBackupManager"

object MemoryBackupManager {
    private val backups = Long2ObjectOpenHashMap<MemoryBackupRecord>()

    fun saveBackup(
        address: Long,
        originalValue: String,
        valueType: DisplayValueType
    ) {
        val record = MemoryBackupRecord(
            address = address,
            originalValue = originalValue,
            originalType = valueType,
            firstModifiedTime = System.currentTimeMillis()
        )

        backups.put(address, record)
    }

    fun getBackup(address: Long): MemoryBackupRecord? {
        return backups.get(address)
    }

    fun removeBackup(address: Long): MemoryBackupRecord? {
        return backups.remove(address)
    }

    fun hasBackup(address: Long): Boolean {
        return backups.containsKey(address)
    }

    fun getAllBackupAddresses(): LongArray {
        return backups.keys.toLongArray()
    }

    fun getAllBackups(): List<MemoryBackupRecord> {
        return backups.values.toList()
    }

    fun clear() {
        val count = backups.size
        backups.clear()
    }
}
