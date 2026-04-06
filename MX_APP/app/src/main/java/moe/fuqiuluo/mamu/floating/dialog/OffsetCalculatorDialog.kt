package moe.fuqiuluo.mamu.floating.dialog

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.annotation.ColorInt
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.data.settings.keyboardType
import moe.fuqiuluo.mamu.databinding.DialogOffsetCalculatorBinding
import moe.fuqiuluo.mamu.driver.Disassembler
import moe.fuqiuluo.mamu.floating.data.local.InputHistoryManager
import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry
import moe.fuqiuluo.mamu.floating.data.model.FormattedValue
import moe.fuqiuluo.mamu.floating.data.model.MemoryDisplayFormat
import moe.fuqiuluo.mamu.floating.event.FloatingEventBus
import moe.fuqiuluo.mamu.floating.event.NavigateToMemoryAddressEvent
import moe.fuqiuluo.mamu.floating.event.UIActionEvent
import moe.fuqiuluo.mamu.pp.ExecutionException
import moe.fuqiuluo.mamu.pp.ExecutionResult
import moe.fuqiuluo.mamu.pp.ParseException
import moe.fuqiuluo.mamu.pp.PtrPathExecutor
import moe.fuqiuluo.mamu.pp.PtrPathParser
import moe.fuqiuluo.mamu.pp.PtrPathTokenizer
import moe.fuqiuluo.mamu.widget.BuiltinKeyboard
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Offset Calculator对话框
 */
class OffsetCalculatorDialog(
    context: Context,
    private val notification: NotificationOverlay,
    private val clipboardManager: ClipboardManager,
    private val initialBaseAddress: Long? = null
) : BaseDialog(context) {
    private var currentFocusedInput: EditText? = null
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var executionResult: ExecutionResult? = null
    private var calculateJob: Job? = null
    private lateinit var binding: DialogOffsetCalculatorBinding

    // History记录（静态保存，跨实例共享）
    companion object {
        private val expressionHistory = mutableListOf<HistoryEntry>()
        private const val MAX_HISTORY_SIZE = 30

        fun clearHistory() {
            expressionHistory.clear()
        }
    }

    data class HistoryEntry(
        val baseAddress: Long,
        val expression: String,
        val finalAddress: Long,
        val hexMode: Boolean
    )

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    override fun setupDialog() {
        binding = DialogOffsetCalculatorBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        // 应用透明度设置
        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.getDialogOpacity()
        binding.rootContainer.background?.alpha = (opacity * 255).toInt()

        // 配置键盘方向
        val isPortrait =
            context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        binding.builtinKeyboard.setScreenOrientation(isPortrait)

        // 根据配置决定是否禁用系统输入法
        val useBuiltinKeyboard = mmkv.keyboardType == 0
        if (useBuiltinKeyboard) {
            binding.builtinKeyboard.visibility = View.VISIBLE
            binding.divider.visibility = View.VISIBLE
        } else {
            binding.inputBaseAddress.showSoftInputOnFocus = true
            binding.inputExpression.showSoftInputOnFocus = true
            binding.builtinKeyboard.visibility = View.GONE
            binding.divider.visibility = View.GONE
        }

        // 设置初始基址（如果提供）
        if (initialBaseAddress != null) {
            val addressText = "%X".format(initialBaseAddress)
            binding.inputBaseAddress.setText(addressText)
            binding.inputBaseAddress.setSelection(addressText.length)  // 光标移To末尾
            binding.resultAddress.text = "Result: 0x" + initialBaseAddress.toHexString()
        } else {
            // Restore上次输入的基址并Select All
            InputHistoryManager.restoreAndSelectAll(
                binding.inputBaseAddress,
                InputHistoryManager.Keys.OFFSET_CALCULATOR_BASE
            )
            binding.resultAddress.text = "Result: 0x0"
        }
        
        // Restore上次输入的偏移表达式并Select All
        InputHistoryManager.restoreAndSelectAll(
            binding.inputExpression,
            InputHistoryManager.Keys.OFFSET_CALCULATOR_OFFSET
        )

        // RestoreHexadecimal模式勾选状态
        val savedHexMode = InputHistoryManager.get(InputHistoryManager.Keys.OFFSET_CALCULATOR_HEX_MODE)
        binding.cbHexMode.isChecked = savedHexMode == "true"

        val spannable = SpannableStringBuilder()
        spannable.appendColored("-h; ", MemoryDisplayFormat.HEX_BIG_ENDIAN.textColor)
        spannable.appendColored("-r; ", MemoryDisplayFormat.HEX_LITTLE_ENDIAN.textColor)
        spannable.appendColored("-; ", MemoryDisplayFormat.STRING_EXPR.textColor)
        spannable.appendColored("-; ", MemoryDisplayFormat.UTF16_LE.textColor)
        spannable.appendColored("-D; ", MemoryDisplayFormat.DWORD.textColor)
        spannable.appendColored("-F; ", MemoryDisplayFormat.FLOAT.textColor)
        spannable.appendColored("-E; ", MemoryDisplayFormat.DOUBLE.textColor)
        spannable.appendColored("-W; ", MemoryDisplayFormat.WORD.textColor)
        spannable.appendColored("-B; ", MemoryDisplayFormat.BYTE.textColor)
        spannable.appendColored("-Q; ", MemoryDisplayFormat.QWORD.textColor)
        binding.resultText.text = spannable

        // 设置输入框焦点监听
        setupInputFocus(binding)

        // 在 setupInputFocus 之后调用，确保包装已有的 OnFocusChangeListener
        if (useBuiltinKeyboard) {
            suppressSystemKeyboard(binding.inputBaseAddress, binding.inputExpression)
        }

        // 设置键盘监听器
        setupBuiltinKeyboard(binding)

        // 监听输入变化，实时计算
        setupTextWatchers(binding)

        // 监听Hexadecimal模式切换，触发重新计算
        binding.cbHexMode.setOnCheckedChangeListener { _, isChecked ->
            InputHistoryManager.save(InputHistoryManager.Keys.OFFSET_CALCULATOR_HEX_MODE, isChecked.toString())
            calculateJob?.cancel()
            calculateJob = coroutineScope.launch(Dispatchers.Default) {
                performCalculation(binding)
            }
        }

        // 触发初始计算（RestoreHistory输入后需要计算一次）
        calculateJob = coroutineScope.launch(Dispatchers.Default) {
            performCalculation(binding)
        }

        // Copy按钮
        binding.btnCopy.setOnClickListener {
            copyResultToClipboard(binding)
        }

        // 跳转按钮
        binding.btnGoto.setOnClickListener {
            jumpToFinalAddress()
        }

        // Cancel按钮
        binding.btnCancel.setOnClickListener {
            // 保存输入内容
            InputHistoryManager.saveFromEditText(binding.inputBaseAddress, InputHistoryManager.Keys.OFFSET_CALCULATOR_BASE)
            InputHistoryManager.saveFromEditText(binding.inputExpression, InputHistoryManager.Keys.OFFSET_CALCULATOR_OFFSET)
            onCancel?.invoke()
            dialog.dismiss()
        }
    }

    private fun setupInputFocus(binding: DialogOffsetCalculatorBinding) {
        val inputs = listOf(
            binding.inputBaseAddress,
            binding.inputExpression
        )

        inputs.forEach { input ->
            input.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    currentFocusedInput = view as EditText
                }
            }
        }

        // 默认焦点在基址输入框
        currentFocusedInput = binding.inputExpression
        binding.inputExpression.requestFocus()
    }

    private fun setupBuiltinKeyboard(binding: DialogOffsetCalculatorBinding) {
        binding.builtinKeyboard.listener = object : BuiltinKeyboard.KeyboardListener {
            override fun onKeyInput(key: String) {
                val input = currentFocusedInput ?: return
                val editable = input.text ?: return
                val selectionStart = input.selectionStart
                val selectionEnd = input.selectionEnd
                editable.replace(selectionStart, selectionEnd, key)
                // 输入后将光标移动To新插入文本的末尾，Cancel选择状态
                val newCursorPos = selectionStart + key.length
                input.setSelection(newCursorPos)
            }

            override fun onDelete() {
                val input = currentFocusedInput ?: return
                val editable = input.text ?: return
                val selectionStart = input.selectionStart
                val selectionEnd = input.selectionEnd

                if (selectionStart != selectionEnd) {
                    editable.delete(selectionStart, selectionEnd)
                } else if (selectionStart > 0) {
                    editable.delete(selectionStart - 1, selectionStart)
                }
            }

            override fun onSelectAll() {
                currentFocusedInput?.selectAll()
            }

            override fun onMoveLeft() {
                val input = currentFocusedInput ?: return
                val cursorPos = input.selectionStart
                if (cursorPos > 0) {
                    input.setSelection(cursorPos - 1)
                }
            }

            override fun onMoveRight() {
                val input = currentFocusedInput ?: return
                val cursorPos = input.selectionStart
                if (cursorPos < input.text.length) {
                    input.setSelection(cursorPos + 1)
                }
            }

            override fun onHistory() {
                showHistoryPopup()
            }

            override fun onPaste() {
                val clip = clipboardManager.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString() ?: ""
                    val input = currentFocusedInput ?: return
                    val editable = input.text ?: return
                    val selectionStart = input.selectionStart
                    val selectionEnd = input.selectionEnd
                    editable.replace(selectionStart, selectionEnd, text)
                }
            }
        }
    }

    /**
     * 监听输入变化，实时计算表达式
     */
    private fun setupTextWatchers(binding: DialogOffsetCalculatorBinding) {
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // 延迟执行，避免频繁计算
                calculateJob?.cancel()
                calculateJob = coroutineScope.launch(Dispatchers.Default) {
                    performCalculation(binding)
                }
            }
        }

        binding.inputBaseAddress.addTextChangedListener(textWatcher)
        binding.inputExpression.addTextChangedListener(textWatcher)
    }

    /**
     * 执行表达式计算
     */
    private suspend fun performCalculation(binding: DialogOffsetCalculatorBinding) {
        val baseAddressStr = binding.inputBaseAddress.text.toString().trim()
        val expressionStr = binding.inputExpression.text.toString().trim()
        val hexMode = binding.cbHexMode.isChecked

        try {
            val result = withContext(Dispatchers.IO) {
                // 解析基址
                val baseAddress = try {
                    baseAddressStr.toULong(16).toLong()
                } catch (e: Exception) {
                    e.printStackTrace()
                    throw ParseException("Invalid base address format: $baseAddressStr")
                }

                // 词法分析
                val tokens = PtrPathTokenizer.tokenize(expressionStr, hexMode)
                // 语法解析
                val ast = PtrPathParser.parse(tokens)
                // 执行
                val executor = PtrPathExecutor(baseAddress)
                executor.execute(ast)
            }

            // 显示结果
            executionResult = result
            withContext(Dispatchers.Main) {
                displayResult(binding, result)
            }
        } catch (e: ParseException) {
            withContext(Dispatchers.Main) {
                // 解析Error
                displayError(binding, "Parse error: ${e.message}")
            }
        } catch (e: ExecutionException) {
            withContext(Dispatchers.Main) {
                // 执行Error
                displayError(binding, "Execution error: ${e.message}")
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                // 其他Error
                displayError(binding, "Error: ${e.message}")
            }
        }
    }

    /**
     * 显示计算结果
     */
    private fun displayResult(binding: DialogOffsetCalculatorBinding, result: ExecutionResult) {
        binding.resultText.visibility = View.VISIBLE

        if (!result.success) {
            binding.resultAddress.text = "Result: " + (result.errorMessage ?: "Expression error")
        } else {
            binding.resultAddress.text = "Result: 0x" + result.finalAddress.toHexString()
        }

        val spannable = SpannableStringBuilder()
        if (!result.success) {
            spannable.appendColored("-h; ", MemoryDisplayFormat.HEX_BIG_ENDIAN.textColor)
            spannable.appendColored("-r; ", MemoryDisplayFormat.HEX_LITTLE_ENDIAN.textColor)
            spannable.appendColored("-; ", MemoryDisplayFormat.STRING_EXPR.textColor)
            spannable.appendColored("-; ", MemoryDisplayFormat.UTF16_LE.textColor)
            spannable.appendColored("-D; ", MemoryDisplayFormat.DWORD.textColor)
            spannable.appendColored("-F; ", MemoryDisplayFormat.FLOAT.textColor)
            spannable.appendColored("-E; ", MemoryDisplayFormat.DOUBLE.textColor)
            spannable.appendColored("-W; ", MemoryDisplayFormat.WORD.textColor)
            spannable.appendColored("-B; ", MemoryDisplayFormat.BYTE.textColor)
            spannable.appendColored("-Q; ", MemoryDisplayFormat.QWORD.textColor)
            return
        }

        // 构建带颜色的结果文本
        val formats = listOf(
            MemoryDisplayFormat.HEX_BIG_ENDIAN,
            MemoryDisplayFormat.HEX_LITTLE_ENDIAN,
            MemoryDisplayFormat.STRING_EXPR,
            MemoryDisplayFormat.UTF16_LE,
            MemoryDisplayFormat.DWORD,
            MemoryDisplayFormat.FLOAT,
            MemoryDisplayFormat.DOUBLE,
            MemoryDisplayFormat.WORD,
            MemoryDisplayFormat.BYTE,
            MemoryDisplayFormat.QWORD,
        )

        val bytes = result.bytes
        if (bytes == null) {
            spannable.appendColored("-h; ", MemoryDisplayFormat.HEX_BIG_ENDIAN.textColor)
            spannable.appendColored("-r; ", MemoryDisplayFormat.HEX_LITTLE_ENDIAN.textColor)
            spannable.appendColored("-; ", MemoryDisplayFormat.STRING_EXPR.textColor)
            spannable.appendColored("-; ", MemoryDisplayFormat.UTF16_LE.textColor)
            spannable.appendColored("-D; ", MemoryDisplayFormat.DWORD.textColor)
            spannable.appendColored("-F; ", MemoryDisplayFormat.FLOAT.textColor)
            spannable.appendColored("-E; ", MemoryDisplayFormat.DOUBLE.textColor)
            spannable.appendColored("-W; ", MemoryDisplayFormat.WORD.textColor)
            spannable.appendColored("-B; ", MemoryDisplayFormat.BYTE.textColor)
            spannable.appendColored("-Q; ", MemoryDisplayFormat.QWORD.textColor)
        } else run {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val hexByteSize = MemoryDisplayFormat.calculateHexByteSize(formats)
            val hexBuilder = StringBuilder(hexByteSize * 2)

            formats.forEach {
                val value = parseValueFast(
                    buffer,
                    result.finalAddress,
                    it,
                    hexByteSize,
                    hexBuilder,
                    result.regions
                )
                spannable.appendColored(
                    "${value.value}${
                        if (it.appendCode) it.code else ""
                    }; ", value.color ?: it.textColor
                )
            }
        }

        binding.resultText.text = spannable
        binding.resultText.isEnabled = true
        binding.btnCopy.isEnabled = true
        binding.btnGoto.isEnabled = result.finalAddress != 0L
    }

    /**
     * 二分查找获取可执行Memory区域（用于 HEX 值颜色判断）
     */
    private fun findExecutableRegionBinary(
        address: Long,
        sortedRegions: List<DisplayMemRegionEntry>
    ): DisplayMemRegionEntry? {
        if (sortedRegions.isEmpty()) return null

        var low = 0
        var high = sortedRegions.lastIndex

        while (low <= high) {
            val mid = (low + high) ushr 1
            val region = sortedRegions[mid]

            when {
                address < region.start -> high = mid - 1
                address >= region.end -> low = mid + 1
                else -> return if (region.isExecutable) region else null
            }
        }
        return null
    }

    /**
     * 快速解析Memory值
     */
    private fun parseValueFast(
        buffer: ByteBuffer,
        address: Long,
        format: MemoryDisplayFormat,
        hexByteSize: Int,
        hexBuilder: StringBuilder,
        sortedRegions: List<DisplayMemRegionEntry>
    ): FormattedValue {
        val startPos = buffer.position()
        val hexChars = "0123456789ABCDEF".toCharArray()

        try {
            return when (format) {
                MemoryDisplayFormat.HEX_LITTLE_ENDIAN, MemoryDisplayFormat.HEX_BIG_ENDIAN -> {
                    if (buffer.remaining() < hexByteSize) {
                        return FormattedValue(format, "---", Color.WHITE)
                    }

                    // 快速Hexadecimal转换
                    hexBuilder.setLength(0)
                    val bytes = ByteArray(hexByteSize)
                    buffer.get(bytes)

                    if (format == MemoryDisplayFormat.HEX_LITTLE_ENDIAN) {
                        for (b in bytes) {
                            hexBuilder.append(hexChars[(b.toInt() shr 4) and 0x0F])
                            hexBuilder.append(hexChars[b.toInt() and 0x0F])
                        }
                    } else {
                        for (i in bytes.lastIndex downTo 0) {
                            val b = bytes[i]
                            hexBuilder.append(hexChars[(b.toInt() shr 4) and 0x0F])
                            hexBuilder.append(hexChars[b.toInt() and 0x0F])
                        }
                    }

                    // 将Hexadecimal值解析为Address（快速版本）
                    buffer.position(startPos)
                    val valueAsAddress: Long? = when (hexByteSize) {
                        8 -> buffer.long.takeIf { it >= 0 }
                        4 -> buffer.int.toLong() and 0xFFFFFFFFL
                        2 -> buffer.short.toLong() and 0xFFFFL
                        else -> null
                    }

                    // 使用二分查找获取颜色
                    val color = if (valueAsAddress != null) {
                        findExecutableRegionBinary(valueAsAddress, sortedRegions)?.range?.color
                            ?: Color.WHITE
                    } else {
                        Color.WHITE
                    }

                    FormattedValue(format, hexBuilder.toString(), color)
                }

                MemoryDisplayFormat.DWORD -> {
                    if (buffer.remaining() < 4) return FormattedValue(format, "---")
                    FormattedValue(format, buffer.int.toString())
                }

                MemoryDisplayFormat.QWORD -> {
                    if (buffer.remaining() < 8) return FormattedValue(format, "---")
                    FormattedValue(format, buffer.long.toString())
                }

                MemoryDisplayFormat.WORD -> {
                    if (buffer.remaining() < 2) return FormattedValue(format, "---")
                    FormattedValue(format, buffer.short.toString())
                }

                MemoryDisplayFormat.BYTE -> {
                    if (buffer.remaining() < 1) return FormattedValue(format, "---")
                    FormattedValue(format, buffer.get().toString())
                }

                MemoryDisplayFormat.FLOAT -> {
                    if (buffer.remaining() < 4) return FormattedValue(format, "---")
                    FormattedValue(format, formatFloatingPoint(buffer.float.toDouble(), 6))
                }

                MemoryDisplayFormat.DOUBLE -> {
                    if (buffer.remaining() < 8) return FormattedValue(format, "---")
                    FormattedValue(format, formatFloatingPoint(buffer.double, 10))
                }

                MemoryDisplayFormat.UTF16_LE -> {
                    if (buffer.remaining() < 2) return FormattedValue(format, "---")
                    val charValue = buffer.short.toInt().toChar()
                    val displayChar = if (charValue.isLetterOrDigit() || charValue.isWhitespace()) {
                        charValue.toString()
                    } else {
                        "."
                    }
                    FormattedValue(format, "\"$displayChar\"")
                }

                MemoryDisplayFormat.STRING_EXPR -> {
                    if (buffer.remaining() < 1) return FormattedValue(format, "---")
                    val bytes = ByteArray(min(4, buffer.remaining()))
                    buffer.get(bytes)
                    val displayString = buildString(bytes.size) {
                        for (b in bytes) {
                            append(if (b in 32..126) b.toInt().toChar() else '.')
                        }
                    }
                    FormattedValue(format, "'$displayString'")
                }

                MemoryDisplayFormat.ARM32 -> {
                    if (buffer.remaining() < 4) return FormattedValue(format, "---")
                    val bytes = ByteArray(4)
                    buffer.get(bytes)
                    try {
                        val results = Disassembler.disassembleARM32(bytes, address, count = 1)
                        if (results.isNotEmpty()) {
                            val insn = results[0]
                            FormattedValue(format, "${insn.mnemonic} ${insn.operands}")
                        } else {
                            FormattedValue(format, "???")
                        }
                    } catch (e: Exception) {
                        FormattedValue(format, "err")
                    }
                }

                MemoryDisplayFormat.THUMB -> {
                    if (buffer.remaining() < 2) return FormattedValue(format, "---")
                    val bytes = ByteArray(2)
                    buffer.get(bytes)
                    try {
                        val results = Disassembler.disassembleThumb(bytes, address, count = 1)
                        if (results.isNotEmpty()) {
                            val insn = results[0]
                            FormattedValue(format, "${insn.mnemonic} ${insn.operands}")
                        } else {
                            FormattedValue(format, "???")
                        }
                    } catch (e: Exception) {
                        FormattedValue(format, "err")
                    }
                }

                MemoryDisplayFormat.ARM64 -> {
                    if (buffer.remaining() < 4) return FormattedValue(format, "---")
                    val bytes = ByteArray(4)
                    buffer.get(bytes)
                    try {
                        val results = Disassembler.disassembleARM64(bytes, address, count = 1)
                        if (results.isNotEmpty()) {
                            val insn = results[0]
                            FormattedValue(format, "${insn.mnemonic} ${insn.operands}")
                        } else {
                            FormattedValue(format, "???")
                        }
                    } catch (e: Exception) {
                        FormattedValue(format, "err")
                    }
                }

                MemoryDisplayFormat.ARM64_PSEUDO -> {
                    if (buffer.remaining() < 4) return FormattedValue(format, "---")
                    val bytes = ByteArray(4)
                    buffer.get(bytes)
                    try {
                        val results = Disassembler.generatePseudoCode(
                            Disassembler.Architecture.ARM64,
                            bytes,
                            address,
                            count = 1
                        )
                        if (results.isNotEmpty()) {
                            val insn = results[0]
                            // 优先显示伪代码，如果没有则显示汇编
                            val displayText = insn.pseudoCode ?: "${insn.mnemonic} ${insn.operands}"
                            FormattedValue(format, displayText)
                        } else {
                            FormattedValue(format, "???")
                        }
                    } catch (e: Exception) {
                        FormattedValue(format, "err")
                    }
                }
            }
        } finally {
            buffer.position(startPos)
        }
    }

    /**
     * 显示Error信息（红色）
     */
    private fun displayError(binding: DialogOffsetCalculatorBinding, errorMessage: String) {
        binding.resultText.visibility = View.VISIBLE

        val spannable = SpannableStringBuilder()
        spannable.append(errorMessage)
        spannable.setSpan(
            ForegroundColorSpan(0xFFF44336.toInt()), // 红色
            0,
            errorMessage.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        binding.resultText.text = spannable
        binding.btnCopy.isEnabled = false
        binding.btnGoto.isEnabled = false
    }

    /**
     * Copy结果To剪贴板
     */
    private fun copyResultToClipboard(binding: DialogOffsetCalculatorBinding) {
        val result = executionResult ?: return

        val baseAddressStr = binding.inputBaseAddress.text.toString().trim()
        val baseAddress = try {
            baseAddressStr.toULong(16).toLong()
        } catch (e: Exception) {
            0L
        }

        val text = buildString {
            append("Base: 0x%X\n".format(baseAddress))
            append("Expression: ${binding.inputExpression.text}\n")
            append("Final address: 0x%X\n".format(result.finalAddress))

            if (result.memoryValues != null) {
                append("\nMemory values:\n")
                result.memoryValues.forEach { (type, value) ->
                    append("$type: $value\n")
                }
            }
        }

        val clip = ClipData.newPlainText("Offset calculation result", text)
        clipboardManager.setPrimaryClip(clip)
        notification.showSuccess("Copied to clipboard")
    }

    private fun SpannableStringBuilder.appendColored(
        text: String,
        @ColorInt color: Int
    ): SpannableStringBuilder {
        val start = length
        append(text)
        setSpan(ForegroundColorSpan(color), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return this
    }

    /**
     * 格式化浮点数，对于极大/极小值使用科学计数法
     */
    private fun formatFloatingPoint(value: Double, maxPrecision: Int): String {
        // 处理特殊值
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Inf" else "-Inf"
        if (value == 0.0) return "0.0"

        val absValue = kotlin.math.abs(value)

        // 极大或极小值使用科学计数法
        return if (absValue >= 1e7 || (absValue < 1e-4 && absValue > 0)) {
            "%.${maxPrecision}g".format(value)
        } else {
            // 普通Range使用定点格式，去除尾部多余的0
            "%.${maxPrecision}f".format(value).trimEnd('0').let {
                if (it.endsWith('.')) it + "0" else it
            }
        }
    }

    /**
     * 显示History记录弹窗
     */
    private fun showHistoryPopup() {
        if (expressionHistory.isEmpty()) {
            notification.showWarning("No history yet")
            return
        }

        OffsetCalculatorHistoryDialog(
            context = context,
            history = expressionHistory.toList(),
            onItemSelected = { entry ->
                // 填充选中的History记录
                binding.inputBaseAddress.setText("%X".format(entry.baseAddress))
                binding.inputExpression.setText(entry.expression)
                binding.cbHexMode.isChecked = entry.hexMode
                // 光标移To表达式末尾
                binding.inputExpression.setSelection(entry.expression.length)
            }
        ).show()
    }

    /**
     * 添加ToHistory记录
     */
    private fun addToHistory(baseAddress: Long, expression: String, finalAddress: Long, hexMode: Boolean) {
        if (expression.isBlank()) return
        
        val entry = HistoryEntry(baseAddress, expression, finalAddress, hexMode)
        // 移除相同表达式的旧记录
        expressionHistory.removeAll { it.baseAddress == baseAddress && it.expression == expression }
        // 添加To开头
        expressionHistory.add(0, entry)
        // 限制大小
        while (expressionHistory.size > MAX_HISTORY_SIZE) {
            expressionHistory.removeAt(expressionHistory.lastIndex)
        }
    }

    /**
     * 跳Go to最终Address
     */
    private fun jumpToFinalAddress() {
        val result = executionResult ?: return

        if (!result.success || result.finalAddress == 0L) {
            notification.showError("Cannot jump: invalid address")
            return
        }

        // 保存输入内容
        InputHistoryManager.saveFromEditText(binding.inputBaseAddress, InputHistoryManager.Keys.OFFSET_CALCULATOR_BASE)
        InputHistoryManager.saveFromEditText(binding.inputExpression, InputHistoryManager.Keys.OFFSET_CALCULATOR_OFFSET)

        // 添加ToHistory记录
        val baseAddressStr = binding.inputBaseAddress.text.toString().trim()
        val expressionStr = binding.inputExpression.text.toString().trim()
        val baseAddress = try { baseAddressStr.toULong(16).toLong() } catch (e: Exception) { 0L }
        addToHistory(baseAddress, expressionStr, result.finalAddress, binding.cbHexMode.isChecked)

        // 使用 JumpToMemoryPreview 事件，会先切换ToMemory预览 Tab 再跳转
        FloatingEventBus.tryEmitUIAction(
            UIActionEvent.JumpToMemoryPreview(address = result.finalAddress)
        )

        dialog.dismiss()
    }

    override fun dismiss() {
        calculateJob?.cancel()
        // Close时保存输入内容，确保下次打开时能Restore
        if (::binding.isInitialized) {
            InputHistoryManager.saveFromEditText(binding.inputBaseAddress, InputHistoryManager.Keys.OFFSET_CALCULATOR_BASE)
            InputHistoryManager.saveFromEditText(binding.inputExpression, InputHistoryManager.Keys.OFFSET_CALCULATOR_OFFSET)
        }
        super.dismiss()
    }
}
