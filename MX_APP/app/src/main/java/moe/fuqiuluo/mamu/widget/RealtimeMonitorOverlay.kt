package moe.fuqiuluo.mamu.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.databinding.ItemMonitorAddressBinding
import moe.fuqiuluo.mamu.databinding.OverlayRealtimeMonitorBinding
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.data.model.SavedAddress
import moe.fuqiuluo.mamu.utils.ValueTypeUtils
import java.util.concurrent.atomic.AtomicInteger

/**
 * 实时监视悬浮窗
 */
class RealtimeMonitorOverlay(
    private val context: Context,
    private val addresses: List<SavedAddress>
) {
    companion object {
        private val monitorCounter = AtomicInteger(0)
        private val activeMonitors = mutableListOf<RealtimeMonitorOverlay>()

        fun getNextIndex(): Int = monitorCounter.incrementAndGet()

        fun clearAll() {
            activeMonitors.toList().forEach { it.dismiss() }
            monitorCounter.set(0)
        }

        /**
         * 隐藏所有监视器
         */
        fun hideAll() {
            activeMonitors.forEach { it.hide() }
        }

        /**
         * 显示所有监视器
         */
        fun showAll() {
            activeMonitors.forEach { it.unhide() }
        }
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_MX)
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var updateJob: Job? = null

    private lateinit var binding: OverlayRealtimeMonitorBinding
    private lateinit var params: WindowManager.LayoutParams

    private val monitorIndex = getNextIndex()
    private val addressViews = mutableMapOf<Long, TextView>()

    private var isShowing = false

    // 拖动相关
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (isShowing || addresses.isEmpty()) return

        binding = OverlayRealtimeMonitorBinding.inflate(LayoutInflater.from(themedContext))

        // 设置编号
        binding.monitorIndex.text = "#$monitorIndex"

        // 添加Address项
        addresses.forEach { address ->
            val itemBinding = ItemMonitorAddressBinding.inflate(
                LayoutInflater.from(themedContext),
                binding.addressListContainer,
                false
            )
            itemBinding.addressText.text = "%X".format(address.address)
            itemBinding.valueText.text = address.value
            itemBinding.typeText.text = address.displayValueType?.code ?: "?"

            binding.addressListContainer.addView(itemBinding.root)
            addressViews[address.address] = itemBinding.valueText
        }

        // Close按钮
        binding.btnClose.setOnClickListener { dismiss() }

        // 设置窗口参数
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 50
        params.y = 100 + (monitorIndex - 1) * 150 // 错开位置

        // 设置拖动
        binding.root.setOnTouchListener { _, event ->
            handleTouch(event)
        }

        windowManager.addView(binding.root, params)
        isShowing = true
        activeMonitors.add(this)

        // 启动值更新
        startValueUpdate()
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY

                if (!isDragging && (dx * dx + dy * dy > touchSlop * touchSlop)) {
                    isDragging = true
                }

                if (isDragging) {
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(binding.root, params)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                return true
            }
        }
        return false
    }

    private fun startValueUpdate() {
        updateJob = coroutineScope.launch {
            while (isActive && isShowing) {
                if (WuwaDriver.isProcessBound) {
                    addresses.forEach { address ->
                        val valueType = address.displayValueType ?: return@forEach
                        val newValue = withContext(Dispatchers.IO) {
                            try {
                                val bytes = WuwaDriver.readMemory(
                                    address.address,
                                    valueType.memorySize.toInt()
                                )
                                if (bytes != null) {
                                    ValueTypeUtils.bytesToDisplayValue(bytes, valueType)
                                } else null
                            } catch (e: Exception) {
                                null
                            }
                        }

                        newValue?.let {
                            addressViews[address.address]?.text = it
                        }
                    }
                }
                delay(100) // 100ms 刷新间隔
            }
        }
    }

    fun dismiss() {
        if (!isShowing) return

        updateJob?.cancel()
        updateJob = null

        try {
            windowManager.removeView(binding.root)
        } catch (_: Exception) {}

        isShowing = false
        activeMonitors.remove(this)
        coroutineScope.cancel()
    }

    /**
     * 临时隐藏（不销毁）
     */
    fun hide() {
        if (!isShowing) return
        binding.root.visibility = View.GONE
    }

    /**
     * Restore显示
     */
    fun unhide() {
        if (!isShowing) return
        binding.root.visibility = View.VISIBLE
    }
}
