package com.svcmonitor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FloatingMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager

    private lateinit var iconView: ImageView
    private lateinit var panelView: LinearLayout
    private lateinit var tvState: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLogs: TextView

    private lateinit var spinnerApp: Spinner
    private lateinit var spinnerPreset: Spinner
    private lateinit var etNrs: EditText
    private lateinit var logsScroll: ScrollView
    private lateinit var tabMonitor: View
    private lateinit var tabFilter: View
    private lateinit var tabLogs: View
    private lateinit var tabSettings: View
    private lateinit var etFilterSearch: EditText
    private lateinit var llAllFilterItems: LinearLayout
    private val selectedNrs = linkedSetOf<Int>()

    private var appList: List<AppInfo> = emptyList()
    private var selectedApp: AppInfo? = null

    private var fileOffset = 0L
    private var tailBuf = ByteArray(0)
    private var useJsonFallback = false
    private var emptyBinPolls = 0
    private val eventBuffer = ArrayDeque<StatusParser.SvcEvent>(300)
    private val floatingLogFile by lazy { File(getExternalFilesDir(null), "svc_floating_latest.log") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        try {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            ensureChannel()
            resetFloatingLog()
            logLine("Floating service starting")
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTI_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTI_ID, notification)
            }
            setupFloatingViews()
            startPolling()
        } catch (e: Exception) {
            logLine("FATAL start error: ${e.message}")
            Toast.makeText(this, "Failed to start floating monitor: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        runCatching { wm.removeView(iconView) }
        runCatching { wm.removeView(panelView) }
    }

    private fun setupFloatingViews() {
        val iconParams = WindowManager.LayoutParams(
            dp(56),
            dp(56),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(160)
        }

        iconView = ImageView(this).apply {
            setImageResource(android.R.drawable.sym_def_app_icon)
            setBackgroundColor(Color.parseColor("#CC1565C0"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { togglePanel() }
            setOnLongClickListener {
                stopSelf()
                true
            }
            setOnTouchListener(DragTouchListener(iconParams))
        }

        val panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        panelView = buildPanel().apply { visibility = View.GONE }

        wm.addView(iconView, iconParams)
        wm.addView(panelView, panelParams)
    }

    private fun buildPanel(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC101418"))
            setPadding(dp(10), dp(26), dp(10), dp(10))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F4FFFFFF"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(card)

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "SVC Floating Monitor"
            setTextColor(Color.BLACK)
            textSize = 16f
            setPadding(0, 0, 0, dp(6))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(title)
        titleRow.addView(makeBtn("—") { panelView.visibility = View.GONE }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        titleRow.addView(makeBtn("×") { stopSelf() }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        card.addView(titleRow)

        tvState = TextView(this).apply {
            text = "Overlay: Running"
            setTextColor(Color.parseColor("#2E7D32"))
        }
        card.addView(tvState)

        tvStatus = TextView(this).apply {
            text = "Status: loading..."
            setTextColor(Color.DKGRAY)
        }
        card.addView(tvStatus)

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        card.addView(tabBar)

        val content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        card.addView(content)

        // Monitor tab
        tabMonitor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        spinnerApp = Spinner(this)
        (tabMonitor as LinearLayout).addView(spinnerApp)
        refreshApps()
        spinnerPreset = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@FloatingMonitorService,
                android.R.layout.simple_spinner_dropdown_item,
                StatusParser.presets.map { "${it.name} (${it.description})" }
            )
        }
        (tabMonitor as LinearLayout).addView(spinnerPreset)
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(makeBtn("Refresh Apps") { refreshApps() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(makeBtn("Preset") { applyPreset() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        (tabMonitor as LinearLayout).addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(makeBtn("Start") { startMonitoring() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(makeBtn("Stop") { stopMonitoring() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        (tabMonitor as LinearLayout).addView(row2)

        // Filter tab
        tabFilter = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        etNrs = EditText(this).apply {
            hint = "NR list: 56,63,64"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        (tabFilter as LinearLayout).addView(etNrs)

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(makeBtn("Set NRs") { setNrs() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row3.addView(makeBtn("Apply selected") { applySelectedNrs() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        (tabFilter as LinearLayout).addView(row3)

        val row4 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row4.addView(makeBtn("Clear") {
            scope.launch(Dispatchers.IO) {
                KpmBridge.clear()
                KpmBridge.clearEventFile()
                fileOffset = 0
                tailBuf = ByteArray(0)
                useJsonFallback = false
                emptyBinPolls = 0
                eventBuffer.clear()
                launch(Dispatchers.Main) { renderLogs() }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row4.addView(makeBtn("Disable all") {
            scope.launch(Dispatchers.IO) { KpmBridge.disableAll() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        (tabFilter as LinearLayout).addView(row4)

        etFilterSearch = EditText(this).apply {
            hint = "Search syscall by nr/name"
            inputType = InputType.TYPE_CLASS_TEXT
            setOnEditorActionListener { _, _, _ ->
                renderAllFilters(text?.toString().orEmpty())
                true
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    renderAllFilters(s?.toString().orEmpty())
                }
            })
        }
        (tabFilter as LinearLayout).addView(etFilterSearch)

        val filterScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = dp(6) }
        }
        llAllFilterItems = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        filterScroll.addView(llAllFilterItems)
        (tabFilter as LinearLayout).addView(filterScroll)
        renderAllFilters("")

        // Logs tab
        val logsActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        logsActions.addView(makeBtn("Share latest log") { shareLatestFloatingLog() }, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        logsActions.addView(makeBtn("Clear latest log") {
            resetFloatingLog()
            logLine("Log file reset by user")
            Toast.makeText(this, "Latest log reset", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))

        logsScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.parseColor("#111111"))
        }

        tvLogs = TextView(this).apply {
            text = "No logs yet"
            setTextColor(Color.parseColor("#66FF66"))
            textSize = 11f
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        logsScroll.addView(tvLogs)
        tabLogs = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(logsActions)
            addView(logsScroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }

        // Settings tab
        val prefs = getSharedPreferences("svcmon_prefs", MODE_PRIVATE)
        tabSettings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val etHost = EditText(this@FloatingMonitorService).apply {
                hint = "Relay host"
                setText(prefs.getString("pc_relay_host", "127.0.0.1"))
            }
            addView(etHost)
            val etPort = EditText(this@FloatingMonitorService).apply {
                hint = "Relay port"
                inputType = InputType.TYPE_CLASS_NUMBER
                setText((prefs.getInt("pc_relay_port", 5001)).toString())
            }
            addView(etPort)
            addView(makeBtn("Save settings") {
                val host = etHost.text?.toString()?.trim().orEmpty().ifBlank { "127.0.0.1" }
                val port = etPort.text?.toString()?.toIntOrNull() ?: 5001
                prefs.edit().putString("pc_relay_host", host).putInt("pc_relay_port", port).apply()
                Toast.makeText(this@FloatingMonitorService, "Saved", Toast.LENGTH_SHORT).show()
            })
        }

        content.addView(tabMonitor)
        content.addView(tabFilter)
        content.addView(tabLogs)
        content.addView(tabSettings)

        fun activateTab(target: View) {
            tabMonitor.visibility = if (target == tabMonitor) View.VISIBLE else View.GONE
            tabFilter.visibility = if (target == tabFilter) View.VISIBLE else View.GONE
            tabLogs.visibility = if (target == tabLogs) View.VISIBLE else View.GONE
            tabSettings.visibility = if (target == tabSettings) View.VISIBLE else View.GONE
        }
        tabBar.addView(makeBtn("Monitor") { activateTab(tabMonitor) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabBar.addView(makeBtn("Filter") { activateTab(tabFilter) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabBar.addView(makeBtn("Logs") { activateTab(tabLogs) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabBar.addView(makeBtn("Settings") { activateTab(tabSettings) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        activateTab(tabMonitor)

        return root
    }

    private fun makeBtn(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }
    }

    private fun refreshApps() {
        appList = AppResolver.getAllApps(this, hideSystemApps = false, onlyLaunchableApps = true)
        spinnerApp.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Select app") + appList.map { "${it.label} (${it.uid})" }
        )
        spinnerApp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedApp = if (position > 0) appList[position - 1] else null
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                selectedApp = null
            }
        }
    }

    private fun applyPreset() {
        val preset = StatusParser.presets.getOrNull(spinnerPreset.selectedItemPosition) ?: return
        scope.launch(Dispatchers.IO) {
            KpmBridge.preset(preset.id)
            logLine("Preset applied: ${preset.id}")
            launch(Dispatchers.Main) {
                Toast.makeText(this@FloatingMonitorService, "Preset ${preset.name} applied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setNrs() {
        val nrs = etNrs.text.toString()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .distinct()
        if (nrs.isEmpty()) {
            Toast.makeText(this, "Invalid NR list", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch(Dispatchers.IO) {
            KpmBridge.setNrs(nrs)
            selectedNrs.clear()
            selectedNrs.addAll(nrs)
            logLine("Set NRs (${nrs.size}): ${nrs.joinToString(",")}")
            launch(Dispatchers.Main) { renderAllFilters(etFilterSearch.text?.toString().orEmpty()) }
        }
    }

    private fun applySelectedNrs() {
        val nrs = selectedNrs.toList().sorted()
        etNrs.setText(nrs.joinToString(","))
        scope.launch(Dispatchers.IO) {
            if (nrs.isEmpty()) KpmBridge.disableAll() else KpmBridge.setNrs(nrs)
            logLine("Applied selected filters: ${nrs.size}")
        }
    }

    private fun renderAllFilters(rawQuery: String) {
        if (!::llAllFilterItems.isInitialized) return
        val q = rawQuery.trim().lowercase()
        llAllFilterItems.removeAllViews()
        val all = StatusParser.categories
            .flatMap { it.syscalls }
            .distinctBy { it.nr }
            .sortedBy { it.nr }
            .filter { e ->
                q.isBlank() ||
                    e.name.lowercase().contains(q) ||
                    e.nr.toString().contains(q) ||
                    e.description.lowercase().contains(q)
            }
        if (all.isEmpty()) {
            llAllFilterItems.addView(TextView(this).apply {
                text = "No matching syscall"
                setTextColor(Color.parseColor("#8892A6"))
            })
            return
        }
        for (sc in all) {
            val cb = android.widget.CheckBox(this).apply {
                text = "${sc.name} (${sc.nr}) - ${sc.description}"
                isChecked = selectedNrs.contains(sc.nr)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedNrs.add(sc.nr) else selectedNrs.remove(sc.nr)
                    etNrs.setText(selectedNrs.toList().sorted().joinToString(","))
                }
            }
            llAllFilterItems.addView(cb)
        }
    }

    private fun startMonitoring() {
        val app = selectedApp
        if (app == null) {
            Toast.makeText(this, "Select target app first", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch(Dispatchers.IO) {
            KpmBridge.setUid(app.uid)
            KpmBridge.enable()
            KpmBridge.clearEventFile()
            fileOffset = 0
            tailBuf = ByteArray(0)
            useJsonFallback = false
            emptyBinPolls = 0
            logLine("Monitoring started for uid=${app.uid} pkg=${app.packageName}")
        }
    }

    private fun stopMonitoring() {
        scope.launch(Dispatchers.IO) {
            KpmBridge.disable()
            logLine("Monitoring stopped")
        }
    }

    private fun startPolling() {
        scope.launch {
            while (isActive) {
                pollOnce()
                delay(700)
            }
        }
    }

    private suspend fun pollOnce() {
        val s = kotlinx.coroutines.withContext(Dispatchers.IO) { KpmBridge.status() }
        if (s.success && s.output.isNotEmpty()) {
            val status = StatusParser.parseStatus(s.output)
            tvStatus.text = "Status: ${if (status.enabled) "Running" else "Stopped"}, uid=${status.targetUid}, events=${status.eventsTotal}"

            if (status.enabled) {
                val chunk = kotlinx.coroutines.withContext(Dispatchers.IO) { KpmBridge.readEventFileChunk(fileOffset, 128 * 1024) }
                if (chunk.isNotEmpty()) {
                    fileOffset += chunk.size
                    val merged = ByteArray(tailBuf.size + chunk.size)
                    System.arraycopy(tailBuf, 0, merged, 0, tailBuf.size)
                    System.arraycopy(chunk, 0, merged, tailBuf.size, chunk.size)
                    val parsed = BinEventParser.parse(merged)
                    tailBuf = if (parsed.consumedBytes in 1 until merged.size) {
                        merged.copyOfRange(parsed.consumedBytes, merged.size)
                    } else ByteArray(0)
                    pushEvents(parsed.events)
                    emptyBinPolls = 0
                } else {
                    emptyBinPolls++
                }

                if (emptyBinPolls >= 6) useJsonFallback = true
                if (useJsonFallback) {
                    val dr = kotlinx.coroutines.withContext(Dispatchers.IO) { KpmBridge.drain(128) }
                    if (dr.success && dr.output.isNotEmpty()) {
                        val parsed = StatusParser.parseDrain(dr.output)
                        if (parsed.ok) pushEvents(parsed.events)
                    }
                }
            }
        } else {
            logLine("status() failed: ${s.error.ifBlank { "no output" }}")
        }
    }

    private fun pushEvents(events: List<StatusParser.SvcEvent>) {
        if (events.isEmpty()) return
        for (e in events) {
            while (eventBuffer.size >= 220) {
                if (eventBuffer.isNotEmpty()) eventBuffer.removeFirst()
            }
            eventBuffer.addLast(e)
        }
        renderLogs()
    }

    private fun renderLogs() {
        if (eventBuffer.isEmpty()) {
            tvLogs.text = "No logs yet"
            return
        }
        val sb = StringBuilder()
        val start = (eventBuffer.size - 160).coerceAtLeast(0)
        var idx = 0
        for (it in eventBuffer) {
            if (idx++ < start) continue
            sb.append('#').append(it.seq)
                .append(' ')
                .append(it.name)
                .append(" pid=")
                .append(it.pid)
                .append(" uid=")
                .append(it.uid)
                .append(' ')
                .append(it.desc)
                .append('\n')
        }
        tvLogs.text = sb.toString()
        logsScroll.post { logsScroll.fullScroll(View.FOCUS_DOWN) }
        if (eventBuffer.isNotEmpty()) {
            val e = eventBuffer.last()
            logLine("event #${e.seq} ${e.name} pid=${e.pid} uid=${e.uid} ${e.desc}")
        }
    }

    private fun togglePanel() {
        panelView.visibility = if (panelView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "SVC Floating Monitor", NotificationManager.IMPORTANCE_LOW)
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this,
            1002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("SVC floating monitor")
            .setContentText("Overlay running in background")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun resetFloatingLog() {
        runCatching {
            val parent = floatingLogFile.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            if (floatingLogFile.exists()) floatingLogFile.delete()
            floatingLogFile.createNewFile()
        }
    }

    private fun logLine(msg: String) {
        runCatching {
            rotateLogIfNeeded()
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            floatingLogFile.appendText("[$ts] $msg\n")
        }
    }

    private fun rotateLogIfNeeded() {
        if (!floatingLogFile.exists()) return
        val maxBytes = 2 * 1024 * 1024L
        if (floatingLogFile.length() <= maxBytes) return
        val bak = File(floatingLogFile.parentFile, "svc_floating_prev.log")
        if (bak.exists()) bak.delete()
        floatingLogFile.renameTo(bak)
        floatingLogFile.createNewFile()
    }

    private fun shareLatestFloatingLog() {
        try {
            if (!floatingLogFile.exists()) {
                Toast.makeText(this, "No latest log found", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", floatingLogFile)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(Intent.createChooser(i, "Share floating latest log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class DragTouchListener(
        private val params: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var sx = 0
        private var sy = 0
        private var tx = 0f
        private var ty = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = params.x
                    sy = params.y
                    tx = event.rawX
                    ty = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = sx + (event.rawX - tx).toInt()
                    params.y = sy + (event.rawY - ty).toInt()
                    wm.updateViewLayout(v, params)
                }
            }
            return false
        }
    }

    companion object {
        private const val CHANNEL_ID = "svc_floating_monitor"
        private const val NOTI_ID = 1001
    }
}
