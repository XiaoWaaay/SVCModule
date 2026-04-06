package moe.fuqiuluo.mamu.floating.dialog

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.databinding.DialogModifyValueBinding
import moe.fuqiuluo.mamu.driver.ExactSearchResultItem
import moe.fuqiuluo.mamu.driver.FuzzySearchResultItem
import moe.fuqiuluo.mamu.driver.SearchResultItem
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.data.settings.keyboardType
import moe.fuqiuluo.mamu.floating.data.local.InputHistoryManager
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import moe.fuqiuluo.mamu.floating.data.model.SavedAddress
import moe.fuqiuluo.mamu.widget.BuiltinKeyboard
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import moe.fuqiuluo.mamu.widget.simpleSingleChoiceDialog

class BatchModifyValueDialog private constructor(
    context: Context,
    private val notification: NotificationOverlay,
    private val clipboardManager: ClipboardManager,
    private val searchResultItems: List<SearchResultItem>?,
    private val savedAddresses: List<SavedAddress>?,
    private val onConfirmSearchResult: ((items: List<SearchResultItem>, newValue: String, valueType: DisplayValueType, freeze: Boolean) -> Unit)?,
    private val onConfirmSavedAddress: ((items: List<SavedAddress>, newValue: String, valueType: DisplayValueType, freeze: Boolean) -> Unit)?
) : BaseDialog(context) {

    // 用于Search结果的构造Functions
    constructor(
        context: Context,
        notification: NotificationOverlay,
        clipboardManager: ClipboardManager,
        searchResultItems: List<SearchResultItem>,
        onConfirm: ((items: List<SearchResultItem>, newValue: String, valueType: DisplayValueType, freeze: Boolean) -> Unit)?
    ) : this(context, notification, clipboardManager, searchResultItems, null, onConfirm, null)

    // 用于保存Address的构造Functions
    constructor(
        context: Context,
        clipboardManager: ClipboardManager,
        notification: NotificationOverlay,
        savedAddresses: List<SavedAddress>,
        onConfirm: ((items: List<SavedAddress>, newValue: String, valueType: DisplayValueType, freeze: Boolean) -> Unit)?
    ) : this(context, notification, clipboardManager, null, savedAddresses, null, onConfirm)

    private val itemCount: Int
        get() = searchResultItems?.size ?: savedAddresses?.size ?: 0

    private val defaultValueType: DisplayValueType
        get() {
            // FromSearch结果获取
            searchResultItems?.firstOrNull()?.let { firstItem ->
                return when (firstItem) {
                    is ExactSearchResultItem -> firstItem.displayValueType ?: DisplayValueType.DWORD
                    is FuzzySearchResultItem -> firstItem.displayValueType ?: DisplayValueType.DWORD
                    else -> DisplayValueType.DWORD
                }
            }
            // From保存Address获取
            savedAddresses?.firstOrNull()?.let { firstItem ->
                return firstItem.displayValueType ?: DisplayValueType.DWORD
            }
            return DisplayValueType.DWORD
        }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    override fun setupDialog() {
        // 使用 dialog.context 确保使用正确的主题
        val binding = DialogModifyValueBinding.inflate(LayoutInflater.from(dialog.context))
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
            // 使用内置键盘时，禁用系统输入法弹出
            suppressSystemKeyboard(binding.inputValue)
            binding.builtinKeyboard.visibility = View.VISIBLE
            binding.divider.visibility = View.VISIBLE
        } else {
            // 使用系统键盘时，允许系统输入法弹出
            binding.inputValue.showSoftInputOnFocus = true
            binding.builtinKeyboard.visibility = View.GONE
            binding.divider.visibility = View.GONE
        }

        // 设置Title：批量修改 N addresses
        binding.titleText.text = "Batch edit $itemCount addresses"

        // 初始化值类型
        val allValueTypes = DisplayValueType.entries.filter { !it.isDisabled }.toTypedArray()
        val valueTypeNames = allValueTypes.map { it.displayName }.toTypedArray()
        val valueTypeColors = allValueTypes.map { it.textColor }.toTypedArray()

        // 默认使用第一个选中项的类型
        var currentValueType = defaultValueType

        fun updateSubtitleRange(type: DisplayValueType) {
            binding.subtitleRange.text = type.rangeDescription
        }

        // 设置初始值：Restore上次输入并Select All
        InputHistoryManager.restoreAndSelectAll(
            binding.inputValue,
            InputHistoryManager.Keys.BATCH_MODIFY_VALUE
        )
        binding.btnValueType.text = currentValueType.displayName
        updateSubtitleRange(currentValueType)

        binding.btnValueType.setOnClickListener {
            context.simpleSingleChoiceDialog(
                options = valueTypeNames,
                selected = allValueTypes.indexOf(currentValueType),
                showTitle = false,
                showRadioButton = false,
                textColors = valueTypeColors,
                onSingleChoice = { which ->
                    currentValueType = allValueTypes[which]
                    binding.btnValueType.text = currentValueType.displayName
                    updateSubtitleRange(currentValueType)
                }
            )
        }

        binding.btnConvertBase.setOnClickListener {
            notification.showSuccess(context.getString(R.string.feature_convert_base_todo))
        }

        binding.builtinKeyboard.listener = object : BuiltinKeyboard.KeyboardListener {
            override fun onKeyInput(key: String) {
                // 直接操作 Editable，避免 setText 带来的竞争条件
                val editable = binding.inputValue.text ?: return
                val selectionStart = binding.inputValue.selectionStart
                val selectionEnd = binding.inputValue.selectionEnd

                // 使用 Editable.replace() 直接替换选中的文本
                editable.replace(selectionStart, selectionEnd, key)
                // 输入后将光标移动To新插入文本的末尾，Cancel选择状态
                val newCursorPos = selectionStart + key.length
                binding.inputValue.setSelection(newCursorPos)
            }

            override fun onDelete() {
                val editable = binding.inputValue.text ?: return
                val selectionStart = binding.inputValue.selectionStart
                val selectionEnd = binding.inputValue.selectionEnd

                if (selectionStart != selectionEnd) {
                    // 有选中文本，Delete选中部分
                    editable.delete(selectionStart, selectionEnd)
                } else if (selectionStart > 0) {
                    // 无选中文本，Delete光标前一个字符
                    editable.delete(selectionStart - 1, selectionStart)
                }
            }

            override fun onSelectAll() {
                binding.inputValue.selectAll()
            }

            override fun onMoveLeft() {
                val cursorPos = binding.inputValue.selectionStart
                if (cursorPos > 0) {
                    binding.inputValue.setSelection(cursorPos - 1)
                }
            }

            override fun onMoveRight() {
                val cursorPos = binding.inputValue.selectionStart
                if (cursorPos < binding.inputValue.text.length) {
                    binding.inputValue.setSelection(cursorPos + 1)
                }
            }

            override fun onHistory() {
                notification.showSuccess(context.getString(R.string.feature_history_todo))
            }

            override fun onPaste() {
                val clip = clipboardManager.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString() ?: ""
                    val editable = binding.inputValue.text ?: return
                    val selectionStart = binding.inputValue.selectionStart
                    val selectionEnd = binding.inputValue.selectionEnd

                    // 使用 Editable.replace() 在光标位置Paste文本
                    editable.replace(selectionStart, selectionEnd, text)
                }
            }
        }

        binding.btnGoto.setOnClickListener {
            notification.showSuccess("Go-to support is coming soon")
        }

        binding.btnCancel.setOnClickListener {
            InputHistoryManager.saveFromEditText(binding.inputValue, InputHistoryManager.Keys.BATCH_MODIFY_VALUE)
            onCancel?.invoke()
            dialog.dismiss()
        }

        binding.btnConfirm.setOnClickListener {
            val newValue = binding.inputValue.text.toString().trim()
            val freeze = binding.cbIsFreeze.isChecked

            if (newValue.isEmpty()) {
                notification.showError(context.getString(R.string.error_empty_search_value))
                return@setOnClickListener
            }

            // 保存输入内容
            InputHistoryManager.save(InputHistoryManager.Keys.BATCH_MODIFY_VALUE, newValue)

            // 根据数据类型调用对应的回调
            searchResultItems?.let {
                onConfirmSearchResult?.invoke(it, newValue, currentValueType, freeze)
            }
            savedAddresses?.let {
                onConfirmSavedAddress?.invoke(it, newValue, currentValueType, freeze)
            }
            dialog.dismiss()
        }
    }
}