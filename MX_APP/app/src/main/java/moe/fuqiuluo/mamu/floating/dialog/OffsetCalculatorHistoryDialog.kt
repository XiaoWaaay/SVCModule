package moe.fuqiuluo.mamu.floating.dialog

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.databinding.DialogOffsetCalculatorHistoryBinding
import moe.fuqiuluo.mamu.databinding.ItemOffsetCalculatorHistoryBinding

/**
 * Offset CalculatorHistory记录对话框
 */
class OffsetCalculatorHistoryDialog(
    context: Context,
    private val history: List<OffsetCalculatorDialog.HistoryEntry>,
    private val onItemSelected: (OffsetCalculatorDialog.HistoryEntry) -> Unit
) : BaseDialog(context) {

    private lateinit var binding: DialogOffsetCalculatorHistoryBinding
    private lateinit var adapter: HistoryAdapter

    override fun setupDialog() {
        binding = DialogOffsetCalculatorHistoryBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        // 设置透明度
        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.getDialogOpacity()
        binding.rootContainer.background?.alpha = (opacity * 255).toInt()

        setupRecyclerView()
        setupButtons()
        updateEmptyState()
    }

    private fun setupRecyclerView() {
        adapter = HistoryAdapter(
            items = history.toMutableList(),
            onItemClick = { entry ->
                onItemSelected(entry)
                dialog.dismiss()
            }
        )

        binding.historyList.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@OffsetCalculatorHistoryDialog.adapter
        }
    }

    private fun setupButtons() {
        // 清空全部按钮
        binding.btnClearAll.setOnClickListener {
            OffsetCalculatorDialog.clearHistory()
            adapter.clearItems()
            updateEmptyState()
        }

        // Cancel按钮
        binding.btnCancel.setOnClickListener {
            onCancel?.invoke()
            dialog.dismiss()
        }
    }

    private fun updateEmptyState() {
        if (adapter.itemCount == 0) {
            binding.emptyState.visibility = View.VISIBLE
            binding.historyList.visibility = View.GONE
            binding.btnClearAll.visibility = View.GONE
        } else {
            binding.emptyState.visibility = View.GONE
            binding.historyList.visibility = View.VISIBLE
            binding.btnClearAll.visibility = View.VISIBLE
        }
    }

    /**
     * History记录适配器
     */
    private class HistoryAdapter(
        private val items: MutableList<OffsetCalculatorDialog.HistoryEntry>,
        private val onItemClick: (OffsetCalculatorDialog.HistoryEntry) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemOffsetCalculatorHistoryBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemOffsetCalculatorHistoryBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = items[position]
            holder.binding.apply {
                tvExpression.text = entry.expression
                tvBaseAddress.text = "0x%X".format(entry.baseAddress)
                tvFinalAddress.text = "0x%X".format(entry.finalAddress)
                tvHexMode.visibility = if (entry.hexMode) View.VISIBLE else View.GONE

                root.setOnClickListener { onItemClick(entry) }
            }
        }

        override fun getItemCount(): Int = items.size

        fun clearItems() {
            items.clear()
            notifyDataSetChanged()
        }
    }
}
