package moe.fuqiuluo.mamu.floating.dialog

import android.content.Context
import android.view.LayoutInflater
import android.widget.ListView
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.databinding.DialogMultiChoiceBinding
import moe.fuqiuluo.mamu.floating.adapter.MemoryRangeAdapter
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.floating.data.model.MemoryRange

class MemoryRangeDialog(
    context: Context,
    private val memoryRanges: Array<MemoryRange>,
    private val checkedItems: BooleanArray,
    private val memorySizes: Map<MemoryRange, Long>? = null,
    private val defaultCheckedItems: BooleanArray? = null,
): BaseDialog(context) {
    var onMultiChoice: ((BooleanArray) -> Unit)? = null
    private var adapter: MemoryRangeAdapter? = null

    override fun setupDialog() {
        // 使用 dialog.context 确保使用正确的主题
        val binding = DialogMultiChoiceBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        // 应用透明度设置
        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.getDialogOpacity()
        binding.rootContainer.background?.alpha = (opacity * 255).toInt()

        // 设置Title
        binding.dialogTitle.text = "Select memory ranges"

        adapter = MemoryRangeAdapter(context, memoryRanges, checkedItems, memorySizes)

        // 设置列表
        binding.optionList.adapter = adapter
        binding.optionList.choiceMode = ListView.CHOICE_MODE_NONE // Custom Adapter 自己管理选中状态

        // 显示Reset按钮（如果提供了默认值）
        if (defaultCheckedItems != null) {
            binding.btnReset.visibility = android.view.View.VISIBLE
            binding.btnReset.setOnClickListener {
                resetToDefault()
            }
        }

        // OK按钮
        binding.btnOk.setOnClickListener {
            val newCheckedItems = adapter?.getCheckedItems() ?: checkedItems
            onMultiChoice?.invoke(newCheckedItems)
            dialog.dismiss()
        }

        // Cancel按钮
        binding.btnCancel.setOnClickListener {
            onCancel?.invoke()
            dialog.dismiss()
        }
    }

    private fun resetToDefault() {
        if (defaultCheckedItems == null) return

        // RestoreTo默认状态
        for (i in checkedItems.indices) {
            checkedItems[i] = defaultCheckedItems[i]
        }

        // 通知适配器刷新
        adapter?.notifyDataSetChanged()
    }
}