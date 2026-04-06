package moe.fuqiuluo.mamu.widget

import android.content.Context
import android.view.LayoutInflater
import android.widget.ListView
import moe.fuqiuluo.mamu.databinding.DialogMultiChoiceBinding
import moe.fuqiuluo.mamu.floating.dialog.BaseDialog

/**
 * 通用多选对话框
 */
class MultiChoiceDialog(
    context: Context,
    private val title: String = "",
    private val options: Array<String> = emptyArray(),
    private val checkedItems: BooleanArray = BooleanArray(0),
    private val itemColors: IntArray? = null,
): BaseDialog(context) {
    var onMultiChoice: ((BooleanArray) -> Unit)? = null

    override fun setupDialog() {
        // 使用 dialog.context 确保使用正确的主题
        val viewBinding = DialogMultiChoiceBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(viewBinding.root)

        // 设置Title
        viewBinding.dialogTitle.text = title

        val adapter = MultiChoiceAdapter(context, options, checkedItems, itemColors)

        // 设置列表
        val listView = viewBinding.optionList
        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_NONE // Custom Adapter 自己管理选中状态

        // OK按钮
        viewBinding.btnOk.setOnClickListener {
            val newCheckedItems = adapter.getCheckedItems()
            onMultiChoice?.invoke(newCheckedItems)
            dialog.dismiss()
        }

        // Cancel按钮
        viewBinding.btnCancel.setOnClickListener {
            onCancel?.invoke()
            dialog.dismiss()
        }
    }
}

fun Context.multiChoiceDialog(
    title: String,
    options: Array<String>,
    checkedItems: BooleanArray,
    itemColors: IntArray? = null,
    onMultiChoice: (BooleanArray) -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    val dialog = MultiChoiceDialog(
        context = this,
        title = title,
        options = options,
        checkedItems = checkedItems,
        itemColors = itemColors,
    )
    dialog.onMultiChoice = onMultiChoice
    dialog.onCancel = onCancel
    dialog.show()
}
