package moe.fuqiuluo.mamu.floating.dialog

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.data.settings.keyboardType
import moe.fuqiuluo.mamu.databinding.DialogModuleListBinding
import moe.fuqiuluo.mamu.floating.data.local.InputHistoryManager
import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry
import moe.fuqiuluo.mamu.floating.data.model.MemoryRange
import moe.fuqiuluo.mamu.widget.BuiltinKeyboard
import moe.fuqiuluo.mamu.widget.NotificationOverlay

/**
 * 模块Memory列表对话框
 * 用于显示进程的模块Memory列表，点击后跳Go to对应Address
 */
class ModuleListDialog(
    context: Context,
    private val modules: List<DisplayMemRegionEntry>,
    private val notification: NotificationOverlay,
    private val onModuleSelected: (DisplayMemRegionEntry) -> Unit,
    private val onGoto: (Long) -> Unit,
) : BaseDialog(context) {

    private var currentFocusedInput: EditText? = null
    private lateinit var binding: DialogModuleListBinding

    // History记录（静态保存，跨实例共享）
    companion object {
        private val addressHistory = mutableListOf<HistoryEntry>()
        private const val MAX_HISTORY_SIZE = 50
    }

    data class HistoryEntry(
        val address: Long,
        val moduleName: String,
        val rangeType: MemoryRange
    )

    @SuppressLint("SetTextI18n")
    override fun setupDialog() {
        binding = DialogModuleListBinding.inflate(LayoutInflater.from(dialog.context))
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
            suppressSystemKeyboard(binding.inputAddress)
            binding.builtinKeyboard.visibility = View.VISIBLE
            binding.divider.visibility = View.VISIBLE
        } else {
            binding.inputAddress.showSoftInputOnFocus = true
            binding.builtinKeyboard.visibility = View.GONE
            binding.divider.visibility = View.GONE
        }

        // 设置输入框焦点
        currentFocusedInput = binding.inputAddress
        binding.inputAddress.requestFocus()
        
        // Restore上次输入内容并Select All
        InputHistoryManager.restoreAndSelectAll(
            binding.inputAddress, 
            InputHistoryManager.Keys.MODULE_ADDRESS
        )

        // 设置Display按钮（跳Go toAddress对应模块）
        binding.btnShowModule.setOnClickListener {
            val input = binding.inputAddress.text.toString().trim()
            if (input.isNotEmpty()) {
                InputHistoryManager.save(InputHistoryManager.Keys.MODULE_ADDRESS, input)
                showModuleAtAddress(input)
            }
        }

        // 设置History按钮
        binding.btnHistory.setOnClickListener {
            showHistoryPopup()
        }

        // 设置类型筛选按钮
        setupTypeFilterButtons(binding)
        setupQuickLibraryButtons(binding)

        // 设置ALL按钮
        binding.btnAll.setOnClickListener {
            showAllModulesPopup()
        }

        // 设置键盘监听器
        setupBuiltinKeyboard(binding)

        // Cancel按钮
        binding.btnCancel.setOnClickListener {
            InputHistoryManager.saveFromEditText(binding.inputAddress, InputHistoryManager.Keys.MODULE_ADDRESS)
            onCancel?.invoke()
            dialog.dismiss()
        }

        // Go to按钮
        binding.btnGoto.setOnClickListener {
            val input = binding.inputAddress.text.toString().trim()
            if (input.isNotEmpty()) {
                InputHistoryManager.save(InputHistoryManager.Keys.MODULE_ADDRESS, input)
                gotoAddress(input)
            }
        }
    }

    private fun setupTypeFilterButtons(binding: DialogModuleListBinding) {
        val filterButtons = mapOf(
            binding.btnFilterCd to MemoryRange.Cd,
            binding.btnFilterCb to MemoryRange.Cb,
            binding.btnFilterPs to MemoryRange.Ps,
            binding.btnFilterXa to MemoryRange.Xa
        )

        filterButtons.forEach { (button, range) ->
            button.setOnClickListener {
                showFilteredModulesPopup(range)
            }
        }
    }

    private fun setupQuickLibraryButtons(binding: DialogModuleListBinding) {
        binding.btnLibUnity.setOnClickListener {
            gotoLibraryBase("libunity.so")
        }
        binding.btnLibUE4.setOnClickListener {
            gotoLibraryBase("libUE4.so")
        }
    }

    private fun gotoLibraryBase(libraryName: String) {
        val targetModule = modules.firstOrNull { module ->
            val moduleName = module.name.substringAfterLast('/')
            moduleName.equals(libraryName, ignoreCase = true) ||
                module.name.endsWith("/$libraryName", ignoreCase = true)
        }

        if (targetModule == null) {
            notification.showWarning("$libraryName was not found")
            return
        }

        addToHistory(targetModule)
        InputHistoryManager.save(
            InputHistoryManager.Keys.MODULE_ADDRESS,
            String.format("%X", targetModule.start)
        )
        onGoto(targetModule.start)
        dialog.dismiss()
    }

    private fun showFilteredModulesPopup(range: MemoryRange) {
        val filteredModules = modules.filter { it.range == range }
        val title = "${range.code} - ${range.displayName}"

        ModuleListPopupDialog(context, title, filteredModules) { selectedModule ->
            addToHistory(selectedModule)
            fillAddressInput(selectedModule.start)
        }.show()
    }

    private fun showAllModulesPopup() {
        ModuleListPopupDialog(context, "All modules", modules) { selectedModule ->
            addToHistory(selectedModule)
            fillAddressInput(selectedModule.start)
        }.show()
    }

    private fun showHistoryPopup() {
        if (addressHistory.isEmpty()) {
            return
        }

        // 将History记录转换为Module List
        val historyModules = addressHistory.mapNotNull { entry ->
            modules.find { it.start <= entry.address && entry.address < it.end }
        }.distinctBy { it.start }

        if (historyModules.isEmpty()) {
            return
        }

        ModuleListPopupDialog(context, "History", historyModules) { selectedModule ->
            addToHistory(selectedModule)
            fillAddressInput(selectedModule.start)
        }.show()
    }

    private fun showModuleAtAddress(input: String) {
        try {
            val address = input.toULong(16).toLong()
            val targetModule = modules.find { it.start <= address && address < it.end }
            if (targetModule != null) {
                // 显示全部Module List，高亮并聚焦To目标模块
                ModuleListPopupDialog(
                    context,
                    "Module containing address ${input.uppercase()}",
                    modules,
                    highlightModule = targetModule
                ) { selectedModule ->
                    addToHistory(selectedModule)
                    fillAddressInput(selectedModule.start)
                }.show()
            } else {
                notification.showWarning("No module contains that address")
            }
        } catch (e: Exception) {
            notification.showError("Invalid address format")
        }
    }

    private fun gotoAddress(input: String) {
        try {
            val address = input.let {
                if (it.startsWith("0x", ignoreCase = true)) it.substring(2) else it
            }.toULong(16).toLong() and 0x0000FFFFFFFFFFFFL // strip MTE tag
            onGoto(address)
            dialog.dismiss()
        } catch (e: Exception) {
            notification.showError("Invalid address format")
        }
    }

    /**
     * 将Address填入输入框（而非直接跳转）
     */
    private fun fillAddressInput(address: Long) {
        val addressText = String.format("%X", address)
        binding.inputAddress.setText(addressText)
        binding.inputAddress.setSelection(addressText.length)
    }

    private fun addToHistory(module: DisplayMemRegionEntry) {
        val entry = HistoryEntry(module.start, module.name, module.range)
        addressHistory.removeAll { it.address == entry.address }
        addressHistory.add(0, entry)
        if (addressHistory.size > MAX_HISTORY_SIZE) {
            addressHistory.removeAt(addressHistory.lastIndex)
        }
    }

    private fun setupBuiltinKeyboard(binding: DialogModuleListBinding) {
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
                val clipboardManager =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = clipboardManager?.primaryClip
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
}
