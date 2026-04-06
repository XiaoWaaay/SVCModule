package moe.fuqiuluo.mamu.floating.dialog

import android.content.Context
import android.view.LayoutInflater
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.databinding.DialogSearchProgressBinding
import moe.fuqiuluo.mamu.driver.PointerScanner

/**
 * 指针扫描进度对话框
 */
class PointerScanProgressDialog(
    context: Context,
    private val onCancelClick: () -> Unit = {},
    private val onHideClick: () -> Unit = {}
) : BaseDialog(context) {

    private lateinit var binding: DialogSearchProgressBinding

    override fun setupDialog() {
        binding = DialogSearchProgressBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        binding.progressTitle.text = context.getString(R.string.pointer_scan_dialog_title)
        binding.tvCounter.text = "Scan phase:"
        binding.tvRegions.text = "Ready"
        binding.tvResults.text = "0"

        binding.btnCancel.setOnClickListener {
            onCancelClick()
            dialog.dismiss()
        }

        binding.btnHide.setOnClickListener {
            onHideClick()
            dialog.dismiss()
        }
    }

    /**
     * 更新进度显示
     */
    fun updateProgress(phase: Int, progress: Int, pointersFound: Long, chainsFound: Long) {
        binding.progressBar.progress = progress
        binding.tvProgress.text = "$progress%"

        val phaseText = when (phase) {
            PointerScanner.Phase.SCANNING_POINTERS -> "Scanning pointers... ($pointersFound)"
            PointerScanner.Phase.BUILDING_CHAINS -> "Building chains..."
            PointerScanner.Phase.WRITING_FILE -> "Writing file... ($chainsFound)"
            PointerScanner.Phase.COMPLETED -> "Completed"
            PointerScanner.Phase.CANCELLED -> "Cancelled"
            PointerScanner.Phase.ERROR -> "Error"
            else -> "Ready"
        }
        binding.tvRegions.text = phaseText
        binding.tvResults.text = chainsFound.toString()
    }
}
