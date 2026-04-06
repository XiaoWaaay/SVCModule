package moe.fuqiuluo.mamu.floating.controller

import android.content.Context
import moe.fuqiuluo.mamu.databinding.FloatingBreakpointsLayoutBinding
import moe.fuqiuluo.mamu.widget.NotificationOverlay

class BreakpointController(
    context: Context,
    binding: FloatingBreakpointsLayoutBinding,
    notification: NotificationOverlay
) : FloatingController<FloatingBreakpointsLayoutBinding>(context, binding, notification) {

    override fun initialize() {
        // TODO: 实现断点功能
    }
}