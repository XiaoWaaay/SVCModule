package moe.fuqiuluo.mamu.floating.controller

import android.content.Context
import androidx.viewbinding.ViewBinding
import moe.fuqiuluo.mamu.widget.NotificationOverlay

abstract class FloatingController<T : ViewBinding>(
    protected val context: Context,
    protected val binding: T,
    protected val notification: NotificationOverlay
) {
    abstract fun initialize()

    open fun cleanup() {
        // 子类可以重写以释放资源
    }

    protected fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}