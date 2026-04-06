package moe.fuqiuluo.mamu.driver

/**
 * Memory region entry representing a single memory region in a process
 *
 * @property start Start address of the region
 * @property end End address of the region
 * @property type Permission flags (combination of MEM_* constants)
 * @property name Region name (path or identifier)
 */
data class MemRegionEntry(
    val start: Long, val end: Long, val type: Int, val name: String
) {
    companion object {
        const val MEM_READABLE =   0b00000000000000000000000000000001
        const val MEM_WRITABLE =   0b00000000000000000000000000000010
        const val MEM_EXECUTABLE = 0b00000000000000000000000000000100
        const val MEM_SHARED =     0b00000000000000000000000000001000
        const val MEM_UNMAPPED =  0b00000000000000000000000000010000
    }

    val isReadable: Boolean
        get() = (type and MEM_READABLE) != 0

    val isWritable: Boolean
        get() = (type and MEM_WRITABLE) != 0

    val isExecutable: Boolean
        get() = (type and MEM_EXECUTABLE) != 0

    val isShared: Boolean
        get() = (type and MEM_SHARED) != 0

    val isUnmapped: Boolean
        get() = (type and MEM_UNMAPPED) != 0

    val nonProt: Boolean
        get() = !isReadable && !isWritable && !isExecutable

    val permissionString: String
        get() = buildString {
            append(if (isReadable) 'r' else '-')
            append(if (isWritable) 'w' else '-')
            append(if (isExecutable) 'x' else '-')
            append(if (isShared) 's' else 'p')
        }

    val size: Long
        get() = end - start

    override fun toString(): String {
        return "MemRegionEntry(0x%016X-0x%016X [%s] %s, size=%d)".format(
            start, end, permissionString, name, size
        )
    }
}