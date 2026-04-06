package moe.fuqiuluo.mamu.floating.dialog

import android.app.Dialog
import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import moe.fuqiuluo.mamu.R

abstract class BaseDialog(
    protected val context: Context,
) {
    var onCancel: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    protected val dialog = Dialog(context, R.style.CustomDarkDialogTheme)
    private var isDialogSetup = false

    val isShowing: Boolean get() = dialog.isShowing

    init {
        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }
        )
        dialog.setOnDismissListener { onDismiss?.invoke() }
    }

    abstract fun setupDialog()

    /**
     * 在使用内置键盘时调用，强制抑制系统输入法弹出。
     * 部分 OEM（如一加 ColorOS）不完全遵守 EditText.showSoftInputOnFocus = false，
     * 需要在 Window 级别设置 SOFT_INPUT_STATE_ALWAYS_HIDDEN 并在焦点变化时主动隐藏 IME。
     */
    protected fun suppressSystemKeyboard(vararg editTexts: EditText) {
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        val focusListener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                imm.hideSoftInputFromWindow(view.windowToken, 0)
            }
        }
        editTexts.forEach { editText ->
            editText.showSoftInputOnFocus = false
            val existing = editText.onFocusChangeListener
            if (existing != null) {
                // 包装已有的 listener，确保不丢失原有逻辑
                editText.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                    existing.onFocusChange(v, hasFocus)
                    if (hasFocus) {
                        imm.hideSoftInputFromWindow(v.windowToken, 0)
                    }
                }
            } else {
                editText.onFocusChangeListener = focusListener
            }
        }
    }

    fun show() {
        // 确保 setupDialog() 只在第一次 show() 时调用，此时子类属性已完全初始化
        if (!isDialogSetup) {
            setupDialog()
            isDialogSetup = true
        }

        dialog.show()
    }

    open fun dismiss() {
        dialog.dismiss()
    }
}