package moe.fuqiuluo.mamu.floating.dialog

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.mamu.data.local.RootFileSystem
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.data.settings.keyboardType
import moe.fuqiuluo.mamu.databinding.DialogExportMemoryBinding
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.data.local.InputHistoryManager
import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry
import moe.fuqiuluo.mamu.widget.BuiltinKeyboard
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import moe.fuqiuluo.mamu.widget.simpleSingleChoiceDialog
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExportMemoryDialog(
    context: Context,
    private val notification: NotificationOverlay,
    private val coroutineScope: CoroutineScope,
    private val memoryRegions: List<DisplayMemRegionEntry>,
    private val defaultStartAddress: Long,
    private val defaultEndAddress: Long,
) : BaseDialog(context) {

    companion object {
        private const val CHUNK_SIZE = 64 * 1024

        @SuppressLint("SdCardPath")
        private const val DEFAULT_EXPORT_DIR = "/sdcard/Mamu/export"

        private val EXPORT_PATHS = arrayOf(
            "/sdcard/Mamu/export",
            "/sdcard/Download",
            "/sdcard/Documents",
            "/sdcard"
        )
    }

    private var currentFocusedInput: EditText? = null
    private var progressDialog: ExportMemoryProgressDialog? = null

    @Volatile
    private var exportCancelled = false

    private data class ExportResult(
        val success: Boolean,
        val filePath: String,
        val bytesWritten: ULong,
        val errorMessage: String? = null,
        val failedAddress: Long? = null,
        val cancelled: Boolean = false,
    )

    private class ExportFailedException(
        val failedAddress: Long?,
        message: String,
    ) : RuntimeException(message)

    private class ExportCancelledException(
        val cancelAddress: Long?,
        val bytesWritten: ULong,
    ) : RuntimeException("ExportCancelled")

    override fun setupDialog() {
        val binding = DialogExportMemoryBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.getDialogOpacity()
        binding.rootContainer.background?.alpha = (opacity * 255).toInt()

        val isPortrait =
            context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        binding.builtinKeyboard.setScreenOrientation(isPortrait)

        val useBuiltinKeyboard = mmkv.keyboardType == 0
        if (useBuiltinKeyboard) {
            binding.builtinKeyboard.visibility = View.VISIBLE
            binding.divider.visibility = View.VISIBLE
            suppressSystemKeyboard(
                binding.inputAddressStart,
                binding.inputAddressEnd,
                binding.inputExportPath
            )
        } else {
            binding.builtinKeyboard.visibility = View.GONE
            binding.divider.visibility = View.GONE
            binding.inputAddressStart.showSoftInputOnFocus = true
            binding.inputAddressEnd.showSoftInputOnFocus = true
            binding.inputExportPath.showSoftInputOnFocus = true
        }

        setupFocusTracking(binding)
        setupBuiltinKeyboard(binding)
        restoreInputState(binding)

        binding.btnPickStartSegment.setOnClickListener {
            showRegionPicker("Choose start memory region") { region ->
                binding.inputAddressStart.setText(toHex(region.start))
            }
        }

        binding.btnPickEndSegment.setOnClickListener {
            showRegionPicker("Choose end memory region") { region ->
                binding.inputAddressEnd.setText(toHex(region.end - 1))
            }
        }

        binding.btnPickExportPath.setOnClickListener {
            context.simpleSingleChoiceDialog(
                title = "Choose a save path",
                options = EXPORT_PATHS,
                showRadioButton = true,
                onSingleChoice = { which ->
                    binding.inputExportPath.setText(EXPORT_PATHS[which])
                    binding.inputExportPath.setSelection(binding.inputExportPath.text?.length ?: 0)
                }
            )
        }

        binding.btnCancel.setOnClickListener {
            saveInputState(binding)
            onCancel?.invoke()
            dialog.dismiss()
        }

        binding.btnExport.setOnClickListener {
            saveInputState(binding)
            val startAddress = parseHexAddress(binding.inputAddressStart.text?.toString().orEmpty())
            val endAddress = parseHexAddress(binding.inputAddressEnd.text?.toString().orEmpty())
            val exportDir = binding.inputExportPath.text?.toString()?.trim().orEmpty()

            if (startAddress == null) {
                notification.showError("Invalid start address format")
                return@setOnClickListener
            }
            if (endAddress == null) {
                notification.showError("Invalid end address format")
                return@setOnClickListener
            }
            if (endAddress.toULong() < startAddress.toULong()) {
                notification.showError("Invalid address range: the end address must be greater than or equal to the start address")
                return@setOnClickListener
            }
            if (exportDir.isEmpty()) {
                notification.showError("The export path cannot be empty")
                return@setOnClickListener
            }

            performExport(startAddress, endAddress, exportDir)
            dialog.dismiss()
        }
    }

    private fun setupFocusTracking(binding: DialogExportMemoryBinding) {
        val inputs = listOf(
            binding.inputAddressStart,
            binding.inputAddressEnd,
            binding.inputExportPath,
        )
        inputs.forEach { input ->
            input.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) currentFocusedInput = view as EditText
            }
        }

        currentFocusedInput = binding.inputAddressStart
        binding.inputAddressStart.requestFocus()
        binding.inputAddressStart.selectAll()
    }

    private fun setupBuiltinKeyboard(binding: DialogExportMemoryBinding) {
        val clipboardManager =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

        binding.builtinKeyboard.listener = object : BuiltinKeyboard.KeyboardListener {
            override fun onKeyInput(key: String) {
                val input = currentFocusedInput ?: return
                val editable = input.text ?: return
                val start = input.selectionStart
                val end = input.selectionEnd
                editable.replace(start, end, key)
                input.setSelection(start + key.length)
            }

            override fun onDelete() {
                val input = currentFocusedInput ?: return
                val editable = input.text ?: return
                val start = input.selectionStart
                val end = input.selectionEnd

                if (start != end) {
                    editable.delete(start, end)
                } else if (start > 0) {
                    editable.delete(start - 1, start)
                }
            }

            override fun onSelectAll() {
                currentFocusedInput?.selectAll()
            }

            override fun onMoveLeft() {
                val input = currentFocusedInput ?: return
                val cursorPos = input.selectionStart
                if (cursorPos > 0) input.setSelection(cursorPos - 1)
            }

            override fun onMoveRight() {
                val input = currentFocusedInput ?: return
                val cursorPos = input.selectionStart
                if (cursorPos < input.text.length) input.setSelection(cursorPos + 1)
            }

            override fun onHistory() = Unit

            override fun onPaste() {
                val clip = clipboardManager?.primaryClip ?: return
                if (clip.itemCount <= 0) return
                val text = clip.getItemAt(0).text?.toString().orEmpty()
                val input = currentFocusedInput ?: return
                val editable = input.text ?: return
                val start = input.selectionStart
                val end = input.selectionEnd
                editable.replace(start, end, text)
            }
        }
    }

    private fun restoreInputState(binding: DialogExportMemoryBinding) {
        val historyStart = InputHistoryManager.get(InputHistoryManager.Keys.EXPORT_MEMORY_START)
        val historyEnd = InputHistoryManager.get(InputHistoryManager.Keys.EXPORT_MEMORY_END)
        val historyPath = InputHistoryManager.get(InputHistoryManager.Keys.EXPORT_MEMORY_PATH)

        binding.inputAddressStart.setText(historyStart.ifEmpty { toHex(defaultStartAddress) })
        binding.inputAddressEnd.setText(historyEnd.ifEmpty { toHex(defaultEndAddress) })
        binding.inputExportPath.setText(historyPath.ifEmpty { DEFAULT_EXPORT_DIR })
    }

    private fun saveInputState(binding: DialogExportMemoryBinding) {
        InputHistoryManager.save(
            InputHistoryManager.Keys.EXPORT_MEMORY_START,
            binding.inputAddressStart.text?.toString().orEmpty()
        )
        InputHistoryManager.save(
            InputHistoryManager.Keys.EXPORT_MEMORY_END,
            binding.inputAddressEnd.text?.toString().orEmpty()
        )
        InputHistoryManager.save(
            InputHistoryManager.Keys.EXPORT_MEMORY_PATH,
            binding.inputExportPath.text?.toString().orEmpty()
        )
    }

    private fun showRegionPicker(
        title: String,
        onSelected: (DisplayMemRegionEntry) -> Unit,
    ) {
        if (memoryRegions.isEmpty()) {
            notification.showWarning("No memory ranges are currently available")
            return
        }
        ModuleListPopupDialog(context, title, memoryRegions, onModuleSelected = onSelected).show()
    }

    private fun performExport(startAddress: Long, endAddress: Long, exportDir: String) {
        if (!WuwaDriver.isProcessBound || WuwaDriver.currentBindPid <= 0) {
            notification.showError("No process bound")
            return
        }

        val pid = WuwaDriver.currentBindPid
        val fileName = createExportFileName(pid, startAddress, endAddress)
        val outputPath = buildOutputPath(exportDir, fileName)
        val totalBytes = endAddress.toULong() - startAddress.toULong() + 1uL

        exportCancelled = false
        showProgressDialog(startAddress, totalBytes)

        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                var lastUiUpdateAt = 0L
                exportMemoryRange(startAddress, endAddress, outputPath) { currentAddress, bytesWritten, total ->
                    val now = System.currentTimeMillis()
                    if (bytesWritten == total || now - lastUiUpdateAt >= 50L) {
                        lastUiUpdateAt = now
                        val progress = calculateProgress(bytesWritten, total)
                        coroutineScope.launch(Dispatchers.Main) {
                            progressDialog?.updateProgress(
                                ExportMemoryProgressData(
                                    progress = progress,
                                    currentAddress = currentAddress,
                                    bytesWritten = bytesWritten,
                                    totalBytes = total
                                )
                            )
                        }
                    }
                }
            }

            progressDialog?.dismiss()
            progressDialog = null
            exportCancelled = false

            when {
                result.success -> {
                    notification.showSuccess(
                        "MemoryExportSuccess: ${result.filePath} (${formatBytes(result.bytesWritten)})"
                    )
                }
                result.cancelled -> {
                    notification.showWarning("ExportCancelled")
                }
                else -> {
                    val failedAddr = result.failedAddress?.let { " Failed address: 0x${toHex(it)}" }.orEmpty()
                    notification.showError("ExportFailed: ${result.errorMessage.orEmpty()}$failedAddr")
                }
            }
        }
    }

    private fun showProgressDialog(startAddress: Long, totalBytes: ULong) {
        progressDialog?.dismiss()
        progressDialog = ExportMemoryProgressDialog(
            context = context,
            onCancelClick = {
                exportCancelled = true
            },
            onHideClick = {
                progressDialog = null
            }
        ).apply {
            show()
            updateProgress(
                ExportMemoryProgressData(
                    progress = 0,
                    currentAddress = startAddress,
                    bytesWritten = 0uL,
                    totalBytes = totalBytes
                )
            )
        }
    }

    private fun calculateProgress(bytesWritten: ULong, totalBytes: ULong): Int {
        if (totalBytes == 0uL) return 100
        if (bytesWritten >= totalBytes) return 100
        return ((bytesWritten.toDouble() / totalBytes.toDouble()) * 100.0).toInt().coerceIn(0, 99)
    }

    private fun exportMemoryRange(
        startAddress: Long,
        endAddress: Long,
        filePath: String,
        onProgress: ((currentAddress: Long, bytesWritten: ULong, totalBytes: ULong) -> Unit)? = null,
    ): ExportResult {
        val totalBytes = endAddress.toULong() - startAddress.toULong() + 1uL
        val parentPath = filePath.substringBeforeLast('/', "")

        if (parentPath.isEmpty()) {
            return ExportResult(false, filePath, 0uL, errorMessage = "Invalid export path")
        }
        if (!ensureExportDirectory(parentPath)) {
            return ExportResult(false, filePath, 0uL, errorMessage = "Unable to create the export directory")
        }

        return try {
            val outputStream = createOutputStream(filePath)
                ?: throw ExportFailedException(null, "Unable to open the output file")

            outputStream.use { stream ->
                var currentAddress = startAddress
                var remaining = totalBytes
                var writtenBytes = 0uL

                onProgress?.invoke(startAddress, writtenBytes, totalBytes)

                while (remaining > 0uL) {
                    if (exportCancelled) {
                        throw ExportCancelledException(currentAddress, writtenBytes)
                    }

                    val chunkSize = minOf(CHUNK_SIZE.toULong(), remaining).toInt()
                    val data = WuwaDriver.readMemory(currentAddress, chunkSize)
                        ?: throw ExportFailedException(currentAddress, "Failed to read memory")

                    if (data.size != chunkSize) {
                        throw ExportFailedException(
                            currentAddress,
                            "Unexpected memory read size: expected $chunkSize, got ${data.size}"
                        )
                    }

                    stream.write(data)
                    writtenBytes += chunkSize.toULong()
                    currentAddress += chunkSize.toLong()
                    remaining -= chunkSize.toULong()

                    val displayAddress = if (remaining > 0uL) currentAddress else endAddress
                    onProgress?.invoke(displayAddress, writtenBytes, totalBytes)
                }
                stream.flush()
            }

            ExportResult(true, filePath, totalBytes)
        } catch (e: ExportCancelledException) {
            cleanupPartialFile(filePath)
            ExportResult(
                success = false,
                filePath = filePath,
                bytesWritten = e.bytesWritten,
                errorMessage = e.message ?: "ExportCancelled",
                failedAddress = e.cancelAddress,
                cancelled = true
            )
        } catch (e: ExportFailedException) {
            cleanupPartialFile(filePath)
            ExportResult(
                success = false,
                filePath = filePath,
                bytesWritten = 0uL,
                errorMessage = e.message ?: "Export failed",
                failedAddress = e.failedAddress
            )
        } catch (e: Exception) {
            cleanupPartialFile(filePath)
            ExportResult(
                success = false,
                filePath = filePath,
                bytesWritten = 0uL,
                errorMessage = e.message ?: "Export failed"
            )
        }
    }

    private fun ensureExportDirectory(path: String): Boolean {
        return if (RootFileSystem.isConnected()) {
            RootFileSystem.ensureDirectory(path)
        } else {
            val dir = File(path)
            dir.exists() || dir.mkdirs()
        }
    }

    private fun createOutputStream(path: String): OutputStream? {
        return if (RootFileSystem.isConnected()) {
            val file = RootFileSystem.getFile(path) ?: return null
            file.parentFile?.let { parent -> if (!parent.exists()) parent.mkdirs() }
            file.newOutputStream()
        } else {
            val file = File(path)
            file.parentFile?.let { parent -> if (!parent.exists()) parent.mkdirs() }
            file.outputStream()
        }
    }

    private fun cleanupPartialFile(path: String) {
        if (RootFileSystem.isConnected()) {
            RootFileSystem.delete(path)
        } else {
            runCatching { File(path).delete() }
        }
    }

    private fun parseHexAddress(input: String): Long? {
        val clean = input.trim().removePrefix("0x").removePrefix("0X")
        if (clean.isEmpty()) return null
        return clean.toULongOrNull(16)?.toLong()
    }

    private fun createExportFileName(pid: Int, startAddress: Long, endAddress: Long): String {
        val now = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "mem_${pid}_${toHex(startAddress)}-${toHex(endAddress)}_$now.bin"
    }

    private fun buildOutputPath(exportDir: String, fileName: String): String {
        return "${exportDir.trim().trimEnd('/')}/$fileName"
    }

    private fun toHex(address: Long): String {
        return java.lang.Long.toUnsignedString(address, 16).uppercase()
    }

    private fun formatBytes(bytes: ULong): String {
        val size = bytes.toDouble()
        return when {
            size >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", size / 1024.0 / 1024.0 / 1024.0)
            size >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", size / 1024.0 / 1024.0)
            size >= 1024 -> String.format(Locale.US, "%.2f KB", size / 1024.0)
            else -> "${bytes} B"
        }
    }
}
