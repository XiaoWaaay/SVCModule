package moe.fuqiuluo.mamu.floating.listener

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

class DraggableFloatingIconTouchListener(
    private val floatingIconView: View,
    private val params: WindowManager.LayoutParams,
    private val windowManager: WindowManager,
    private var touchSlop: Int = 0,
    private val showFullscreen: () -> Unit,
): View.OnTouchListener {
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    // 帧率节流：限制窗口更新频率为 60fps (约 16ms)
    // 避免每个 MOVE 事件都触发 WindowManager 重绘
    private var lastUpdateTime = 0L
    private val updateIntervalMs = 16L  // 60fps = 1000ms / 60 ≈ 16ms

    // 缓存最新位置，确保 ACTION_UP 时应用最终位置
    private var pendingX = 0
    private var pendingY = 0
    private var hasPendingUpdate = false

    override fun onTouch(v: View?, event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                hasPendingUpdate = false
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - initialTouchX
                val deltaY = event.rawY - initialTouchY

                if (!isDragging && (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)) {
                    isDragging = true
                }

                if (isDragging) {
                    pendingX = initialX + deltaX.toInt()
                    pendingY = initialY + deltaY.toInt()
                    hasPendingUpdate = true

                    // 节流：只在间隔超过 16ms 时才更新窗口位置
                    val currentTime = SystemClock.uptimeMillis()
                    if (currentTime - lastUpdateTime >= updateIntervalMs) {
                        params.x = pendingX
                        params.y = pendingY
                        windowManager.updateViewLayout(floatingIconView, params)
                        lastUpdateTime = currentTime
                        hasPendingUpdate = false
                    }
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                // 确保最终位置被应用（处理节流期间积累的最后一次移动）
                if (isDragging && hasPendingUpdate) {
                    params.x = pendingX
                    params.y = pendingY
                    windowManager.updateViewLayout(floatingIconView, params)
                }

                if (!isDragging) {
                    showFullscreen()
                }
                true
            }

            else -> false
        }
    }
}