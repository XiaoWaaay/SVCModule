package moe.fuqiuluo.mamu.widget

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.TextView
import moe.fuqiuluo.mamu.R

/**
 * 通用多选列表适配器
 */
class MultiChoiceAdapter(
    private val context: Context,
    private val options: Array<String>,
    private val checkedItems: BooleanArray,
    private val itemColors: IntArray? = null
) : BaseAdapter() {

    override fun getCount(): Int = options.size

    override fun getItem(position: Int): String = options[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_memory_range_choice, parent, false)

        val checkBox = view.findViewById<CheckBox>(android.R.id.checkbox)
        val textView = view.findViewById<TextView>(android.R.id.text1)

        val option = options[position]

        // 设置文本内容和颜色
        textView.text = option
        val color = itemColors?.getOrNull(position) ?: 0xFFFFFFFF.toInt()
        textView.setTextColor(color)

        // 设置 CheckBox 状态（白色已在布局中设置）
        checkBox.isChecked = checkedItems[position]

        // 点击整个项目时切换 CheckBox 状态
        view.setOnClickListener {
            checkedItems[position] = !checkedItems[position]
            checkBox.isChecked = checkedItems[position]
        }

        return view
    }

    /**
     * 获取当前选中状态
     */
    fun getCheckedItems(): BooleanArray = checkedItems.copyOf()
}
