package moe.fuqiuluo.mamu.floating.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import moe.fuqiuluo.mamu.databinding.ItemMemoryRangeChoiceBinding
import moe.fuqiuluo.mamu.floating.data.model.MemoryRange
import moe.fuqiuluo.mamu.utils.ByteFormatUtils

/**
 * MemoryRange 多选列表适配器
 */
class MemoryRangeAdapter(
    private val context: Context,
    private val memoryRanges: Array<MemoryRange>,
    private val checkedItems: BooleanArray,
    private val memorySizes: Map<MemoryRange, Long>? = null
) : BaseAdapter() {

    override fun getCount(): Int = memoryRanges.size

    override fun getItem(position: Int): MemoryRange = memoryRanges[position]

    override fun getItemId(position: Int): Long = position.toLong()

    @SuppressLint("SetTextI18n")
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val binding = if (convertView == null) {
            ItemMemoryRangeChoiceBinding.inflate(LayoutInflater.from(context), parent, false)
        } else {
            ItemMemoryRangeChoiceBinding.bind(convertView)
        }

        val memoryRange = memoryRanges[position]

        // 设置文本内容和颜色
        val sizeText = if (memorySizes == null) "" else " [${
            ByteFormatUtils.formatBytes(
                memorySizes[memoryRange] ?: 0,
                0
            )
        }]"

        binding.text1.text = "${memoryRange.code}: ${memoryRange.displayName}$sizeText"
        binding.text1.setTextColor(memoryRange.color)

        // 设置 CheckBox 状态（白色已在布局中设置）
        binding.checkbox.isChecked = checkedItems[position]

        // 点击整个项目时切换 CheckBox 状态
        binding.root.setOnClickListener {
            checkedItems[position] = !checkedItems[position]
            binding.checkbox.isChecked = checkedItems[position]
        }

        return binding.root
    }

    /**
     * 获取当前选中状态
     */
    fun getCheckedItems(): BooleanArray = checkedItems.copyOf()
}