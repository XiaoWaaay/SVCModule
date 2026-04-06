package moe.fuqiuluo.mamu.floating.dialog

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.databinding.DialogSearchProgressBinding
import java.util.Locale

data class ExportMemoryProgressData(
    val progress: Int,
    val currentAddress: Long,
    val bytesWritten: ULong,
    val totalBytes: ULong,
)

class ExportMemoryProgressDialog(
    context: Context,
    private val onCancelClick: (() -> Unit)? = null,
    private val onHideClick: (() -> Unit)? = null,
) : BaseDialog(context) {

    private lateinit var binding: DialogSearchProgressBinding

    @SuppressLint("SetTextI18n")
    override fun setupDialog() {
        binding = DialogSearchProgressBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)
        dialog.setCancelable(false)

        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.getDialogOpacity()
        binding.root.background?.alpha = (opacity * 255).toInt()

        binding.progressTitle.text = "Export Memory"
        binding.tvCounter.text = "Current address:"
        binding.tvRegions.text = "0x0"
        binding.tvResults.text = "0 B / 0 B"

        binding.btnCancel.setOnClickListener {
            onCancelClick?.invoke()
        }

        binding.btnHide.setOnClickListener {
            onHideClick?.invoke()
            dialog.dismiss()
        }

        updateProgress(
            ExportMemoryProgressData(
                progress = 0,
                currentAddress = 0L,
                bytesWritten = 0uL,
                totalBytes = 0uL,
            )
        )
    }

    @SuppressLint("SetTextI18n")
    fun updateProgress(data: ExportMemoryProgressData) {
        if (!::binding.isInitialized) return

        val progress = data.progress.coerceIn(0, 100)
        binding.progressBar.progress = progress
        binding.tvProgress.text = "$progress%"
        binding.tvRegions.text = "0x${java.lang.Long.toUnsignedString(data.currentAddress, 16).uppercase()}"
        binding.tvResults.text = "${formatBytes(data.bytesWritten)} / ${formatBytes(data.totalBytes)}"
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