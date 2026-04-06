package moe.fuqiuluo.mamu.floating.dialog

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.databinding.DialogAddressActionRvBinding
import moe.fuqiuluo.mamu.databinding.ItemAddressActionBinding
import moe.fuqiuluo.mamu.driver.Disassembler
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import moe.fuqiuluo.mamu.floating.data.model.MemoryDisplayFormat
import moe.fuqiuluo.mamu.floating.data.model.MemoryRange
import moe.fuqiuluo.mamu.floating.data.model.SavedAddress
import moe.fuqiuluo.mamu.utils.ValueTypeUtils
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import moe.fuqiuluo.mamu.widget.RealtimeMonitorOverlay
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.text.HexFormat

/**
 * Address Actions对话框来源
 */
enum class AddressActionSource {
    /** Search结果界面 */
    SEARCH,
    /** Memory预览界面 */
    MEMORY_PREVIEW,
    /** 保存Address界面 */
    SAVED_ADDRESS
}

/**
 * Address Actions对话框 - 使用 RecyclerView 实现
 */
class AddressActionDialog(
    context: Context,
    private val notification: NotificationOverlay,
    private val clipboardManager: ClipboardManager,
    private val address: Long,
    private val value: String,
    private val valueType: DisplayValueType,
    private val coroutineScope: CoroutineScope,
    private val callbacks: Callbacks,
    private val source: AddressActionSource = AddressActionSource.SEARCH,
    private val memoryRange: MemoryRange? = null,
    private val displayFormats: List<MemoryDisplayFormat>? = null
) : BaseDialog(context) {

    companion object {
        // ARM64 用户态Address仅使用低 48 位；高位可能带有 MTE/TBI tag。
        private const val ADDRESS_MASK_48BIT = 0x0000FFFFFFFFFFFFuL
    }

    /**
     * 回调接口
     */
    interface Callbacks {
        fun onShowOffsetCalculator(address: Long)
        fun onJumpToAddress(address: Long)
        fun onJumpToPointer(fromAddress: Long, toAddress: Long) {
            onJumpToAddress(toAddress)
        }
    }

    /**
     * 操作项数据类
     */
    private data class ActionItem(
        val title: String,
        val icon: Int,
        val action: () -> Unit
    )

    private val hexFormat = HexFormat { upperCase = true }

    private fun stripMteTag(address: Long): Long {
        return (address.toULong() and ADDRESS_MASK_48BIT).toLong()
    }

    private fun formatUnsignedHex(address: Long): String {
        return java.lang.Long.toUnsignedString(address, 16).uppercase()
    }

    @SuppressLint("SetTextI18n")
    override fun setupDialog() {
        val binding = DialogAddressActionRvBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.getDialogOpacity()
        binding.rootContainer.background?.alpha = (opacity * 255).toInt()

        binding.addressInfoText.text = "Address: 0x${address.toString(16).uppercase()}"
        binding.valueInfoText.text = "Value: $value (${valueType.displayName})"

        // 异步构建操作列表（需要读取Memory）
        coroutineScope.launch {
            val actions = buildActionList()
            withContext(Dispatchers.Main) {
                binding.actionRecyclerView.apply {
                    layoutManager = LinearLayoutManager(context)
                    adapter = ActionAdapter(actions)
                    addItemDecoration(DividerItemDecoration())
                }
            }
        }

        binding.btnCancel.setOnClickListener { dismiss() }
    }

    private fun getHexString(): String {
        return try {
            val bytes = ValueTypeUtils.parseExprToBytes(value, valueType)
            bytes.toHexString(hexFormat)
        } catch (e: Exception) {
            (value.toLongOrNull() ?: 0).toHexString(hexFormat)
        }
    }

    private fun getReverseHexString(): String {
        return try {
            val bytes = ValueTypeUtils.parseExprToBytes(value, valueType)
            bytes.reversedArray().toHexString(hexFormat)
        } catch (e: Exception) {
            (value.toLongOrNull() ?: 0).toHexString(hexFormat).reversed()
        }
    }

    private suspend fun getPointerAddress(): Long? {
        val maxSize = 8
        val memoryBytes = withContext(Dispatchers.IO) {
            try {
                WuwaDriver.readMemory(address, maxSize)
            } catch (e: Exception) {
                null
            }
        } ?: return null

        val buffer = ByteBuffer.wrap(memoryBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(0)
        return stripMteTag(buffer.long)
    }

    /**
     * 构建操作列表
     */
    private suspend fun buildActionList(): List<ActionItem> {
        val hexString = getHexString()
        val reverseHexString = getReverseHexString()
        val pointerAddress = getPointerAddress()

        val actions = mutableListOf(
            ActionItem("Offset Calculator", R.drawable.calculate_24px) {
                dismiss()
                callbacks.onShowOffsetCalculator(address)
            }
        )

        // Memory预览界面已经在查看该Address，不需要"Go to此Address"
        if (source != AddressActionSource.MEMORY_PREVIEW) {
            actions.add(ActionItem(
                "Go to this address: ${"%X".format(address)}",
                R.drawable.icon_arrow_right_alt_24px
            ) {
                dismiss()
                callbacks.onJumpToAddress(address)
            })
        }

        actions.addAll(listOf(
            ActionItem(
                "Jump to pointer: ${formatUnsignedHex(pointerAddress ?: 0L)}",
                R.drawable.icon_arrow_right_alt_24px
            ) {
                dismiss()
                val addr = pointerAddress ?: run {
                    notification.showError("Cannot jump because the pointer is invalid")
                    return@ActionItem
                }
                callbacks.onJumpToPointer(address, addr)
                notification.showSuccess("Jumped to pointer: 0x${formatUnsignedHex(addr)}")
            },
            ActionItem("Copy this address: ${"%X".format(address)}", R.drawable.content_copy_24px) {
                copyToClipboard("address", address.toString(16).uppercase(), "Address")
            },
            ActionItem("Copy this value: $value", R.drawable.content_copy_24px) {
                copyToClipboard("value", value, "value")
            },
            ActionItem("Copy hex value: $hexString", R.drawable.content_copy_24px) {
                copyToClipboard("hex_value", hexString, "hex")
            },
            ActionItem("Copy reversed hex: $reverseHexString", R.drawable.content_copy_24px) {
                copyToClipboard("reverse_hex_value", reverseHexString, "reversed hex")
            }
        ))

        // 根据Memory预览界面的 displayFormats 添加额外Copy选项
        if (source == AddressActionSource.MEMORY_PREVIEW && displayFormats != null) {
            addFormatBasedActions(actions)
        }

        // 实时监视选项
        if (source == AddressActionSource.SAVED_ADDRESS) {
            actions.add(ActionItem("Real-time monitor", R.drawable.icon_visibility_24px) {
                dismiss()
                showRealtimeMonitor()
            })
        }

        return actions
    }

    /**
     * 根据 displayFormats 添加额外的Copy选项
     */
    private suspend fun addFormatBasedActions(actions: MutableList<ActionItem>) {
        val formats = displayFormats ?: return

        // 读取足够的Memory数据用于各种格式转换
        val maxSize = 8 // 最大读取8字节（QWORD/Double）
        val memoryBytes = withContext(Dispatchers.IO) {
            try {
                WuwaDriver.readMemory(address, maxSize)
            } catch (e: Exception) {
                null
            }
        } ?: return

        val buffer = ByteBuffer.wrap(memoryBytes).order(ByteOrder.LITTLE_ENDIAN)

        for (format in formats) {
            when (format) {
                MemoryDisplayFormat.BYTE -> {
                    val byteVal = memoryBytes[0] // 保持有符号，与Memory预览显示一致
                    actions.add(ActionItem(
                        "Copy Byte: $byteVal",
                        R.drawable.content_copy_24px
                    ) { copyToClipboard("byte", byteVal.toString(), "Byte") })
                }

                MemoryDisplayFormat.WORD -> {
                    buffer.position(0)
                    val wordVal = buffer.short // 保持有符号，与Memory预览显示一致
                    actions.add(ActionItem(
                        "Copy Word: $wordVal",
                        R.drawable.content_copy_24px
                    ) { copyToClipboard("word", wordVal.toString(), "Word") })
                }

                MemoryDisplayFormat.DWORD -> {
                    buffer.position(0)
                    val dwordVal = buffer.int
                    actions.add(ActionItem(
                        "Copy Dword: $dwordVal",
                        R.drawable.content_copy_24px
                    ) { copyToClipboard("dword", dwordVal.toString(), "Dword") })
                }

                MemoryDisplayFormat.QWORD -> {
                    buffer.position(0)
                    val qwordVal = buffer.long
                    actions.add(ActionItem(
                        "Copy Qword: $qwordVal",
                        R.drawable.content_copy_24px
                    ) { copyToClipboard("qword", qwordVal.toString(), "Qword") })
                }

                MemoryDisplayFormat.FLOAT -> {
                    buffer.position(0)
                    val floatVal = buffer.float
                    val floatStr = "%.6g".format(floatVal)
                    actions.add(ActionItem(
                        "Copy Float: $floatStr",
                        R.drawable.content_copy_24px
                    ) { copyToClipboard("float", floatStr, "Float") })
                }

                MemoryDisplayFormat.DOUBLE -> {
                    buffer.position(0)
                    val doubleVal = buffer.double
                    val doubleStr = "%.10g".format(doubleVal)
                    actions.add(ActionItem(
                        "Copy Double: $doubleStr",
                        R.drawable.content_copy_24px
                    ) { copyToClipboard("double", doubleStr, "Double") })
                }

                MemoryDisplayFormat.HEX_BIG_ENDIAN -> {
                    val hexBE = memoryBytes.reversedArray().toHexString(hexFormat)
                    actions.add(ActionItem(
                        "Copy big-endian hex: $hexBE",
                        R.drawable.content_copy_24px
                    ) { copyToClipboard("hex_be", hexBE, "big-endian hex") })
                }

                MemoryDisplayFormat.HEX_LITTLE_ENDIAN -> {
                    val hexLE = memoryBytes.toHexString(hexFormat)
                    actions.add(ActionItem(
                        "Copy little-endian hex: $hexLE",
                        R.drawable.content_copy_24px
                    ) { copyToClipboard("hex_le", hexLE, "little-endian hex") })
                }

                MemoryDisplayFormat.ARM32 -> {
                    val arm32Asm = withContext(Dispatchers.IO) {
                        try {
                            val bytes = memoryBytes.copyOf(4)
                            val results = Disassembler.disassembleARM32(bytes, address, count = 1)
                            if (results.isNotEmpty()) "${results[0].mnemonic} ${results[0].operands}" else null
                        } catch (e: Exception) { null }
                    }
                    if (arm32Asm != null) {
                        actions.add(ActionItem(
                            "Copy ARM32: $arm32Asm",
                            R.drawable.content_copy_24px
                        ) { copyToClipboard("arm32", arm32Asm, "ARM32") })
                    }
                }

                MemoryDisplayFormat.THUMB -> {
                    val thumbAsm = withContext(Dispatchers.IO) {
                        try {
                            val bytes = memoryBytes.copyOf(2)
                            val results = Disassembler.disassembleThumb(bytes, address, count = 1)
                            if (results.isNotEmpty()) "${results[0].mnemonic} ${results[0].operands}" else null
                        } catch (e: Exception) { null }
                    }
                    if (thumbAsm != null) {
                        actions.add(ActionItem(
                            "Copy Thumb: $thumbAsm",
                            R.drawable.content_copy_24px
                        ) { copyToClipboard("thumb", thumbAsm, "Thumb") })
                    }
                }

                MemoryDisplayFormat.ARM64 -> {
                    val arm64Asm = withContext(Dispatchers.IO) {
                        try {
                            val bytes = memoryBytes.copyOf(4)
                            val results = Disassembler.disassembleARM64(bytes, address, count = 1)
                            if (results.isNotEmpty()) "${results[0].mnemonic} ${results[0].operands}" else null
                        } catch (e: Exception) { null }
                    }
                    if (arm64Asm != null) {
                        actions.add(ActionItem(
                            "Copy ARM64: $arm64Asm",
                            R.drawable.content_copy_24px
                        ) { copyToClipboard("arm64", arm64Asm, "ARM64") })
                    }
                }

                MemoryDisplayFormat.ARM64_PSEUDO -> {
                    val pseudoCode = withContext(Dispatchers.IO) {
                        try {
                            val bytes = memoryBytes.copyOf(4)
                            val results = Disassembler.generatePseudoCode(
                                Disassembler.Architecture.ARM64, bytes, address, count = 1
                            )
                            if (results.isNotEmpty()) {
                                results[0].pseudoCode ?: "${results[0].mnemonic} ${results[0].operands}"
                            } else null
                        } catch (e: Exception) { null }
                    }
                    if (pseudoCode != null) {
                        actions.add(ActionItem(
                            "Copy pseudocode: $pseudoCode",
                            R.drawable.content_copy_24px
                        ) { copyToClipboard("pseudo", pseudoCode, "pseudocode") })
                    }
                }

                MemoryDisplayFormat.STRING_EXPR -> {
                    val strExpr = buildString {
                        for (b in memoryBytes) {
                            val c = b.toInt() and 0xFF
                            if (c in 0x20..0x7E) append(c.toChar())
                            else append('.')
                        }
                    }
                    actions.add(ActionItem(
                        "Copy string: $strExpr",
                        R.drawable.content_copy_24px
                    ) { copyToClipboard("string", strExpr, "string") })
                }

                MemoryDisplayFormat.UTF16_LE -> {
                    val utf16Str = try {
                        String(memoryBytes, Charsets.UTF_16LE).takeWhile { it != '\u0000' }
                    } catch (e: Exception) { "" }
                    if (utf16Str.isNotEmpty()) {
                        actions.add(ActionItem(
                            "Copy UTF16: $utf16Str",
                            R.drawable.content_copy_24px
                        ) { copyToClipboard("utf16", utf16Str, "UTF16") })
                    }
                }
            }
        }
    }

    private fun copyToClipboard(label: String, text: String, displayName: String) {
        val clip = ClipData.newPlainText(label, text)
        clipboardManager.setPrimaryClip(clip)
        notification.showSuccess("Copied $displayName: $text")
        dismiss()
    }

    private fun showRealtimeMonitor() {
        val savedAddress = SavedAddress(
            address = address,
            name = "",
            valueType = valueType.nativeId,
            value = value,
            isFrozen = false,
            range = memoryRange ?: MemoryRange.An
        )
        RealtimeMonitorOverlay(dialog.context, listOf(savedAddress)).show()
        notification.showSuccess("Added to real-time monitor")
    }

    fun getSource(): AddressActionSource = source

    private inner class ActionAdapter(
        private val actions: List<ActionItem>
    ) : RecyclerView.Adapter<ActionAdapter.ViewHolder>() {

        inner class ViewHolder(
            private val binding: ItemAddressActionBinding
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: ActionItem) {
                binding.actionTitle.text = item.title
                binding.actionIcon.setImageResource(item.icon)
                binding.itemContainer.setOnClickListener { item.action() }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemAddressActionBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(actions[position])
        }

        override fun getItemCount(): Int = actions.size
    }

    private class DividerItemDecoration : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: android.view.View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            if (position != parent.adapter?.itemCount?.minus(1)) {
                outRect.bottom = 1
            }
        }
    }
}
