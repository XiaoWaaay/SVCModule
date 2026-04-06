package moe.fuqiuluo.mamu.floating.dialog

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.databinding.DialogFilterBinding
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.data.settings.keyboardType
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import moe.fuqiuluo.mamu.widget.BuiltinKeyboard
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import moe.fuqiuluo.mamu.widget.multiChoiceDialog

class FilterDialog(
    context: Context,
    private val notification: NotificationOverlay,
    private val filterDialogState: FilterDialogState,
    private val clipboardManager: ClipboardManager,
    private val onApply: ((FilterDialogState) -> Unit)? = null
) : BaseDialog(context) {

    private var currentFocusedInput: EditText? = null

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    override fun setupDialog() {
        // 使用 dialog.context 确保使用正确的主题
        val binding = DialogFilterBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        // 应用透明度设置
        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.getDialogOpacity()
        binding.rootContainer.background?.alpha = (opacity * 255).toInt()

        val isPortrait =
            context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        binding.builtinKeyboard.setScreenOrientation(isPortrait)

        // 根据配置决定是否禁用系统输入法
        val useBuiltinKeyboard = mmkv.keyboardType == 0
        if (useBuiltinKeyboard) {
            binding.builtinKeyboard.visibility = View.VISIBLE
            binding.divider?.visibility = View.VISIBLE
        } else {
            // 使用系统键盘时，允许系统输入法弹出
            binding.inputMaxDisplayCount.showSoftInputOnFocus = true
            binding.inputAddressStart.showSoftInputOnFocus = true
            binding.inputAddressEnd.showSoftInputOnFocus = true
            binding.builtinKeyboard.visibility = View.GONE
            binding.divider?.visibility = View.GONE
        }

        // Restore状态
        val maxDisplayCountText = filterDialogState.maxDisplayCount.toString()
        binding.inputMaxDisplayCount.setText(maxDisplayCountText)
        if (maxDisplayCountText.isNotEmpty()) {
            binding.inputMaxDisplayCount.selectAll()
        }
        binding.cbEnableAddressFilter.isChecked = filterDialogState.enableAddressFilter
        binding.inputAddressStart.setText(filterDialogState.addressRangeStart)
        if (filterDialogState.addressRangeStart.isNotEmpty()) {
            binding.inputAddressStart.selectAll()
        }
        binding.inputAddressEnd.setText(filterDialogState.addressRangeEnd)
        if (filterDialogState.addressRangeEnd.isNotEmpty()) {
            binding.inputAddressEnd.selectAll()
        }
        binding.cbEnableDataTypeFilter.isChecked = filterDialogState.enableDataTypeFilter

        // 设置输入框焦点监听
        setupInputFocus(binding)

        // 在 setupInputFocus 之后调用，确保包装已有的 OnFocusChangeListener
        if (useBuiltinKeyboard) {
            suppressSystemKeyboard(
                binding.inputMaxDisplayCount,
                binding.inputAddressStart,
                binding.inputAddressEnd
            )
        }

        // 控制AddressRange过滤显示/隐藏
        binding.cbEnableAddressFilter.setOnCheckedChangeListener { _, isChecked ->
            binding.containerAddressRange.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        binding.containerAddressRange.visibility =
            if (filterDialogState.enableAddressFilter) View.VISIBLE else View.GONE

        // 控制数据类型过滤显示/隐藏
        binding.cbEnableDataTypeFilter.setOnCheckedChangeListener { _, isChecked ->
            binding.btnSelectDataTypes.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        binding.btnSelectDataTypes.visibility =
            if (filterDialogState.enableDataTypeFilter) View.VISIBLE else View.GONE

        // 数据类型选择
        updateDataTypeButtonText(binding)
        binding.btnSelectDataTypes.setOnClickListener {
            showDataTypeSelectionDialog(binding)
        }

        // 内置键盘
        setupBuiltinKeyboard(binding)

        // Reset按钮
        binding.btnReset.setOnClickListener {
            resetToDefaults(binding)
        }

        // Cancel按钮
        binding.btnCancel.setOnClickListener {
            saveState(binding)
            onCancel?.invoke()
            dialog.dismiss()
        }

        // 应用按钮
        binding.btnApply.setOnClickListener {
            if (validateInput(binding)) {
                saveState(binding)
                onApply?.invoke(filterDialogState)
                dialog.dismiss()
            }
        }
    }

    private fun setupInputFocus(binding: DialogFilterBinding) {
        val inputs = listOf(
            binding.inputMaxDisplayCount,
            binding.inputAddressStart,
            binding.inputAddressEnd,
        )

        inputs.forEach { input ->
            input.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    currentFocusedInput = view as EditText
                }
            }
        }

        // 默认焦点
        currentFocusedInput = binding.inputMaxDisplayCount
        binding.inputMaxDisplayCount.requestFocus()
    }

    private fun setupBuiltinKeyboard(binding: DialogFilterBinding) {
        binding.builtinKeyboard.listener = object : BuiltinKeyboard.KeyboardListener {
            override fun onKeyInput(key: String) {
                val input = currentFocusedInput ?: return
                // 直接操作 Editable，避免 setText 带来的竞争条件
                val editable = input.text ?: return
                val selectionStart = input.selectionStart
                val selectionEnd = input.selectionEnd

                // 使用 Editable.replace() 直接替换选中的文本
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
                    // 有选中文本，Delete选中部分
                    editable.delete(selectionStart, selectionEnd)
                } else if (selectionStart > 0) {
                    // 无选中文本，Delete光标前一个字符
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
                notification.showSuccess("History support is still under development")
            }

            override fun onPaste() {
                val clip = clipboardManager.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString() ?: ""
                    val input = currentFocusedInput ?: return
                    val editable = input.text ?: return
                    val selectionStart = input.selectionStart
                    val selectionEnd = input.selectionEnd

                    // 使用 Editable.replace() 在光标位置Paste文本
                    editable.replace(selectionStart, selectionEnd, text)
                }
            }
        }
    }

    private fun showDataTypeSelectionDialog(binding: DialogFilterBinding) {
        val allTypes = DisplayValueType.entries.toTypedArray()
        val typeNames = allTypes.map { it.displayName }.toTypedArray()
        val checkedItems = BooleanArray(allTypes.size) { index ->
            filterDialogState.selectedDataTypes.contains(allTypes[index])
        }

        context.multiChoiceDialog(
            title = "Choose data types",
            options = typeNames,
            checkedItems = checkedItems,
            onMultiChoice = { newCheckedItems ->
                filterDialogState.selectedDataTypes.clear()
                newCheckedItems.forEachIndexed { index, checked ->
                    if (checked) {
                        filterDialogState.selectedDataTypes.add(allTypes[index])
                    }
                }
                updateDataTypeButtonText(binding)
            }
        )
    }

    private fun updateDataTypeButtonText(binding: DialogFilterBinding) {
        val text = if (filterDialogState.selectedDataTypes.isEmpty()) {
            context.getString(R.string.button_select_data_types)
        } else {
            filterDialogState.selectedDataTypes.joinToString(", ") { it.displayName }
        }
        binding.btnSelectDataTypes.text = text
    }

    private fun resetToDefaults(binding: DialogFilterBinding) {
        // Reset为默认值
        filterDialogState.maxDisplayCount = 100
        filterDialogState.enableAddressFilter = false
        filterDialogState.addressRangeStart = ""
        filterDialogState.addressRangeEnd = ""
        filterDialogState.enableDataTypeFilter = false
        filterDialogState.selectedDataTypes.clear()

        // 更新UI
        binding.inputMaxDisplayCount.setText("100")
        binding.cbEnableAddressFilter.isChecked = false
        binding.inputAddressStart.setText("")
        binding.inputAddressEnd.setText("")
        updateDataTypeButtonText(binding)

        notification.showSuccess("Reset to default values")
    }

    private fun validateInput(binding: DialogFilterBinding): Boolean {
        // 验证单页最大显示数量
        val maxCount = binding.inputMaxDisplayCount.text.toString().toIntOrNull()
        if (maxCount == null || maxCount <= 0) {
            notification.showError("The maximum items per page must be a positive integer")
            return false
        }

        // 如果启用了AddressRange过滤，验证AddressRange
        if (binding.cbEnableAddressFilter.isChecked) {
            val startAddr = binding.inputAddressStart.text.toString().trim()
            val endAddr = binding.inputAddressEnd.text.toString().trim()
            if (startAddr.isEmpty() || endAddr.isEmpty()) {
                notification.showError("The address range cannot be empty")
                return false
            }
        }

        // 如果启用了数据类型过滤，验证是否选择了至少一个类型
        if (binding.cbEnableDataTypeFilter.isChecked && filterDialogState.selectedDataTypes.isEmpty()) {
            notification.showError("Please select at least one data type")
            return false
        }

        return true
    }

    private fun saveState(binding: DialogFilterBinding) {
        filterDialogState.maxDisplayCount =
            binding.inputMaxDisplayCount.text.toString().toIntOrNull() ?: 100
        filterDialogState.enableAddressFilter = binding.cbEnableAddressFilter.isChecked
        filterDialogState.addressRangeStart = binding.inputAddressStart.text.toString()
        filterDialogState.addressRangeEnd = binding.inputAddressEnd.text.toString()
        filterDialogState.enableDataTypeFilter = binding.cbEnableDataTypeFilter.isChecked
    }
}