package com.svcmonitor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class FloatingMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager

    private lateinit var iconView: ImageView
    private lateinit var panelView: LinearLayout
    private lateinit var tvState: TextView
    private lateinit var tvLogs: TextView

    // Tabs
    private lateinit var tabMonitor: View
    private lateinit var tabFilter: View
    private lateinit var tabEvents: LinearLayout
    private lateinit var tabThreads: LinearLayout
    private lateinit var tabLogs: LinearLayout

    // Monitor tab widgets
    private lateinit var etAppSearch: EditText
    private lateinit var spinnerApp: Spinner
    private lateinit var tvVersion: TextView
    private lateinit var tvUid: TextView
    private lateinit var tvEventCount: TextView
    private lateinit var tvMonState: TextView
    private lateinit var tvPresetState: TextView
    private lateinit var btnStartStop: Button
    private lateinit var tvStatusCard: TextView
    private lateinit var tvDashNrCount: TextView
    private lateinit var tvDashNrList: TextView

    // Filter tab widgets
    private lateinit var switchDoFilpOpen: Switch
    private lateinit var tvNrCount: TextView
    private lateinit var tvNrList: TextView
    private lateinit var llSelectedNrs: LinearLayout
    private lateinit var etAllNrFilter: EditText
    private lateinit var llAllNrList: LinearLayout
    private lateinit var llAllFilterItems: LinearLayout
    private val selectedNrs = linkedSetOf<Int>()
    private var currentNrList: List<Int> = emptyList()

    // Events tab widgets
    private lateinit var evtListContainer: LinearLayout
    private lateinit var evtSearchEdit: EditText
    private lateinit var evtCountTv: TextView
    private var eventFilterQuery = ""

    // Threads tab widgets
    private lateinit var etTgid: EditText
    private lateinit var tvThreadOut: TextView

    // Data
    private var appList: List<AppInfo> = emptyList()
    private var selectedApp: AppInfo? = null

    private var fileOffset = 0L
    private var tailBuf = ByteArray(0)
    private var useJsonFallback = false
    private var emptyBinPolls = 0
    private val eventBuffer = ArrayDeque<StatusParser.SvcEvent>(500)
    private val floatingLogFile by lazy { File(getExternalFilesDir(null), "svc_floating_latest.log") }
    private var mapsAutoJob: Job? = null
    private var lastPresetLabel: String = "(none)"
    private var presetPinnedUntilMs: Long = 0L

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
            startStatusUpdater()
        } catch (e: Exception) {
            logLine("FATAL start error: ${e.message}")
            Toast.makeText(this, "Failed to start floating monitor: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mapsAutoJob?.cancel()
        mapsAutoJob = null
        scope.cancel()
        runCatching { wm.removeView(iconView) }
        runCatching { wm.removeView(panelView) }
    }

    private fun setupFloatingViews() {
        val iconParams = WindowManager.LayoutParams(
            dp(56), dp(56),
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        card.addView(tabBar)

        val content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        card.addView(content)

        // ==================== Monitor Tab ====================
        val monitorInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        tabMonitor = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(monitorInner)
        }

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        statusCard.addView(TextView(this).apply { text = "Module Status"; setTextColor(Color.parseColor("#1565C0")); typeface = Typeface.DEFAULT_BOLD })
        tvStatusCard = TextView(this).apply { text = "Status: Unknown" }
        tvVersion = TextView(this).apply { text = "Version: —" }
        tvUid = TextView(this).apply { text = "Target UID: —" }
        tvEventCount = TextView(this).apply { text = "Event count: 0" }
        tvMonState = TextView(this).apply { text = "Monitoring: Not started"; setTextColor(Color.GRAY) }
        tvPresetState = TextView(this).apply { text = "Preset: $lastPresetLabel"; setTextColor(Color.DKGRAY) }
        statusCard.addView(tvStatusCard)
        statusCard.addView(tvVersion)
        statusCard.addView(tvUid)
        statusCard.addView(tvEventCount)
        statusCard.addView(tvMonState)
        statusCard.addView(tvPresetState)
        monitorInner.addView(statusCard)

        monitorInner.addView(TextView(this).apply { text = "Select target app"; setTextColor(Color.BLACK); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, dp(4)) })
        etAppSearch = EditText(this).apply { hint = "Search app / package name"; inputType = InputType.TYPE_CLASS_TEXT; addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { refreshAppList(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }) }
        monitorInner.addView(etAppSearch)
        spinnerApp = Spinner(this)
        monitorInner.addView(spinnerApp)
        refreshAppList("")
        monitorInner.addView(makeBtn("Refresh Apps") { refreshAppList(etAppSearch.text.toString()) })

        val selectedCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        selectedCard.addView(TextView(this).apply { text = "Step 2: Selected syscalls (manage in Filter tab)"; setTextColor(Color.BLACK); typeface = Typeface.DEFAULT_BOLD })
        tvDashNrCount = TextView(this).apply { text = "Selected: 0 syscalls"; setTextColor(Color.DKGRAY) }
        tvDashNrList = TextView(this).apply { text = "NR list: (empty)"; setTextColor(Color.DKGRAY); maxLines = 6; ellipsize = TextUtils.TruncateAt.END }
        selectedCard.addView(tvDashNrCount)
        selectedCard.addView(tvDashNrList)
        monitorInner.addView(selectedCard)

        btnStartStop = Button(this).apply {
            text = "One-tap start monitoring"
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            setOnClickListener { onStartStopClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        monitorInner.addView(btnStartStop)

        // ==================== Filter Tab ====================
        tabFilter = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val filterScroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        val filterInner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        filterScroll.addView(filterInner)
        (tabFilter as LinearLayout).addView(filterScroll)

        filterInner.addView(TextView(this).apply {
            text = "Extra Hooks"
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(4))
        })
        switchDoFilpOpen = Switch(this).apply {
            text = "Enable do_filp_open (lower-level open path)"
            isChecked = false
            setOnCheckedChangeListener { _, checked ->
                scope.launch(Dispatchers.IO) { KpmBridge.setDoFilpOpen(checked) }
            }
        }
        filterInner.addView(switchDoFilpOpen)

        filterInner.addView(TextView(this).apply { text = "Current NR filter"; setTextColor(Color.BLACK); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, dp(4)) })
        tvNrCount = TextView(this).apply { text = "Selected: 0 syscalls" }
        tvNrList = TextView(this).apply { text = "NR list: (empty)" }
        filterInner.addView(tvNrCount)
        filterInner.addView(tvNrList)

        llSelectedNrs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        filterInner.addView(llSelectedNrs)

        filterInner.addView(Button(this).apply {
            text = "Clear selected NRs"
            setTextColor(Color.parseColor("#C62828"))
            setOnClickListener { vmDisableAll() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        })

        filterInner.addView(TextView(this).apply { text = "Quick apply preset"; setTextColor(Color.BLACK); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, dp(4)) })
        StatusParser.presets.forEach { preset ->
            filterInner.addView(Button(this).apply {
                text = "${preset.name}: ${preset.description}"
                isAllCaps = false
                setOnClickListener { applyPreset(preset.id) }
            })
        }

        filterInner.addView(TextView(this).apply { text = "Rule Sets"; setTextColor(Color.BLACK); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, dp(4)) })
        filterInner.addView(makeBtn("Capture file I/O") { vmSetNrs(RuleSets.FILE_IO.toList()) })
        filterInner.addView(makeBtn("Capture network requests") { vmSetNrs(RuleSets.NETWORK.toList()) })
        filterInner.addView(makeBtn("Anti-debug detection") { vmSetNrs(RuleSets.ANTI_DEBUG.toList()) })
        filterInner.addView(makeBtn("Process lifecycle") { vmSetNrs(RuleSets.PROCESS.toList()) })
        filterInner.addView(makeBtn("Memory ops/injection") { vmSetNrs(RuleSets.MEMORY.toList()) })

        filterInner.addView(TextView(this).apply { text = "Manual NR management"; setTextColor(Color.BLACK); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, dp(4)) })
        val etNr = EditText(this).apply { hint = "Enter NR number (e.g. 56)"; inputType = InputType.TYPE_CLASS_NUMBER }
        filterInner.addView(etNr)
        val manualRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        manualRow.addView(Button(this).apply {
            text = "Add"
            setOnClickListener {
                val nr = etNr.text.toString().toIntOrNull() ?: return@setOnClickListener
                selectedNrs.add(nr)
                refreshSelectedNrsDisplay()
                etNr.text.clear()
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        manualRow.addView(Button(this).apply {
            text = "Remove"
            setOnClickListener {
                val nr = etNr.text.toString().toIntOrNull() ?: return@setOnClickListener
                selectedNrs.remove(nr)
                refreshSelectedNrsDisplay()
                etNr.text.clear()
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        filterInner.addView(manualRow)
        filterInner.addView(makeBtn("Apply selected NRs") { applySelectedNrs() })

        filterInner.addView(TextView(this).apply { text = "Select syscalls by category"; setTextColor(Color.BLACK); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, dp(4)) })
        llAllFilterItems = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        filterInner.addView(llAllFilterItems)
        renderAllFilters("")

        filterInner.addView(TextView(this).apply { text = "All ARM64 SVC numbers (0-459)"; setTextColor(Color.BLACK); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(12), 0, dp(4)) })
        etAllNrFilter = EditText(this).apply { hint = "Filter: nr / name"; addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { renderAllNrList(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }) }
        filterInner.addView(etAllNrFilter)
        llAllNrList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        filterInner.addView(llAllNrList)
        renderAllNrList("")

        // ==================== Events Tab ====================
        tabEvents = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val evtTopBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        evtCountTv = TextView(this).apply { text = "Events: 0"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        evtTopBar.addView(evtCountTv)
        evtTopBar.addView(makeBtn("CSV") { exportEventsToCsv() })
        evtTopBar.addView(makeBtn("JSONL") { exportEventsToJsonl() })
        evtTopBar.addView(makeBtn("Clear") { clearEvents() })
        tabEvents.addView(evtTopBar)

        evtSearchEdit = EditText(this).apply { hint = "Search events (text/nr/pid)"; addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { eventFilterQuery = s.toString().trim().lowercase(); renderEventList() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }) }
        tabEvents.addView(evtSearchEdit)

        val evtScroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        evtListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        evtScroll.addView(evtListContainer)
        tabEvents.addView(evtScroll)

        // ==================== Threads Tab ====================
        tabThreads = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val tgidRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        etTgid = EditText(this).apply { hint = "TGID (process ID)"; inputType = InputType.TYPE_CLASS_NUMBER; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        tgidRow.addView(etTgid)
        tgidRow.addView(makeBtn("Analyze") { analyzeThreads() })
        tabThreads.addView(tgidRow)

        tvThreadOut = TextView(this).apply { setTextSize(11f); typeface = Typeface.MONOSPACE; setPadding(dp(4), dp(8), dp(4), dp(8)) }
        val threadScroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        threadScroll.addView(tvThreadOut)
        tabThreads.addView(threadScroll)

        // ==================== Logs Tab ====================
        val logsActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        logsActions.addView(makeBtn("Share latest log") { shareLatestFloatingLog() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        logsActions.addView(makeBtn("Clear latest log") { resetFloatingLog(); logLine("Log file reset by user"); Toast.makeText(this, "Latest log reset", Toast.LENGTH_SHORT).show() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val logsScroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); setBackgroundColor(Color.parseColor("#111111")) }
        tvLogs = TextView(this).apply { text = "No logs yet"; setTextColor(Color.parseColor("#66FF66")); textSize = 11f; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        logsScroll.addView(tvLogs)
        tabLogs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(logsActions); addView(logsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }

        content.addView(tabMonitor)
        content.addView(tabFilter)
        content.addView(tabEvents)
        content.addView(tabThreads)
        content.addView(tabLogs)

        fun activateTab(target: View) {
            tabMonitor.visibility = if (target == tabMonitor) View.VISIBLE else View.GONE
            tabFilter.visibility = if (target == tabFilter) View.VISIBLE else View.GONE
            tabEvents.visibility = if (target == tabEvents) View.VISIBLE else View.GONE
            tabThreads.visibility = if (target == tabThreads) View.VISIBLE else View.GONE
            tabLogs.visibility = if (target == tabLogs) View.VISIBLE else View.GONE
        }

        tabBar.addView(makeBtn("Monitor") { activateTab(tabMonitor) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabBar.addView(makeBtn("Filter") { activateTab(tabFilter) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabBar.addView(makeBtn("Events") { activateTab(tabEvents) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabBar.addView(makeBtn("Threads") { activateTab(tabThreads) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabBar.addView(makeBtn("Logs") { activateTab(tabLogs) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        activateTab(tabMonitor)

        return root
    }

    private fun makeBtn(text: String, onClick: () -> Unit): Button = Button(this).apply { this.text = text; isAllCaps = false; setOnClickListener { onClick() } }

    private fun refreshAppList(query: String) {
        appList = if (query.isBlank()) AppResolver.getAllApps(this, hideSystemApps = false, onlyLaunchableApps = true)
                 else AppResolver.searchApps(this, query, hideSystemApps = false, onlyLaunchableApps = true)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Select app") + appList.map { "${it.label} (${it.packageName})" })
        spinnerApp.adapter = adapter
        spinnerApp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedApp = if (position > 0) appList[position - 1] else null
            }
            override fun onNothingSelected(parent: AdapterView<*>?) { selectedApp = null }
        }
    }

    private fun applyPreset(presetId: String) {
        val preset = StatusParser.presets.firstOrNull { it.id == presetId } ?: return
        scope.launch(Dispatchers.IO) {
            val r = KpmBridge.preset(preset.id)
            if (r.success) {
                logLine("Preset applied: ${preset.id}")
                launch(Dispatchers.Main) {
                    lastPresetLabel = preset.name
                    presetPinnedUntilMs = System.currentTimeMillis() + 8000L
                    tvPresetState.text = "Preset: $lastPresetLabel"
                    Toast.makeText(this@FloatingMonitorService, "Preset ${preset.name} applied", Toast.LENGTH_SHORT).show()
                }
                syncSelectionFromStatus()
            } else {
                logLine("Preset apply failed: ${preset.id}: ${r.error}")
                launch(Dispatchers.Main) {
                    Toast.makeText(this@FloatingMonitorService, "Preset failed: ${r.error}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun vmSetNrs(nrs: List<Int>) {
        scope.launch(Dispatchers.IO) {
            KpmBridge.setNrs(nrs)
            selectedNrs.clear()
            selectedNrs.addAll(nrs)
            launch(Dispatchers.Main) {
                tvPresetState.text = "Preset: Custom"
                lastPresetLabel = "Custom"
                presetPinnedUntilMs = System.currentTimeMillis() + 5000L
                refreshSelectedNrsDisplay()
                renderAllFilters(etAllNrFilter.text.toString())
                renderAllNrList(etAllNrFilter.text.toString())
                Toast.makeText(this@FloatingMonitorService, "Set ${nrs.size} syscalls", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun vmDisableAll() {
        scope.launch(Dispatchers.IO) {
            KpmBridge.disableAll()
            selectedNrs.clear()
            launch(Dispatchers.Main) {
                tvPresetState.text = "Preset: (none)"
                lastPresetLabel = "(none)"
                presetPinnedUntilMs = 0L
                refreshSelectedNrsDisplay()
                renderAllFilters(etAllNrFilter.text.toString())
                renderAllNrList(etAllNrFilter.text.toString())
                Toast.makeText(this@FloatingMonitorService, "All NRs disabled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applySelectedNrs() {
        val nrs = selectedNrs.toList().sorted()
        scope.launch(Dispatchers.IO) {
            if (nrs.isEmpty()) KpmBridge.disableAll() else KpmBridge.setNrs(nrs)
            launch(Dispatchers.Main) {
                tvPresetState.text = "Preset: Custom"
                lastPresetLabel = "Custom"
                presetPinnedUntilMs = System.currentTimeMillis() + 5000L
                refreshSelectedNrsDisplay()
                renderAllFilters(etAllNrFilter.text.toString())
                renderAllNrList(etAllNrFilter.text.toString())
            }
            syncSelectionFromStatus()
        }
    }

    private suspend fun syncSelectionFromStatus() {
        val s = KpmBridge.status()
        if (!s.success || s.output.isBlank()) return
        val status = StatusParser.parseStatus(s.output)
        selectedNrs.clear()
        selectedNrs.addAll(status.nrList)
        currentNrList = status.nrList
        withContext(Dispatchers.Main) {
            refreshSelectedNrsDisplay()
            renderAllFilters(etAllNrFilter.text.toString())
            renderAllNrList(etAllNrFilter.text.toString())
        }
    }

    private fun renderAllFilters(rawQuery: String) {
        if (!::llAllFilterItems.isInitialized) return
        val q = rawQuery.trim().lowercase()
        llAllFilterItems.removeAllViews()

        for (category in StatusParser.categories) {
            val matching = category.syscalls.filter { sc ->
                q.isBlank() || sc.name.lowercase().contains(q) || sc.nr.toString().contains(q) || sc.description.lowercase().contains(q)
            }
            if (matching.isEmpty()) continue

            val catLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(4)) }
            val catCheckBox = CheckBox(this).apply { text = "${category.icon} ${category.name}"; setTextColor(Color.parseColor("#1565C0")); typeface = Typeface.DEFAULT_BOLD; isChecked = matching.all { selectedNrs.contains(it.nr) } }
            catLayout.addView(catCheckBox)
            llAllFilterItems.addView(catLayout)

            val syscallsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
            for (sc in matching) {
                val cb = CheckBox(this).apply {
                    text = "${sc.name} (${sc.nr}) - ${sc.description}"
                    isChecked = selectedNrs.contains(sc.nr)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedNrs.add(sc.nr) else selectedNrs.remove(sc.nr)
                        val allChecked = matching.all { selectedNrs.contains(it.nr) }
                        catCheckBox.isChecked = allChecked
                        refreshSelectedNrsDisplay()
                    }
                }
                syscallsContainer.addView(cb)
            }
            llAllFilterItems.addView(syscallsContainer)

            catCheckBox.setOnCheckedChangeListener { _, isChecked ->
                for (sc in matching) { if (isChecked) selectedNrs.add(sc.nr) else selectedNrs.remove(sc.nr) }
                for (i in 0 until syscallsContainer.childCount) {
                    (syscallsContainer.getChildAt(i) as? CheckBox)?.isChecked = isChecked
                }
                refreshSelectedNrsDisplay()
            }
        }
        if (llAllFilterItems.childCount == 0) {
            llAllFilterItems.addView(TextView(this).apply { text = "No matching syscall"; setTextColor(Color.GRAY) })
        }
    }

    private fun refreshSelectedNrsDisplay() {
        val sorted = selectedNrs.toList().sorted()
        llSelectedNrs.removeAllViews()
        tvNrCount.text = "Selected: ${sorted.size} syscalls"
        tvDashNrCount.text = "Selected: ${sorted.size} syscalls"
        val nrLine = if (sorted.isEmpty()) "(empty)" else sorted.joinToString(", ")
        tvNrList.text = "NR list: $nrLine"
        tvDashNrList.text = "NR list: $nrLine"
        if (sorted.isEmpty()) {
            llSelectedNrs.addView(TextView(this).apply { text = "No syscalls selected"; setTextColor(Color.GRAY) })
        } else {
            for (nr in sorted) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(2)) }
                row.addView(TextView(this).apply { text = "${StatusParser.nrToName(nr)}($nr)"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
                row.addView(Button(this).apply { text = "Delete"; setTextColor(Color.RED); setOnClickListener { removeNr(nr) } })
                llSelectedNrs.addView(row)
            }
        }
    }

    private fun renderAllNrList(query: String) {
        if (!::llAllNrList.isInitialized) return
        llAllNrList.removeAllViews()
        val q = query.trim().lowercase()
        val selected = currentNrList.toHashSet()
        var shown = 0
        for (nr in 0..459) {
            val name = StatusParser.nrToName(nr)
            if (q.isNotEmpty() && !nr.toString().contains(q) && !name.lowercase().contains(q)) continue
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(2)) }
            row.addView(TextView(this).apply { text = nr.toString(); typeface = Typeface.MONOSPACE; textSize = 12f; setTextColor(Color.GRAY); minWidth = dp(48) })
            row.addView(TextView(this).apply { text = name; textSize = 13f; setTextColor(if (selected.contains(nr)) Color.parseColor("#2E7D32") else Color.BLACK); typeface = if (selected.contains(nr)) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            row.addView(Button(this).apply { text = "+"; setOnClickListener { addNr(nr) } })
            row.addView(Button(this).apply { text = "-"; setOnClickListener { removeNr(nr) } })
            llAllNrList.addView(row)
            shown++
            if (shown >= 200) break
        }
        if (shown == 0) llAllNrList.addView(TextView(this).apply { text = "No matches"; setTextColor(Color.GRAY); setPadding(0, dp(4), 0, dp(4)) })
        else if (shown >= 200) llAllNrList.addView(TextView(this).apply { text = "Showing first 200 items only"; setTextColor(Color.GRAY); setPadding(0, dp(4), 0, dp(4)) })
    }

    private fun addNr(nr: Int) {
        scope.launch(Dispatchers.IO) {
            KpmBridge.enableNr(nr)
            selectedNrs.add(nr)
            launch(Dispatchers.Main) {
                tvPresetState.text = "Preset: Custom"
                lastPresetLabel = "Custom"
                presetPinnedUntilMs = System.currentTimeMillis() + 5000L
                refreshSelectedNrsDisplay()
                renderAllFilters(etAllNrFilter.text.toString())
                renderAllNrList(etAllNrFilter.text.toString())
                Toast.makeText(this@FloatingMonitorService, "Added NR $nr", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removeNr(nr: Int) {
        scope.launch(Dispatchers.IO) {
            KpmBridge.disableNr(nr)
            selectedNrs.remove(nr)
            launch(Dispatchers.Main) {
                tvPresetState.text = "Preset: Custom"
                lastPresetLabel = "Custom"
                presetPinnedUntilMs = System.currentTimeMillis() + 5000L
                refreshSelectedNrsDisplay()
                renderAllFilters(etAllNrFilter.text.toString())
                renderAllNrList(etAllNrFilter.text.toString())
                Toast.makeText(this@FloatingMonitorService, "Removed NR $nr", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onStartStopClick() {
        if (btnStartStop.text == "Stop monitoring") {
            stopMonitoring()
        } else {
            startMonitoring()
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
            logLine("Monitoring started for uid=${app.uid}")
            startAutoMapsSnapshots()
            launch(Dispatchers.Main) {
                Toast.makeText(this@FloatingMonitorService, "Monitoring started for ${app.label}", Toast.LENGTH_SHORT).show()
                btnStartStop.text = "Stop monitoring"
                btnStartStop.setBackgroundColor(Color.parseColor("#C62828"))
            }
        }
    }

    private fun stopMonitoring() {
        scope.launch(Dispatchers.IO) {
            KpmBridge.disable()
            mapsAutoJob?.cancel()
            mapsAutoJob = null
            logLine("Monitoring stopped")
            launch(Dispatchers.Main) {
                Toast.makeText(this@FloatingMonitorService, "Monitoring stopped", Toast.LENGTH_SHORT).show()
                btnStartStop.text = "One-tap start monitoring"
                btnStartStop.setBackgroundColor(Color.parseColor("#2E7D32"))
            }
        }
    }

    private fun startAutoMapsSnapshots() {
        mapsAutoJob?.cancel()
        mapsAutoJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val tgids = synchronized(eventBuffer) { eventBuffer.map { it.tgid }.filter { it > 0 }.distinct() }
                tgids.forEach { pid ->
                    runCatching {
                        AddressResolver.captureSnapshot(pid)
                        AddressResolver.persistRecentRawMapsFiles(this@FloatingMonitorService, pid, 5)
                    }.onFailure { e ->
                        logLine("Auto maps snapshot failed for pid=$pid: ${e.message}")
                    }
                }
                delay(5000L)
            }
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
        val s = withContext(Dispatchers.IO) { KpmBridge.status() }
        if (s.success && s.output.isNotEmpty()) {
            val status = StatusParser.parseStatus(s.output)
            tvStatusCard.text = "Status: ${if (status.enabled) "Enabled" else "Disabled"}"
            tvVersion.text = "Version: ${status.version}"
            tvUid.text = "Target UID: ${if (status.targetUid >= 0) status.targetUid.toString() else "All"}"
            tvEventCount.text = "Event count: ${status.eventsTotal}"
            tvMonState.text = if (status.enabled) "Monitoring: Running" else "Monitoring: Not started"
            tvMonState.setTextColor(if (status.enabled) Color.parseColor("#2E7D32") else Color.GRAY)

            if (status.enabled) {
                val chunk = withContext(Dispatchers.IO) { KpmBridge.readEventFileChunk(fileOffset, 128 * 1024) }
                if (chunk.isNotEmpty()) {
                    fileOffset += chunk.size
                    val merged = ByteArray(tailBuf.size + chunk.size)
                    System.arraycopy(tailBuf, 0, merged, 0, tailBuf.size)
                    System.arraycopy(chunk, 0, merged, tailBuf.size, chunk.size)
                    val parsed = BinEventParser.parse(merged)
                    tailBuf = if (parsed.consumedBytes in 1 until merged.size) merged.copyOfRange(parsed.consumedBytes, merged.size) else ByteArray(0)
                    pushEvents(parsed.events)
                    emptyBinPolls = 0
                } else { emptyBinPolls++ }
                if (emptyBinPolls >= 6) useJsonFallback = true
                if (useJsonFallback) {
                    val dr = withContext(Dispatchers.IO) { KpmBridge.drain(128) }
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
        for (e in events) { while (eventBuffer.size >= 500) eventBuffer.removeFirst(); eventBuffer.addLast(e) }
        renderLogs()
        renderEventList()
        evtCountTv.text = "Events: ${eventBuffer.size}"
    }

    private fun renderLogs() {
        if (eventBuffer.isEmpty()) { tvLogs.text = "No logs yet"; return }
        val sb = StringBuilder()
        val start = (eventBuffer.size - 160).coerceAtLeast(0)
        var idx = 0
        for (it in eventBuffer) { if (idx++ < start) continue; sb.append('#').append(it.seq).append(' ').append(it.name).append(" pid=").append(it.pid).append(" uid=").append(it.uid).append(' ').append(it.desc).append('\n') }
        tvLogs.text = sb.toString()
        (tabLogs.getChildAt(1) as ScrollView).post { (tabLogs.getChildAt(1) as ScrollView).fullScroll(View.FOCUS_DOWN) }
        if (eventBuffer.isNotEmpty()) logLine("event #${eventBuffer.last().seq} ${eventBuffer.last().name}")
    }

    private fun renderEventList() {
        if (!::evtListContainer.isInitialized) return
        evtListContainer.removeAllViews()
        val filtered = if (eventFilterQuery.isEmpty()) eventBuffer.toList() else eventBuffer.filter {
            it.name.lowercase().contains(eventFilterQuery) || it.desc.lowercase().contains(eventFilterQuery) ||
                    it.comm.lowercase().contains(eventFilterQuery) || it.nr.toString().contains(eventFilterQuery) ||
                    it.pid.toString().contains(eventFilterQuery) || it.uid.toString().contains(eventFilterQuery) ||
                    it.seq.toString().contains(eventFilterQuery)
        }
        if (filtered.isEmpty()) { evtListContainer.addView(TextView(this).apply { text = "No events"; gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(20)) }); return }
        filtered.reversed().take(200).forEach { evt ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#FAFFFFFF")); setPadding(dp(8), dp(6), dp(8), dp(6)); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }; setOnClickListener { showEventDetail(evt) } }
            card.addView(TextView(this).apply { text = "#${evt.seq}  ${evt.name}(${evt.nr})  pid=${evt.pid}  uid=${evt.uid}"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD })
            if (evt.desc.isNotEmpty()) card.addView(TextView(this).apply { text = evt.desc.take(120); textSize = 11f; setTextColor(Color.DKGRAY); maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
            evtListContainer.addView(card)
        }
    }

    private fun showEventDetail(evt: StatusParser.SvcEvent) {
        val detail = buildString {
            appendLine("═══ Syscall Details ═══")
            appendLine("#${evt.seq}  ${evt.name}(${evt.nr})")
            appendLine("Category: ${StatusParser.syscallCategory(evt.nr)}")
            appendLine("TGID: ${evt.tgid}  PID: ${evt.pid}  UID: ${evt.uid}")
            appendLine("Process: ${evt.comm}")
            appendLine()
            appendLine("═══ Arguments ═══")
            appendLine("a0: 0x${java.lang.Long.toHexString(evt.a0)} (${evt.a0})")
            appendLine("a1: 0x${java.lang.Long.toHexString(evt.a1)} (${evt.a1})")
            appendLine("a2: 0x${java.lang.Long.toHexString(evt.a2)} (${evt.a2})")
            appendLine("a3: 0x${java.lang.Long.toHexString(evt.a3)} (${evt.a3})")
            appendLine("a4: 0x${java.lang.Long.toHexString(evt.a4)} (${evt.a4})")
            appendLine("a5: 0x${java.lang.Long.toHexString(evt.a5)} (${evt.a5})")
            appendLine()
            appendLine("═══ Parsed Result ═══")
            appendLine(evt.desc)
        }
        AlertDialog.Builder(this)
            .setTitle("${evt.name}(${evt.nr})")
            .setMessage(detail)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("svc_event", detail))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ==================== EXPORT ALL EVENTS FROM BINARY FILE ====================
    private suspend fun readAllEventsStreaming(): List<StatusParser.SvcEvent> {
        return withContext(Dispatchers.IO) {
            val allEvents = mutableListOf<StatusParser.SvcEvent>()
            var offset = 0L
            var tail = ByteArray(0)
            val chunkSize = 256 * 1024
            while (true) {
                val chunk = KpmBridge.readEventFileChunk(offset, chunkSize)
                if (chunk.isEmpty()) break
                val merged = ByteArray(tail.size + chunk.size)
                System.arraycopy(tail, 0, merged, 0, tail.size)
                System.arraycopy(chunk, 0, merged, tail.size, chunk.size)
                val parsed = BinEventParser.parse(merged)
                allEvents.addAll(parsed.events)
                if (parsed.consumedBytes in 1 until merged.size) {
                    tail = merged.copyOfRange(parsed.consumedBytes, merged.size)
                    offset += parsed.consumedBytes
                } else if (parsed.consumedBytes == merged.size) {
                    tail = ByteArray(0)
                    offset += chunk.size
                } else {
                    // Keep trailing bytes when parser cannot fully consume current chunk
                    tail = merged
                    offset += chunk.size
                }
                if (chunk.size < chunkSize) break
            }
            allEvents
        }
    }

    private fun exportEventsToCsv() {
        scope.launch {
            runCatching {
                Toast.makeText(this@FloatingMonitorService, "Exporting CSV...", Toast.LENGTH_SHORT).show()
                val result = FullLogExporter.exportCsv(this@FloatingMonitorService)
                val mapsFile = AddressResolver.exportRecentMapsSnapshots(this@FloatingMonitorService, 5)
                logLine("Floating CSV exported: ${result.count} events -> ${result.file.absolutePath}")
                Toast.makeText(this@FloatingMonitorService, "CSV exported: ${result.count} events", Toast.LENGTH_LONG).show()
                val files = mutableListOf(result.file)
                if (mapsFile != null) files.add(mapsFile)
                shareExportFiles(files, "text/csv")
            }.onFailure { e ->
                logLine("Floating CSV export failed: ${e.message}")
                Toast.makeText(this@FloatingMonitorService, "CSV export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportEventsToJsonl() {
        scope.launch {
            runCatching {
                Toast.makeText(this@FloatingMonitorService, "Exporting JSONL...", Toast.LENGTH_SHORT).show()
                val result = FullLogExporter.exportJsonl(this@FloatingMonitorService)
                val mapsFile = AddressResolver.exportRecentMapsSnapshots(this@FloatingMonitorService, 5)
                logLine("Floating JSONL exported: ${result.count} events -> ${result.file.absolutePath}")
                Toast.makeText(this@FloatingMonitorService, "JSONL exported: ${result.count} events", Toast.LENGTH_LONG).show()
                val files = mutableListOf(result.file)
                if (mapsFile != null) files.add(mapsFile)
                shareExportFiles(files, "application/x-ndjson")
            }.onFailure { e ->
                logLine("Floating JSONL export failed: ${e.message}")
                Toast.makeText(this@FloatingMonitorService, "JSONL export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun shareExportFiles(files: List<File>, mimeType: String) {
        try {
            if (files.isEmpty()) return
            val uris = ArrayList<android.net.Uri>(files.size)
            files.forEach { uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", it)) }
            val sendIntent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            startActivity(Intent.createChooser(sendIntent, "Share export"))
        } catch (e: Exception) { Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun clearEvents() { eventBuffer.clear(); renderLogs(); renderEventList(); evtCountTv.text = "Events: 0"; Toast.makeText(this, "Events cleared", Toast.LENGTH_SHORT).show() }

    private fun analyzeThreads() {
        val tgid = etTgid.text.toString().toIntOrNull()
        if (tgid == null || tgid <= 0) { Toast.makeText(this, "Enter valid TGID", Toast.LENGTH_SHORT).show(); return }
        val events = eventBuffer.filter { it.tgid == tgid }
        if (events.isEmpty()) { tvThreadOut.text = "No events for TGID $tgid"; return }
        val stats = events.groupBy { it.pid }.mapValues { it.value.size }
        val cloneEvents = events.filter { it.nr == 220 || it.nr == 435 }.filter { it.ret > 0 }
        val children = HashMap<Int, MutableList<Int>>()
        for (e in cloneEvents) children.getOrPut(e.pid) { ArrayList() }.add(e.ret.toInt())
        val tree = buildString {
            appendLine("TGID: $tgid"); appendLine("Total events: ${events.size}"); appendLine("Unique threads: ${stats.size}")
            appendLine("\nThread stats (events per TID):")
            stats.entries.sortedByDescending { it.value }.take(20).forEach { appendLine("  TID ${it.key} → ${it.value} events") }
            if (children.isNotEmpty()) {
                appendLine("\nThread creation tree (clone/clone3):")
                fun printTree(pid: Int, indent: String) { appendLine("$indent$pid"); children[pid]?.forEach { printTree(it, "$indent  ") } }
                val roots = (children.keys - children.values.flatten().toSet())
                if (roots.isEmpty()) children.keys.firstOrNull()?.let { printTree(it, "") } else roots.forEach { printTree(it, "") }
            }
        }
        tvThreadOut.text = tree
    }

    private fun startStatusUpdater() {
        scope.launch {
            while (isActive) {
                val s = withContext(Dispatchers.IO) { KpmBridge.status() }
                if (s.success && s.output.isNotEmpty()) {
                    val status = StatusParser.parseStatus(s.output)
                    currentNrList = status.nrList
                    selectedNrs.clear()
                    selectedNrs.addAll(status.nrList)
                    val now = System.currentTimeMillis()
                    if (status.nrList.isEmpty()) {
                        if (now > presetPinnedUntilMs) {
                            lastPresetLabel = "(none)"
                            tvPresetState.text = "Preset: (none)"
                        } else {
                            tvPresetState.text = "Preset: $lastPresetLabel"
                        }
                    } else if (tvPresetState.text.toString() == "Preset: (none)") {
                        if (lastPresetLabel == "(none)") lastPresetLabel = "Custom"
                        tvPresetState.text = "Preset: $lastPresetLabel"
                    }
                    refreshSelectedNrsDisplay()
                    renderAllFilters(etAllNrFilter.text.toString())
                    renderAllNrList(etAllNrFilter.text.toString())
                }
                delay(2000)
            }
        }
    }

    private fun togglePanel() { panelView.visibility = if (panelView.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
    private fun ensureChannel() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL_ID, "SVC Floating Monitor", NotificationManager.IMPORTANCE_LOW)) }
    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("SVC floating monitor").setContentText("Overlay running").setContentIntent(PendingIntent.getActivity(this, 1002, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).setOngoing(true).build()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun resetFloatingLog() { runCatching { floatingLogFile.parentFile?.mkdirs(); if (floatingLogFile.exists()) floatingLogFile.delete(); floatingLogFile.createNewFile() } }
    private fun logLine(msg: String) { runCatching { rotateLogIfNeeded(); floatingLogFile.appendText("[${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}] $msg\n") } }
    private fun rotateLogIfNeeded() { if (floatingLogFile.exists() && floatingLogFile.length() > 2*1024*1024) { File(floatingLogFile.parentFile, "svc_floating_prev.log").apply { if (exists()) delete() }; floatingLogFile.renameTo(File(floatingLogFile.parentFile, "svc_floating_prev.log")); floatingLogFile.createNewFile() } }
    private fun shareLatestFloatingLog() {
        try {
            if (!floatingLogFile.exists()) { Toast.makeText(this, "No latest log found", Toast.LENGTH_SHORT).show(); return }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", floatingLogFile)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private inner class DragTouchListener(private val params: WindowManager.LayoutParams) : View.OnTouchListener {
        private var sx = 0; private var sy = 0; private var tx = 0f; private var ty = 0f
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { sx = params.x; sy = params.y; tx = event.rawX; ty = event.rawY }
                MotionEvent.ACTION_MOVE -> { params.x = sx + (event.rawX - tx).toInt(); params.y = sy + (event.rawY - ty).toInt(); wm.updateViewLayout(v, params) }
            }
            return false
        }
    }

    companion object { private const val CHANNEL_ID = "svc_floating_monitor"; private const val NOTI_ID = 1001 }
}
