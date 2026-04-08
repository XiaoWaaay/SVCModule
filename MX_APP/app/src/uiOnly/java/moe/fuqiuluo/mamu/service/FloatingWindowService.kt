package moe.fuqiuluo.mamu.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import moe.fuqiuluo.mamu.MainActivity
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.databinding.FloatingFullscreenLayoutBinding
import moe.fuqiuluo.mamu.databinding.FloatingWindowLayoutBinding

private const val CHANNEL_ID = "floating_ui_only"
private const val NOTIFICATION_ID = 1001

class FloatingWindowService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var iconBinding: FloatingWindowLayoutBinding
    private lateinit var panelBinding: FloatingFullscreenLayoutBinding
    private var showingPanel = false

    private val iconParams by lazy {
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 250
        }
    }

    private val panelParams by lazy {
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        iconBinding = FloatingWindowLayoutBinding.inflate(LayoutInflater.from(this))
        panelBinding = FloatingFullscreenLayoutBinding.inflate(LayoutInflater.from(this))

        iconBinding.root.setOnClickListener { togglePanel() }
        panelBinding.root.setOnClickListener { togglePanel() }
        iconBinding.root.setOnTouchListener(DragTouchListener())

        windowManager.addView(iconBinding.root, iconParams)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        if (::iconBinding.isInitialized) runCatching { windowManager.removeView(iconBinding.root) }
        if (::panelBinding.isInitialized && showingPanel) runCatching { windowManager.removeView(panelBinding.root) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun togglePanel() {
        if (showingPanel) {
            windowManager.removeView(panelBinding.root)
            showingPanel = false
        } else {
            windowManager.addView(panelBinding.root, panelParams)
            showingPanel = true
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Floating UI", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("MX Floating UI")
            .setContentText("UI-only floating mode is running")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private inner class DragTouchListener : android.view.View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downRawX = 0f
        private var downRawY = 0f

        override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = iconParams.x
                    startY = iconParams.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    return false
                }

                MotionEvent.ACTION_MOVE -> {
                    iconParams.x = startX + (event.rawX - downRawX).toInt()
                    iconParams.y = startY + (event.rawY - downRawY).toInt()
                    windowManager.updateViewLayout(iconBinding.root, iconParams)
                    return true
                }
            }
            return false
        }
    }
}
