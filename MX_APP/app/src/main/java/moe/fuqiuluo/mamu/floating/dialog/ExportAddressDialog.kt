package moe.fuqiuluo.mamu.floating.dialog

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.data.local.RootFileSystem
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.databinding.DialogExportAddressBinding
import moe.fuqiuluo.mamu.driver.ExactSearchResultItem
import moe.fuqiuluo.mamu.driver.FuzzySearchResultItem
import moe.fuqiuluo.mamu.driver.SearchResultItem
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.data.local.InputHistoryManager
import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import moe.fuqiuluo.mamu.floating.data.model.MemoryRange
import moe.fuqiuluo.mamu.floating.data.model.SavedAddress
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import moe.fuqiuluo.mamu.widget.simpleSingleChoiceDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ExportAddress对话框
 * 支持FromSearch结果或保存AddressExportTo文件
 */
class ExportAddressDialog(
    context: Context,
    private val notification: NotificationOverlay,
    private val coroutineScope: CoroutineScope,
    private val selectedItems: List<Any>, // SearchResultItem 或 SavedAddress
    private val ranges: List<DisplayMemRegionEntry>?,
    private val defaultFileName: String,
    private val onExportComplete: ((Boolean) -> Unit)? = null
) : BaseDialog(context) {

    companion object {
        @SuppressLint("SdCardPath")
        private const val DEFAULT_EXPORT_DIR = "/sdcard/Mamu/export"

        private val EXPORT_PATHS = arrayOf(
            "/sdcard/Mamu/export",
            "/sdcard/Download",
            "/sdcard/Documents",
            "/sdcard"
        )
    }

    private var currentExportPath = DEFAULT_EXPORT_DIR

    @SuppressLint("SetTextI18n")
    override fun setupDialog() {
        val binding = DialogExportAddressBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        // 应用透明度设置
        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.getDialogOpacity()
        binding.rootContainer.background?.alpha = (opacity * 255).toInt()

        // 设置默认文件名：优先使用History记录，否则使用默认值
        val savedFileName = InputHistoryManager.get(InputHistoryManager.Keys.EXPORT_FILENAME)
        val fileNameToSet = savedFileName.ifEmpty { defaultFileName }
        binding.inputFileName.setText(fileNameToSet)
        if (fileNameToSet.isNotEmpty()) {
            binding.inputFileName.selectAll()
        }

        // 设置保存Path
        binding.textSavePath.text = currentExportPath

        // 设置Export数量提示
        binding.textExportCount.text = "Export ${selectedItems.size} addresses"

        // Path选择点击事件
        binding.textSavePath.setOnClickListener {
            context.simpleSingleChoiceDialog(
                title = "Choose a save path",
                options = EXPORT_PATHS,
                showRadioButton = true,
                onSingleChoice = { which ->
                    currentExportPath = EXPORT_PATHS[which]
                    binding.textSavePath.text = currentExportPath
                }
            )
        }

        // Cancel按钮
        binding.btnCancel.setOnClickListener {
            InputHistoryManager.saveFromEditText(binding.inputFileName, InputHistoryManager.Keys.EXPORT_FILENAME)
            onCancel?.invoke()
            dialog.dismiss()
        }

        // Export按钮
        binding.btnExport.setOnClickListener {
            val fileName = binding.inputFileName.text.toString().trim()
            if (fileName.isEmpty()) {
                notification.showWarning("Please enter a file name")
                return@setOnClickListener
            }
            
            // 保存文件名
            InputHistoryManager.save(InputHistoryManager.Keys.EXPORT_FILENAME, fileName)

            performExport(fileName)
            dialog.dismiss()
        }
    }

    private fun performExport(fileName: String) {
        coroutineScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    // 确保目录存在
                    ensureDirectoryExists(currentExportPath)

                    // 生成Export内容
                    val content = generateExportContent()

                    // 写入文件
                    val filePath = "$currentExportPath/$fileName.txt"
                    writeToFile(filePath, content)
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }

            if (success) {
                notification.showSuccess("Exported ${selectedItems.size} addresses to $currentExportPath/$fileName.txt")
            } else {
                notification.showError("Export failed")
            }

            onExportComplete?.invoke(success)
        }
    }

    /**
     * 生成Export内容
     * 格式：
     * PID
     * 名称|Address(小写hex)|类型|值|冻结|0|0|0|Permissions|区域名称|偏移
     */
    private fun generateExportContent(): String {
        val sb = StringBuilder()

        // 第一行：PID
        val pid = WuwaDriver.currentBindPid
        sb.appendLine(pid)

        // 后续行：Address数据
        selectedItems.forEach { item ->
            val line = when (item) {
                is SearchResultItem -> formatSearchResultItem(item)
                is SavedAddress -> formatSavedAddress(item)
                else -> null
            }
            line?.let { sb.appendLine(it) }
        }

        return sb.toString()
    }

    /**
     * 格式化Search结果项
     */
    private fun formatSearchResultItem(item: SearchResultItem): String {
        val address = when (item) {
            is ExactSearchResultItem -> item.address
            is FuzzySearchResultItem -> item.address
            else -> return ""
        }

        val value = when (item) {
            is ExactSearchResultItem -> item.value
            is FuzzySearchResultItem -> item.value
            else -> "0"
        }

        val valueType = item.displayValueType ?: DisplayValueType.DWORD
        val name = "Var #${String.format("%X", address)}"
        val addressHex = address.toString(16).lowercase()
        val typeId = valueType.nativeId
        val frozen = 0 // Search结果默认不冻结

        // 查找对应的MemoryRange
        val range = findRangeForAddress(address)
        val permission = "rw-p" // 默认Permissions
        val regionName = range?.name ?: "[anon:unknown]"

        // 计算偏移（Address的低位部分）
        val offset = (address and 0xFFFFF).toString(16).lowercase()

        return "$name|$addressHex|$typeId|$value|$frozen|0|0|0|$permission|$regionName|$offset"
    }

    /**
     * 格式化保存的Address
     */
    private fun formatSavedAddress(item: SavedAddress): String {
        val addressHex = item.address.toString(16).lowercase()
        val frozen = if (item.isFrozen) 1 else 0
        val permission = "rw-p"
        val regionName = item.range.displayName
        val offset = (item.address and 0xFFFFF).toString(16).lowercase()

        return "${item.name}|$addressHex|${item.valueType}|${item.value}|$frozen|0|0|0|$permission|$regionName|$offset"
    }

    /**
     * 查找Address对应的MemoryRange
     */
    private fun findRangeForAddress(address: Long): DisplayMemRegionEntry? {
        if (ranges.isNullOrEmpty()) return null

        return ranges.find { range ->
            address >= range.start && address < range.end
        }
    }

    /**
     * 确保目录存在
     */
    private fun ensureDirectoryExists(path: String): Boolean {
        return if (RootFileSystem.isConnected()) {
            RootFileSystem.ensureDirectory(path)
        } else {
            val dir = File(path)
            if (!dir.exists()) {
                dir.mkdirs()
            } else {
                true
            }
        }
    }

    /**
     * 写入文件
     */
    private fun writeToFile(filePath: String, content: String): Boolean {
        return if (RootFileSystem.isConnected()) {
            RootFileSystem.writeText(filePath, content)
        } else {
            try {
                File(filePath).writeText(content, Charsets.UTF_8)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }
}