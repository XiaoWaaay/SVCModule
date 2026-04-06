package moe.fuqiuluo.mamu.widget

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.databinding.OverlayNotificationBinding

enum class NotificationType {
    SUCCESS,
    ERROR,
    WARNING
}

enum class NotificationPosition {
    TOP_START,
    TOP_END,
    BOTTOM_START,
    BOTTOM_END,
    BOTTOM_CENTER
}

class NotificationOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var currentNotificationView: View? = null
    private var dismissRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    fun showSuccess(message: String, position: NotificationPosition = NotificationPosition.BOTTOM_END) {
        show(message, NotificationType.SUCCESS, position)
    }

    fun showError(message: String, position: NotificationPosition = NotificationPosition.BOTTOM_END) {
        show(message, NotificationType.ERROR, position)
    }

    fun showWarning(message: String, position: NotificationPosition = NotificationPosition.BOTTOM_END) {
        show(message, NotificationType.WARNING, position)
    }

    fun show(
        message: String,
        type: NotificationType,
        position: NotificationPosition = NotificationPosition.BOTTOM_END,
        duration: Long = 3000
    ) {
        dismissCurrent()

        val binding = OverlayNotificationBinding.inflate(LayoutInflater.from(context))
        val notificationView = binding.root

        binding.notificationMessage.text = message

        val (startColor, endColor, iconRes) = when (type) {
            NotificationType.SUCCESS -> Triple(
                Color.parseColor("#4CAF50"),
                Color.parseColor("#81C784"),
                R.drawable.icon_check_circle_24px
            )
            NotificationType.ERROR -> Triple(
                Color.parseColor("#F44336"),
                Color.parseColor("#E57373"),
                R.drawable.icon_error_24px
            )
            NotificationType.WARNING -> Triple(
                Color.parseColor("#FF9800"),
                Color.parseColor("#FFB74D"),
                R.drawable.icon_warning_24px
            )
        }

        val gradient = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(startColor, endColor)
        )
        gradient.cornerRadius = dpToPx(8f)
        binding.notificationContainer.background = gradient

        binding.notificationIcon.setImageResource(iconRes)
        binding.notificationIcon.setColorFilter(Color.WHITE)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )

        val margin = dpToPx(16f).toInt()

        when (position) {
            NotificationPosition.TOP_START -> {
                params.gravity = Gravity.TOP or Gravity.START
                params.x = margin
                params.y = margin
            }
            NotificationPosition.TOP_END -> {
                params.gravity = Gravity.TOP or Gravity.END
                params.x = margin
                params.y = margin
            }
            NotificationPosition.BOTTOM_START -> {
                params.gravity = Gravity.BOTTOM or Gravity.START
                params.x = margin
                params.y = margin
            }
            NotificationPosition.BOTTOM_END -> {
                params.gravity = Gravity.BOTTOM or Gravity.END
                params.x = margin
                params.y = margin
            }
            NotificationPosition.BOTTOM_CENTER -> {
                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                params.x = 0
                params.y = margin
            }
        }

        currentNotificationView = notificationView
        windowManager.addView(notificationView, params)

        animateSlideIn(notificationView, position) {
            dismissRunnable = Runnable {
                animateFadeOut(notificationView) {
                    removeView(notificationView)
                }
            }
            handler.postDelayed(dismissRunnable!!, duration)
        }
    }

    private fun dismissCurrent() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null

        currentNotificationView?.let { view ->
            removeView(view)
        }
        currentNotificationView = null
    }

    private fun removeView(view: View) {
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
        }
    }

    private fun animateSlideIn(view: View, position: NotificationPosition, onComplete: () -> Unit) {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels

        view.alpha = 1f

        when (position) {
            NotificationPosition.TOP_START, NotificationPosition.BOTTOM_START -> {
                view.translationX = -screenWidth.toFloat()
                view.animate()
                    .translationX(0f)
                    .setDuration(300)
                    .setInterpolator(DecelerateInterpolator())
                    .setListener(null)
                    .withEndAction(onComplete)
                    .start()
            }
            NotificationPosition.TOP_END, NotificationPosition.BOTTOM_END -> {
                view.translationX = screenWidth.toFloat()
                view.animate()
                    .translationX(0f)
                    .setDuration(300)
                    .setInterpolator(DecelerateInterpolator())
                    .setListener(null)
                    .withEndAction(onComplete)
                    .start()
            }
            NotificationPosition.BOTTOM_CENTER -> {
                view.translationY = screenHeight.toFloat()
                view.animate()
                    .translationY(0f)
                    .setDuration(300)
                    .setInterpolator(DecelerateInterpolator())
                    .setListener(null)
                    .withEndAction(onComplete)
                    .start()
            }
        }
    }

    private fun animateFadeOut(view: View, onComplete: () -> Unit) {
        view.animate()
            .alpha(0f)
            .setDuration(200)
            .setListener(null)
            .withEndAction {
                view.visibility = View.GONE
                onComplete()
            }
            .start()
    }

    private fun dpToPx(dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }

    fun destroy() {
        dismissCurrent()
        handler.removeCallbacksAndMessages(null)
    }
}
