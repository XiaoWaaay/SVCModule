package moe.fuqiuluo.mamu.widget

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import moe.fuqiuluo.mamu.databinding.ItemSingleChoiceBinding

class SingleChoiceAdapter(
    private val context: Context,
    private val options: Array<String>,
    private var selectedPosition: Int = 0,
    private val icons: Array<Int>? = null,
    private val showRadioButton: Boolean = true,
    private val textColors: Array<Int>? = null,
) : BaseAdapter() {

    override fun getCount(): Int = options.size

    override fun getItem(position: Int): String = options[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val binding = if (convertView == null) {
            ItemSingleChoiceBinding.inflate(LayoutInflater.from(context), parent, false)
        } else {
            ItemSingleChoiceBinding.bind(convertView)
        }

        binding.text1.text = options[position]

        if (textColors != null && position < textColors.size) {
            binding.text1.setTextColor(textColors[position])
        }

        if (icons != null) {
            binding.leftIcon.visibility = View.VISIBLE
            binding.leftIcon.setImageResource(icons[position])
        } else {
            binding.leftIcon.visibility = View.GONE
        }

        if (showRadioButton) {
            binding.button1.isChecked = position == selectedPosition
            binding.button1.visibility = View.VISIBLE
        } else {
            binding.button1.visibility = View.GONE
        }

        return binding.root
    }

    fun setSelectedPosition(position: Int) {
        selectedPosition = position
        notifyDataSetChanged()
    }
}
