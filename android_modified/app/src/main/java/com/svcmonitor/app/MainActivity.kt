package com.svcmonitor.app

import android.content.Intent
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.svcmonitor.app.db.SvcEventDb
import com.svcmonitor.app.db.SvcEventEntity
import com.svcmonitor.app.db.ThreadEdge
import com.svcmonitor.app.db.ThreadStat
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import com.svcmonitor.app.AddressResolver

/**
 * MainActivity — 4-tab UI built programmatically (no XML layouts).
 *
 * Tabs: Monitor | Filter | Events | Settings
 *
 * CRITICAL: All tab views are pre-built in onCreate() BEFORE observeViewModel()
 *           to avoid UninitializedPropertyAccessException on lateinit properties.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var vm: MainViewModel
    private lateinit var logExporter: LogExporter

    /* ── views that observers touch (must be initialized before observeViewModel) ── */
    // Dashboard tab
    private lateinit var tvStatus: TextView
    private lateinit var tvVersion: TextView
    private lateinit var tvUid: TextView
    private lateinit var tvEventCount: TextView
    private lateinit var tvMonState: TextView
    private lateinit var tvMsg: TextView
    private lateinit var btnStartStop: Button
    private lateinit var etAppSearch: EditText
    private lateinit var spinnerApp: Spinner
    private lateinit var tvDashNrCount: TextView
    private lateinit var tvDashNrList: TextView

    // Events tab
    private lateinit var tvEvtCount: TextView
    private lateinit var etEventSearch: EditText
    private lateinit var llEventList: LinearLayout
    private lateinit var scrollEvents: ScrollView

    // Settings tab
    private lateinit var switchTier2: Switch
    private lateinit var switchHideSystemApps: Switch
    private lateinit var switchOnlyLaunchableApps: Switch
    private lateinit var tvSuperKey: EditText

    // Filter tab
    private lateinit var tvNrCount: TextView
    private lateinit var tvNrList: TextView
    private lateinit var llSelectedNrs: LinearLayout
    private lateinit var switchDoFilpOpen: Switch
    private val nrNameViews = HashMap<Int, TextView>()
    private lateinit var filterListContainer: LinearLayout
    private lateinit var etAllNrFilter: EditText
    private lateinit var llAllNrList: LinearLayout
    private var hookedNrSet: Set<Int> = emptySet()
    private var currentNrList: List<Int> = emptyList()
    private var lastEventsAll: List<StatusParser.SvcEvent> = emptyList()
    private var eventSearchQuery: String = ""
    private var historyLastSeq: Long = 0L
    private val eventSearchExtra = HashMap<Long, String>()
    private val eventCallChain = HashMap<Long, String>()
    private var resolvingSearch = false
    private var resolvingChain = false

    private var relayEnabled = false
    private var relayHost = "127.0.0.1"
    private var relayPort = 5001
    private var relayLastEnqueuedSeq = 0L
    private val relayQueue = ArrayDeque<StatusParser.SvcEvent>(2048)
    private var relayJob: kotlinx.coroutines.Job? = null
    private var relayLastError: String = ""

    private var pcServerEnabled = false
    private var pcServerPort = 8080
    private var pcServerJob: kotlinx.coroutines.Job? = null
    private var pcServerSocket: java.net.ServerSocket? = null
    private val pcServerClients = ArrayList<java.io.BufferedWriter>(4)
    private var pcServerBacklogLimit = 20000

    private data class SensitiveRule(val needle: String, val color: Int)
    private val sensitiveRules by lazy {
        listOf(
            SensitiveRule("ptrace", cRed),
            SensitiveRule("/proc/self/maps", cRed),
            SensitiveRule("/proc/self/mem", cRed),
            SensitiveRule("process_vm_readv", cRed),
            SensitiveRule("process_vm_writev", cRed),
            SensitiveRule("frida", 0xFFFF8800.toInt()),
            SensitiveRule("xposed", 0xFFFF8800.toInt()),
            SensitiveRule("magisk", 0xFFFF8800.toInt()),
            SensitiveRule("su", 0xFFCCAA00.toInt()),
            SensitiveRule("tcp", 0xFFCCAA00.toInt())
        )
    }

    /* ── app list data ────────────────────────────────────────── */
    private var appList: List<AppInfo> = emptyList()
    private var appSearchQuery: String = ""
    private var hideSystemApps: Boolean = false
    private var onlyLaunchableApps: Boolean = false

    private val prefs by lazy { getSharedPreferences("svcmon_prefs", MODE_PRIVATE) }

    /* ── colors ───────────────────────────────────────────────── */
    private val cPrimary = Color.parseColor("#1565C0")
    private val cBg = Color.parseColor("#F5F5F5")
    private val cCard = Color.WHITE
    private val cText = Color.parseColor("#212121")
    private val cSecondary = Color.parseColor("#757575")
    private val cGreen = Color.parseColor("#2E7D32")
    private val cRed = Color.parseColor("#C62828")
    private val cAccent = Color.parseColor("#FF6F00")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = ViewModelProvider(this)[MainViewModel::class.java]
        vm.initDb(applicationContext)
        logExporter = LogExporter(this)

        hideSystemApps = prefs.getBoolean("hide_system_apps", false)
        onlyLaunchableApps = prefs.getBoolean("only_launchable_apps", false)
        vm.doFilpOpenEnabled = prefs.getBoolean("do_filp_open", false)
        vm.btLengthFirst = prefs.getBoolean("bt_length_first", false)
        historyLastSeq = prefs.getLong("history_last_seq", 0L)
        appList = loadVisibleApps(appSearchQuery)

        relayEnabled = prefs.getBoolean("pc_relay_enabled", false)
        relayHost = prefs.getString("pc_relay_host", "127.0.0.1") ?: "127.0.0.1"
        relayPort = prefs.getInt("pc_relay_port", 5001)
        pcServerEnabled = prefs.getBoolean("pc_server_enabled", false)
        pcServerPort = prefs.getInt("pc_server_port", 8080)

        // Pre-build ALL tab views FIRST (before observeViewModel!)
        val dashboardView = buildDashboardTab()
        val filterView = buildFilterTab()
        val eventsView = buildEventsTab()
        val threadView = buildThreadTab()
        val settingsView = buildSettingsTab()

        // Build main layout with TabHost
        val root = buildMainLayout(dashboardView, filterView, eventsView, threadView, settingsView)
        setContentView(root)

        // NOW observe — all lateinit properties are initialized
        observeViewModel()

        // Start polling
        vm.startPolling()

        if (relayEnabled) startRelay()
        if (pcServerEnabled) startPcServer()
    }

    /* ══════════════════════════════════════════════════════════════
     *  MAIN LAYOUT with TabHost
     * ══════════════════════════════════════════════════════════════ */

    private fun buildMainLayout(
        dashboard: View, filter: View, events: View, thread: View, settings: View
    ): View {
        val tabHost = TabHost(this, null).apply {
            id = android.R.id.tabhost
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Title bar
        val titleBar = TextView(this).apply {
            text = "SVCMonitor v8.1"
            setTextColor(Color.WHITE)
            setBackgroundColor(cPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        ll.addView(titleBar)

        // Tab widget
        val tabWidget = TabWidget(this).apply {
            id = android.R.id.tabs
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        ll.addView(tabWidget)

        // Tab content
        val tabContent = FrameLayout(this).apply {
            id = android.R.id.tabcontent
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }
        ll.addView(tabContent)

        tabHost.addView(ll)
        tabHost.setup()

        // Add tabs with pre-built views
        tabHost.addTab(tabHost.newTabSpec("dashboard").setIndicator("Monitor").setContent { dashboard })
        tabHost.addTab(tabHost.newTabSpec("filter").setIndicator("Filter").setContent { filter })
        tabHost.addTab(tabHost.newTabSpec("events").setIndicator("Events").setContent { events })
        tabHost.addTab(tabHost.newTabSpec("thread").setIndicator("Threads").setContent { thread })
        tabHost.addTab(tabHost.newTabSpec("settings").setIndicator("Settings").setContent { settings })

        return tabHost
    }

    /* ══════════════════════════════════════════════════════════════
     *  TAB 1: Monitor (Dashboard)
     * ══════════════════════════════════════════════════════════════ */

    private fun buildDashboardTab(): View {
        val sv = ScrollView(this).apply {
            setBackgroundColor(cBg)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        // Status card
        col.addView(makeCard {
            addView(makeLabel("Module Status"))
            tvStatus = makeValue("Unknown"); addView(tvStatus)
            tvVersion = makeValue("Version: —"); addView(tvVersion)
            tvUid = makeValue("Target UID: —"); addView(tvUid)
            tvEventCount = makeValue("Event count: 0"); addView(tvEventCount)
            tvMonState = makeValue("Status: Not started").apply {
                setTextColor(cSecondary)
            }; addView(tvMonState)

            tvMsg = makeValue("Tip: -").apply {
                setTextColor(cSecondary)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }; addView(tvMsg)
        })

        // Step 1: Select app
        col.addView(makeCard {
            addView(makeLabel("Step 1: Select target app"))
            etAppSearch = EditText(this@MainActivity).apply {
                hint = "Search app name / package"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        appSearchQuery = s?.toString()?.trim().orEmpty()
                        refreshAppSpinner()
                    }
                })
            }
            addView(etAppSearch)
            spinnerApp = Spinner(this@MainActivity).apply {
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        vm.selectedApp = if (pos > 0) appList[pos - 1] else null
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
            addView(spinnerApp)
            refreshAppSpinner()
        })

        // Step 2: Show selected NRs (managed in Filter tab)
        col.addView(makeCard {
            addView(makeLabel("Step 2: Selected syscalls (manage in Filter tab)"))
            tvDashNrCount = makeValue("Selected: 0 syscalls"); addView(tvDashNrCount)
            tvDashNrList = makeValue("NR list: (empty)").apply {
                maxLines = 6
                ellipsize = TextUtils.TruncateAt.END
            }; addView(tvDashNrList)
        })

        // Step 3: Start/Stop button
        col.addView(makeCard {
            addView(makeLabel("Step 3: Start monitoring"))
            btnStartStop = Button(this@MainActivity).apply {
                text = "One-tap start monitoring"
                setBackgroundColor(cGreen)
                setTextColor(Color.WHITE)
                setOnClickListener { onStartStopClick() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            addView(btnStartStop)
        })

        col.addView(makeCard {
            addView(makeLabel("Default connection settings (Main page)"))

            val rowRelay = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val etHost = EditText(this@MainActivity).apply {
                hint = "WebSocket/PC host"
                setText(relayHost)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val etPort = EditText(this@MainActivity).apply {
                hint = "Port"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(relayPort.toString())
                layoutParams = LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            rowRelay.addView(etHost)
            rowRelay.addView(Space(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
            rowRelay.addView(etPort)
            addView(rowRelay)

            val rowAdb = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            val etAdbPort = EditText(this@MainActivity).apply {
                hint = "ADB app port"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(pcServerPort.toString())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            rowAdb.addView(etAdbPort)
            addView(rowAdb)

            addView(Button(this@MainActivity).apply {
                text = "Save defaults"
                isAllCaps = false
                setOnClickListener {
                    relayHost = etHost.text.toString().trim().ifBlank { "127.0.0.1" }
                    relayPort = etPort.text.toString().toIntOrNull() ?: 5001
                    pcServerPort = etAdbPort.text.toString().toIntOrNull() ?: 8080
                    prefs.edit()
                        .putString("pc_relay_host", relayHost)
                        .putInt("pc_relay_port", relayPort)
                        .putInt("pc_server_port", pcServerPort)
                        .apply()
                    tvMsg.text = "Tip: Defaults saved. Relay=$relayHost:$relayPort, adb-port=$pcServerPort"
                }
            })

            addView(Button(this@MainActivity).apply {
                text = "Launch floating monitor (MX-like overlay)"
                isAllCaps = false
                setOnClickListener { startFloatingMonitorOverlay() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            })
        })

        sv.addView(col)
        return sv
    }

    private fun startFloatingMonitorOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            tryAutoGrantOverlayByRoot()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val i = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(i)
            Toast.makeText(this, "Overlay permission is required. Opened settings.", Toast.LENGTH_LONG).show()
            return
        }
        val i = Intent(this, FloatingMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        Toast.makeText(this, "Floating monitor started", Toast.LENGTH_SHORT).show()
    }

    private fun tryAutoGrantOverlayByRoot() {
        try {
            Runtime.getRuntime().exec(
                arrayOf(
                    "su",
                    "-c",
                    "appops set $packageName SYSTEM_ALERT_WINDOW allow; appops set $packageName POST_NOTIFICATION allow"
                )
            ).waitFor()
        } catch (_: Exception) {
        }
    }

    /* ══════════════════════════════════════════════════════════════
     *  TAB 2: Filter (Filter)
     * ══════════════════════════════════════════════════════════════ */

    private fun buildFilterTab(): View {
        val sv = ScrollView(this).apply {
            setBackgroundColor(cBg)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        col.addView(makeCard {
            addView(makeLabel("Extra Hooks"))
            switchDoFilpOpen = Switch(this@MainActivity).apply {
                text = "Enable do_filp_open (lower-level open path)"
                isChecked = prefs.getBoolean("do_filp_open", false)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean("do_filp_open", checked).apply()
                    vm.setDoFilpOpen(checked)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            }
            addView(switchDoFilpOpen)
        })

        col.addView(makeCard {
            addView(makeLabel("Current NR filter"))
            tvNrCount = makeValue("Selected: 0 syscalls"); addView(tvNrCount)
            tvNrList = makeValue("NR list: (empty)").apply {
                maxLines = 10
                ellipsize = TextUtils.TruncateAt.END
            }; addView(tvNrList)
            llSelectedNrs = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            addView(llSelectedNrs)
            addView(Button(this@MainActivity).apply {
                text = "Clear selected NRs"
                setTextColor(cRed)
                isAllCaps = false
                setOnClickListener { vm.disableAll() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            })
        })

        // Preset quick-apply
        col.addView(makeCard {
            addView(makeLabel("Quick apply preset"))
            StatusParser.presets.forEach { preset ->
                addView(Button(this@MainActivity).apply {
                    text = "${preset.name}: ${preset.description}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    isAllCaps = false
                    setOnClickListener {
                        applyPresetUi(preset.id)
                        tvMsg.text = "Tip: Preset applied ${preset.name}"
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(4) }
                })
            }
        })

        col.addView(makeCard {
            addView(makeLabel("Rule Sets"))

            fun addRuleBtn(title: String, nrs: IntArray) {
                addView(Button(this@MainActivity).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    isAllCaps = false
                    setOnClickListener {
                        vm.setNrs(nrs.toList())
                        tvMsg.text = "Tip: Rule set applied $title"
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(4) }
                })
            }

            addRuleBtn("Capture file I/O", RuleSets.FILE_IO)
            addRuleBtn("Capture network requests", RuleSets.NETWORK)
            addRuleBtn("Anti-debug detection", RuleSets.ANTI_DEBUG)
            addRuleBtn("Process lifecycle", RuleSets.PROCESS)
            addRuleBtn("Memory ops/injection", RuleSets.MEMORY)
        })

        col.addView(makeCard {
            addView(makeLabel("PC relay forwarding"))

            val hostRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val etHost = EditText(this@MainActivity).apply {
                hint = "PC IP/domain (e.g. 192.168.1.10)"
                setText(relayHost)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val etPort = EditText(this@MainActivity).apply {
                hint = "Port"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(relayPort.toString())
                layoutParams = LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            hostRow.addView(etHost)
            hostRow.addView(Space(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
            hostRow.addView(etPort)
            addView(hostRow)

            val tvState = makeValue(if (relayEnabled) "Status: Enabled" else "Status: Disabled").apply {
                setTextColor(if (relayEnabled) cGreen else cSecondary)
            }
            addView(tvState)

            val serverRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            val etServerPort = EditText(this@MainActivity).apply {
                hint = "App port (default 8080)"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(pcServerPort.toString())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            serverRow.addView(etServerPort)
            serverRow.addView(Space(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
            val tvServerState = makeValue(if (pcServerEnabled) "ADB mode: Enabled" else "ADB mode: Disabled").apply {
                setTextColor(if (pcServerEnabled) cGreen else cSecondary)
            }
            serverRow.addView(tvServerState)
            addView(serverRow)

            addView(Switch(this@MainActivity).apply {
                text = "Enable app server (PC connects via adb forward)"
                isChecked = pcServerEnabled
                setOnCheckedChangeListener { _, checked ->
                    pcServerPort = etServerPort.text.toString().toIntOrNull() ?: 8080
                    prefs.edit()
                        .putBoolean("pc_server_enabled", checked)
                        .putInt("pc_server_port", pcServerPort)
                        .apply()
                    pcServerEnabled = checked
                    if (checked) {
                        tvServerState.text = "ADB mode: Enabled"
                        tvServerState.setTextColor(cGreen)
                        startPcServer()
                    } else {
                        tvServerState.text = "ADB mode: Disabled"
                        tvServerState.setTextColor(cSecondary)
                        stopPcServer()
                    }
                }
            })

            addView(Button(this@MainActivity).apply {
                text = "Show ADB command"
                isAllCaps = false
                setOnClickListener {
                    pcServerPort = etServerPort.text.toString().toIntOrNull() ?: 8080
                    prefs.edit().putInt("pc_server_port", pcServerPort).apply()
                    tvMsg.text = "Tip: Run `adb forward tcp:$pcServerPort tcp:$pcServerPort` on PC, then let PC Viewer connect to 127.0.0.1:$pcServerPort"
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            })

            val sw = Switch(this@MainActivity).apply {
                text = "Enable PC relay (HTTP push to /api/ingest)"
                isChecked = relayEnabled
                setOnCheckedChangeListener { _, checked ->
                    relayHost = etHost.text.toString().trim().ifBlank { "127.0.0.1" }
                    relayPort = etPort.text.toString().toIntOrNull() ?: 5001
                    prefs.edit()
                        .putBoolean("pc_relay_enabled", checked)
                        .putString("pc_relay_host", relayHost)
                        .putInt("pc_relay_port", relayPort)
                        .apply()
                    relayEnabled = checked
                    if (checked) {
                        tvState.text = "Status: Enabled"
                        tvState.setTextColor(cGreen)
                        startRelay()
                    } else {
                        tvState.text = "Status: Disabled"
                        tvState.setTextColor(cSecondary)
                        stopRelay()
                    }
                }
            }
            addView(sw)

            addView(Button(this@MainActivity).apply {
                text = "Test connection"
                isAllCaps = false
                setOnClickListener {
                    relayHost = etHost.text.toString().trim().ifBlank { "127.0.0.1" }
                    relayPort = etPort.text.toString().toIntOrNull() ?: 5001
                    prefs.edit().putString("pc_relay_host", relayHost).putInt("pc_relay_port", relayPort).apply()
                    lifecycleScope.launch {
                        val ok = withContext(Dispatchers.IO) { testRelayOnce() }
                        if (ok) {
                            tvMsg.text = "Tip: PC relay endpoint is reachable"
                        } else {
                            val h = relayHost.trim().lowercase()
                            tvMsg.text = if (h == "127.0.0.1" || h == "localhost") {
                                "Tip: PC relay endpoint unavailable (${relayLastError.ifBlank { "Connection failed" }}; if adb reverse is set, confirm PC Viewer listens on $relayPort)"
                            } else {
                                "Tip: PC relay endpoint unavailable (${relayLastError.ifBlank { "Connection failed" }}）"
                            }
                        }
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            })
        })

        // Manual NR add/remove
        col.addView(makeCard {
            addView(makeLabel("Manual NR management"))
            val etNr = EditText(this@MainActivity).apply {
                hint = "Enter NR number (e.g. 56)"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            addView(etNr)

            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            row.addView(Button(this@MainActivity).apply {
                text = "Add"
                setOnClickListener {
                    val nr = etNr.text.toString().toIntOrNull()
                    if (nr != null) {
                        if (hookedNrSet.isNotEmpty() && !hookedNrSet.contains(nr)) {
                            tvMsg.text = "Tip: NR not hooked yet; events may not appear"
                        }
                        vm.addNr(nr)
                        etNr.text.clear()
                    }
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this@MainActivity).apply {
                text = "Remove"
                setOnClickListener {
                    val nr = etNr.text.toString().toIntOrNull()
                    if (nr != null) {
                        vm.removeNr(nr)
                        etNr.text.clear()
                    }
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(row)
        })

        col.addView(makeCard {
            addView(makeLabel("Select syscalls by category"))
            filterListContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(filterListContainer)
        })

        col.addView(makeCard {
            addView(makeLabel("All ARM64 SVC numbers (0-459)"))

            etAllNrFilter = EditText(this@MainActivity).apply {
                hint = "Filter: nr / name (substring supported)"
                setTextColor(cText)
                setHintTextColor(cSecondary)
            }
            addView(etAllNrFilter)

            llAllNrList = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            addView(llAllNrList)

            etAllNrFilter.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    renderAllNrList(s?.toString() ?: "")
                }
            })
        })

        sv.addView(col)
        return sv
    }

    private fun renderAllNrList(query: String) {
        if (!this::llAllNrList.isInitialized) return
        llAllNrList.removeAllViews()
        val q = query.trim().lowercase()
        val selected = currentNrList.toHashSet()
        var shown = 0
        for (nr in 0..459) {
            val name = StatusParser.nrToName(nr)
            if (q.isNotEmpty()) {
                val hit = nr.toString().contains(q) || name.lowercase().contains(q)
                if (!hit) continue
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
            }
            row.addView(TextView(this).apply {
                text = nr.toString()
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(cSecondary)
                minWidth = dp(48)
            })
            row.addView(TextView(this).apply {
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(if (selected.contains(nr)) cGreen else cText)
                typeface = if (selected.contains(nr)) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "+"
                setOnClickListener { vm.addNr(nr) }
            })
            row.addView(Button(this).apply {
                text = "-"
                setOnClickListener { vm.removeNr(nr) }
            })
            llAllNrList.addView(row)
            shown++
            if (shown >= 200) break
        }
        if (shown == 0) {
            llAllNrList.addView(TextView(this).apply {
                text = "No matches"
                setTextColor(cSecondary)
                setPadding(0, dp(4), 0, dp(4))
            })
        } else if (shown >= 200) {
            llAllNrList.addView(TextView(this).apply {
                text = "Showing first 200 items only; keep typing to narrow down"
                setTextColor(cSecondary)
                setPadding(0, dp(6), 0, 0)
            })
        }
    }

    /* ══════════════════════════════════════════════════════════════
     *  TAB 3: Events (Events)
     * ══════════════════════════════════════════════════════════════ */

    private fun buildEventsTab(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top bar with count + export buttons
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }

        tvEvtCount = TextView(this).apply {
            text = "Events: 0"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(cText)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(tvEvtCount)

        topBar.addView(Button(this).apply {
            text = "CSV"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isAllCaps = false
            setOnClickListener { exportCsv() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) }
        })

        topBar.addView(Button(this).apply {
            text = "JSON"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isAllCaps = false
            setOnClickListener { exportJson() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) }
        })

        topBar.addView(Button(this).apply {
            text = "Clear"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(cRed)
            isAllCaps = false
            setOnClickListener { vm.clearEvents() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) }
        })

        root.addView(topBar)

        val searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }

        etEventSearch = EditText(this).apply {
            hint = "Search events (text/number)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    eventSearchQuery = s?.toString()?.trim().orEmpty()
                    vm.setSearchQuery(eventSearchQuery)
                    kickResolveForSearch()
                    updateEventList(filterEvents(lastEventsAll))
                }
            })
        }
        searchBar.addView(etEventSearch)

        val etTid = EditText(this).apply {
            hint = "TID"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val tid = s?.toString()?.trim()?.toIntOrNull()
                    vm.setTidFilter(tid)
                }
            })
        }
        searchBar.addView(etTid)

        searchBar.addView(Button(this).apply {
            text = "Reset"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isAllCaps = false
            setOnClickListener {
                etEventSearch.setText("")
                etTid.setText("")
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) }
        })

        searchBar.addView(Button(this).apply {
            text = "History"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isAllCaps = false
            setOnClickListener { shareHistory() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) }
        })

        searchBar.addView(Button(this).apply {
            text = "Clear history"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(cRed)
            isAllCaps = false
            setOnClickListener { clearHistory() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) }
        })

        root.addView(searchBar)

        // Event list in ScrollView
        scrollEvents = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        llEventList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        scrollEvents.addView(llEventList)
        root.addView(scrollEvents)

        return root
    }

    private fun buildThreadTab(): View {
        val sv = ScrollView(this).apply {
            setBackgroundColor(cBg)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        col.addView(makeCard {
            addView(makeLabel("Thread analyzer"))

            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val etTgid = EditText(this@MainActivity).apply {
                hint = "TGID (process id)"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(etTgid)

            row.addView(Space(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })

            row.addView(Button(this@MainActivity).apply {
                text = "Use recent"
                isAllCaps = false
                setOnClickListener {
                    val t = lastEventsAll.lastOrNull()?.tgid ?: 0
                    if (t > 0) etTgid.setText(t.toString())
                }
            })

            row.addView(Space(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })

            val tvOut = TextView(this@MainActivity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
                setTextColor(cPrimary)
                text = "Enter TGID and click Refresh."
            }

            row.addView(Button(this@MainActivity).apply {
                text = "Refresh"
                isAllCaps = false
                setOnClickListener {
                    val tgid = etTgid.text.toString().trim().toIntOrNull() ?: 0
                    if (tgid <= 0) {
                        tvMsg.text = "Tip: Invalid TGID"
                        return@setOnClickListener
                    }
                    lifecycleScope.launch {
                        val dao = SvcEventDb.get(applicationContext).dao()
                        val (stats, edges) = withContext(Dispatchers.IO) {
                            Pair(dao.threadStats(tgid, 200), dao.threadEdges(tgid, 2000))
                        }
                        tvOut.text = buildString {
                            appendLine("TGID=$tgid")
                            appendLine()
                            appendLine("Thread stats (Top):")
                            if (stats.isEmpty()) {
                                appendLine("  (empty)")
                            } else {
                                stats.forEach { appendLine("  tid=${it.pid}  cnt=${it.count}") }
                            }
                            appendLine()
                            appendLine("Thread tree (based on clone/clone3 ret):")
                            appendLine(buildThreadTree(edges))
                        }
                    }
                }
            })

            addView(row)
            addView(Space(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(1, dp(8)) })
            addView(tvOut)
        })

        sv.addView(col)
        return sv
    }

    private fun buildThreadTree(edges: List<ThreadEdge>): String {
        if (edges.isEmpty()) return "(empty)"
        val children = HashMap<Int, MutableList<Int>>()
        val hasParent = HashSet<Int>()
        val allParents = HashSet<Int>()
        for (e in edges) {
            val p = e.parentPid
            val c = e.childPid.toInt()
            if (c <= 0) continue
            allParents.add(p)
            hasParent.add(c)
            children.getOrPut(p) { ArrayList() }.add(c)
        }
        for ((_, v) in children) v.sort()
        val roots = (allParents - hasParent).toList().sorted()
        val out = StringBuilder()
        val seen = HashSet<Int>()
        fun dfs(n: Int, indent: String) {
            if (!seen.add(n)) return
            out.append(indent).append(n).append('\n')
            val cs = children[n] ?: return
            for (c in cs) dfs(c, "$indent  ")
        }
        if (roots.isEmpty()) {
            val k = children.keys.toList().sorted().firstOrNull() ?: return "(empty)"
            dfs(k, "")
        } else {
            for (r in roots) dfs(r, "")
        }
        return out.toString().trimEnd()
    }

    /* ══════════════════════════════════════════════════════════════
     *  TAB 4: Settings (Settings)
     * ══════════════════════════════════════════════════════════════ */

    private fun buildSettingsTab(): View {
        val sv = ScrollView(this).apply {
            setBackgroundColor(cBg)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        // SuperKey setting
        col.addView(makeCard {
            addView(makeLabel("SuperKey"))
            tvSuperKey = EditText(this@MainActivity).apply {
                setText(KpmBridge.getSuperKey())
                hint = "Enter SuperKey"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            addView(tvSuperKey)
            addView(Button(this@MainActivity).apply {
                text = "Save SuperKey"
                setOnClickListener {
                    val key = tvSuperKey.text.toString().trim()
                    if (key.isNotEmpty()) {
                        KpmBridge.setSuperKey(key)
                        tvMsg.text = "Tip: SuperKey updated"
                    }
                }
            })
        })

        // Tier2 toggle
        col.addView(makeCard {
            addView(makeLabel("Tier-2 extended hooks"))
            addView(TextView(this@MainActivity).apply {
                text = "Enabling adds 24 extra syscall hooks\nincluding: mkdirat, unlinkat, renameat, statfs, faccessat, " +
                        "getsockname, getsockopt, shutdown, sendmsg, recvmsg, accept4, ppoll, " +
                        "eventfd2, timerfd, signalfd4, seccomp, bpf, getrandom, prlimit64, etc."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(cSecondary)
            })
            switchTier2 = Switch(this@MainActivity).apply {
                text = "Enable Tier-2"
                setOnCheckedChangeListener { _, checked ->
                    vm.tier2(checked)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            addView(switchTier2)
        })

        col.addView(makeCard {
            addView(makeLabel("Backtrace mode"))
            addView(TextView(this@MainActivity).apply {
                text = "Accuracy-first: FP chain only with light stack scan\nLength-first: more aggressive stack scan for longer traces with possible false positives"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(cSecondary)
            })
            val sw = Switch(this@MainActivity).apply {
                text = "Length-first"
                isChecked = prefs.getBoolean("bt_length_first", false)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean("bt_length_first", checked).apply()
                    vm.btLengthFirst = checked
                    vm.setBtMode(checked)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            addView(sw)
        })

        col.addView(makeCard {
            addView(makeLabel("App list"))
            switchHideSystemApps = Switch(this@MainActivity).apply {
                text = "Hide system apps"
                isChecked = hideSystemApps
                setOnCheckedChangeListener { _, checked ->
                    hideSystemApps = checked
                    prefs.edit().putBoolean("hide_system_apps", checked).apply()
                    refreshAppSpinner()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            addView(switchHideSystemApps)

            switchOnlyLaunchableApps = Switch(this@MainActivity).apply {
                text = "Show launchable apps only"
                isChecked = onlyLaunchableApps
                setOnCheckedChangeListener { _, checked ->
                    onlyLaunchableApps = checked
                    prefs.edit().putBoolean("only_launchable_apps", checked).apply()
                    refreshAppSpinner()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            }
            addView(switchOnlyLaunchableApps)
        })

        // About
        col.addView(makeCard {
            addView(makeLabel("About"))
            addView(makeValue("SVCMonitor v8.1.0"))
            addView(makeValue("ARM64 SVC syscall monitoring tool"))
            addView(makeValue("Supports deep argument parsing for 50+ syscalls"))
            addView(makeValue("Target: Pixel 6 / Android 12 / APatch"))
        })

        sv.addView(col)
        return sv
    }

    private fun loadVisibleApps(query: String): List<AppInfo> {
        return if (query.isBlank()) {
            AppResolver.getAllApps(this, hideSystemApps = hideSystemApps, onlyLaunchableApps = onlyLaunchableApps)
        } else {
            AppResolver.searchApps(
                this,
                query = query,
                hideSystemApps = hideSystemApps,
                onlyLaunchableApps = onlyLaunchableApps
            )
        }
    }

    private fun refreshAppSpinner() {
        val prevUid = vm.selectedApp?.uid
        appList = loadVisibleApps(appSearchQuery)
        val names = listOf("— Please select —") + appList.map { "${it.label} (${it.packageName})" }
        val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)
        spinnerApp.adapter = adapter

        val idx = if (prevUid != null) appList.indexOfFirst { it.uid == prevUid } else -1
        if (idx >= 0) {
            spinnerApp.setSelection(idx + 1, false)
            vm.selectedApp = appList[idx]
        } else {
            spinnerApp.setSelection(0, false)
            vm.selectedApp = null
        }
    }

    /* ══════════════════════════════════════════════════════════════
     *  OBSERVE VIEW MODEL
     * ══════════════════════════════════════════════════════════════ */

    private fun observeViewModel() {
        // Status updates
        vm.status.observe(this) { s ->
            if (s != null) {
                tvVersion.text = "Version: ${s.version}"
                tvUid.text = "Target UID: ${if (s.targetUid >= 0) s.targetUid.toString() else "All"}"
                tvStatus.text = if (s.enabled) "Enabled" else "Disabled"
                tvStatus.setTextColor(if (s.enabled) cGreen else cRed)
                tvNrCount.text = "Selected: ${s.nrCount} syscalls"
                tvNrList.text = "NR list: ${s.nrList.joinToString(", ") { "${StatusParser.nrToName(it)}($it)" }}"
                tvDashNrCount.text = "Selected: ${s.nrCount} syscalls"
                tvDashNrList.text = "NR list: ${s.nrList.joinToString(", ") { "${StatusParser.nrToName(it)}($it)" }}"
                currentNrList = s.nrList
                renderSelectedNrs(s.nrList)
                switchTier2.isChecked = s.tier2
                refreshNrHighlights(s.nrList)
                renderFilterList(s.hooks)
                renderAllNrList(etAllNrFilter.text?.toString() ?: "")
            }
        }

        // Monitoring state
        vm.monitoring.observe(this) { mon ->
            if (mon) {
                tvMonState.text = "Status: Monitoring"
                tvMonState.setTextColor(cGreen)
                btnStartStop.text = "Stop monitoring"
                btnStartStop.setBackgroundColor(cRed)
            } else {
                tvMonState.text = "Status: Not started"
                tvMonState.setTextColor(cSecondary)
                btnStartStop.text = "One-tap start monitoring"
                btnStartStop.setBackgroundColor(cGreen)
            }
        }

        // Event count
        vm.eventCount.observe(this) { count ->
            tvEventCount.text = "Event count: $count"
            tvEvtCount.text = "Events: $count"
        }

        // Events list
        vm.events.observe(this) { events ->
            lastEventsAll = events
            kickResolveCallChain(events)
            kickResolveForSearch()
            updateEventList(filterEvents(events))
        }

        vm.newEvents.observe(this) { events ->
            if (events.isNotEmpty()) {
                enqueueRelayEvents(events)
                broadcastPcServerEvents(events)
            }
        }

        // Toast
        vm.toast.observe(this) { msg ->
            if (msg != null) {
                tvMsg.text = "Tip: $msg"
                vm.toastConsumed()
            }
        }
    }

    /* ══════════════════════════════════════════════════════════════
     *  EVENT LIST rendering
     * ══════════════════════════════════════════════════════════════ */

    private fun updateEventList(events: List<StatusParser.SvcEvent>) {
        llEventList.removeAllViews()

        val limit = if (eventSearchQuery.isNotBlank()) 300 else 100
        val display = events.takeLast(limit).asReversed()
        if (display.isEmpty()) {
            llEventList.addView(TextView(this).apply {
                text = "No events yet. Events will appear automatically after monitoring starts."
                setTextColor(cSecondary)
                setPadding(dp(8), dp(16), dp(8), dp(16))
                gravity = Gravity.CENTER
            })
            return
        }

        display.forEach { evt ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(cCard)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(4)
                }
            }

            // Row 1: syscall name + NR + category
            val row1 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row1.addView(TextView(this).apply {
                text = "#${evt.seq}  ${evt.name}(${evt.nr})"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(cPrimary)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row1.addView(TextView(this).apply {
                text = StatusParser.syscallCategory(evt.nr)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(cAccent)
                setPadding(dp(6), dp(2), dp(6), dp(2))
            })
            card.addView(row1)

            // Row 2: pid/uid/comm
            card.addView(TextView(this).apply {
                text = "tgid=${evt.tgid} pid=${evt.pid} uid=${evt.uid} comm=${evt.comm}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(cSecondary)
                setOnClickListener { showPidSidebar(evt.tgid) }
            })

            // Row 3: description (the deep-parsed args)
            if (evt.desc.isNotEmpty()) {
                card.addView(TextView(this).apply {
                    text = highlightSensitive(evt.desc)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(cText)
                    typeface = Typeface.MONOSPACE
                    maxLines = 6
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, 0)
                })
            }

            val chain = eventCallChain[evt.seq].orEmpty()
            if (chain.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = highlightSensitive(chain)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(cAccent)
                    typeface = Typeface.MONOSPACE
                    maxLines = 8
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, 0)
                })
            }

            // Click for detail dialog
            card.setOnClickListener {
                showEventDetail(evt)
            }

            llEventList.addView(card)
        }
    }

    private fun filterEvents(events: List<StatusParser.SvcEvent>): List<StatusParser.SvcEvent> {
        val q = eventSearchQuery.trim()
        if (q.isEmpty()) return events
        val lq = q.lowercase()
        val out = ArrayList<StatusParser.SvcEvent>()
        for (e in events) {
            val extra = buildString {
                eventSearchExtra[e.seq]?.let { append(it.lowercase()) }
                eventCallChain[e.seq]?.let { if (isNotEmpty()) append('\n'); append(it.lowercase()) }
                if (e.bt.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append("bt_raw:")
                    e.bt.forEach { append(" 0x").append(java.lang.Long.toHexString(it)) }
                }
                if (isNotEmpty()) append('\n')
                append("seq=").append(e.seq)
                append(" nr=").append(e.nr)
                append(" tgid=").append(e.tgid)
                append(" pid=").append(e.pid)
                append(" uid=").append(e.uid)
                append(" ret=").append(e.ret)
                append(" a0=").append(e.a0).append(" a1=").append(e.a1).append(" a2=").append(e.a2)
                append(" a3=").append(e.a3).append(" a4=").append(e.a4).append(" a5=").append(e.a5)
                append(" pc=0x").append(java.lang.Long.toHexString(e.pc))
                append(" caller=0x").append(java.lang.Long.toHexString(e.caller))
                append(" fp=0x").append(java.lang.Long.toHexString(e.fp))
                append(" sp=0x").append(java.lang.Long.toHexString(e.sp))
                if (e.cloneFn != 0L) append(" clone_fn=0x").append(java.lang.Long.toHexString(e.cloneFn))
            }
            val hit =
                e.name.lowercase().contains(lq) ||
                    e.comm.lowercase().contains(lq) ||
                    e.desc.lowercase().contains(lq) ||
                    extra.contains(lq) ||
                    e.nr.toString().contains(q) ||
                    e.tgid.toString().contains(q) ||
                    e.pid.toString().contains(q) ||
                    e.uid.toString().contains(q) ||
                    e.seq.toString().contains(q) ||
                    e.ret.toString().contains(q) ||
                    e.pc.toString().contains(q) ||
                    e.caller.toString().contains(q) ||
                    e.fp.toString().contains(q) ||
                    e.sp.toString().contains(q) ||
                    e.a0.toString().contains(q) ||
                    e.a1.toString().contains(q) ||
                    e.a2.toString().contains(q) ||
                    e.a3.toString().contains(q) ||
                    e.a4.toString().contains(q) ||
                    e.a5.toString().contains(q) ||
                    (if (e.cloneFn != 0L) e.cloneFn.toString().contains(q) else false)
            if (hit) out.add(e)
        }
        return out
    }

    private fun highlightSensitive(text: String): CharSequence {
        if (text.isEmpty()) return text
        val out = SpannableString(text)
        val lower = text.lowercase()
        for (r in sensitiveRules) {
            val needle = r.needle.lowercase()
            var idx = lower.indexOf(needle)
            while (idx >= 0) {
                out.setSpan(
                    ForegroundColorSpan(r.color),
                    idx,
                    (idx + needle.length).coerceAtMost(text.length),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                idx = lower.indexOf(needle, idx + needle.length)
            }
        }
        return out
    }

    private fun showPidSidebar(pid: Int) {
        if (pid <= 0) return
        val events = lastEventsAll.filter { it.tgid == pid }
        if (events.isEmpty()) {
            tvMsg.text = "Tip: No events for PID=$pid"
            return
        }

        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        val w = dialog.window
        if (w != null) {
            w.setGravity(Gravity.END)
            w.setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(16), dp(12), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "Process analysis (PID=$pid)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(cPrimary)
            typeface = Typeface.DEFAULT_BOLD
        })

        val summaryTv = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(cText)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(ScrollView(this).apply {
            addView(summaryTv)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(8)
            }
        })

        root.addView(Button(this).apply {
            text = "Close"
            isAllCaps = false
            setOnClickListener { dialog.dismiss() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        })

        dialog.setContentView(root)

        lifecycleScope.launch {
            val text = withContext(Dispatchers.Default) {
                val uidSet = LinkedHashSet<Int>()
                val commSet = LinkedHashSet<String>()
                val cntByName = HashMap<String, Int>()
                var firstSeq = Long.MAX_VALUE
                var lastSeq = 0L
                for (e in events) {
                    uidSet.add(e.uid)
                    commSet.add(e.comm)
                    cntByName[e.name] = (cntByName[e.name] ?: 0) + 1
                    if (e.seq in 1 until firstSeq) firstSeq = e.seq
                    if (e.seq > lastSeq) lastSeq = e.seq
                }
                val top = cntByName.entries.sortedByDescending { it.value }.take(12)
                val ordered = events.sortedBy { it.seq }

                buildString {
                    appendLine("Event count: ${events.size}")
                    appendLine("UID: ${uidSet.joinToString(", ")}")
                    appendLine("Process name: ${commSet.joinToString(", ")}")
                    appendLine("Seq range: $firstSeq ~ $lastSeq")
                    appendLine()
                    appendLine("Top syscalls:")
                    top.forEach { appendLine("  ${it.key}: ${it.value}") }
                    appendLine()
                    appendLine("Call flow (by Seq):")
                    for (e in ordered) {
                        val d = e.desc.replace('\n', ' ').replace('\r', ' ').trim()
                        appendLine("#${e.seq}  ${e.name}(${e.nr})  $d")
                    }
                }
            }
            summaryTv.text = highlightSensitive(text)
        }

        dialog.show()
    }

    private fun matchesSensitive(text: String): Boolean {
        if (text.isEmpty()) return false
        val lower = text.lowercase()
        for (r in sensitiveRules) {
            if (lower.contains(r.needle.lowercase())) return true
        }
        return false
    }

    private fun startRelay() {
        if (relayJob != null) return
        relayLastEnqueuedSeq = lastEventsAll.maxOfOrNull { it.seq } ?: 0L
        relayQueue.clear()
        relayJob = lifecycleScope.launch(Dispatchers.IO) {
            while (relayEnabled) {
                if (relayQueue.isEmpty()) {
                    kotlinx.coroutines.delay(120)
                    continue
                }

                val batch = ArrayList<StatusParser.SvcEvent>(128)
                while (batch.size < 128 && relayQueue.isNotEmpty()) {
                    batch.add(relayQueue.removeFirst())
                }

                val ok = try {
                    postRelayBatch(batch)
                } catch (_: Exception) {
                    false
                }

                if (!ok) {
                    for (i in batch.size - 1 downTo 0) {
                        if (relayQueue.size >= 5000) relayQueue.removeFirst()
                        relayQueue.addFirst(batch[i])
                    }
                    kotlinx.coroutines.delay(600)
                }
            }
        }
    }

    private fun stopRelay() {
        relayJob?.cancel()
        relayJob = null
        relayQueue.clear()
    }

    private fun startPcServer() {
        if (pcServerJob != null) return
        if (pcServerPort <= 0 || pcServerPort > 65535) pcServerPort = 8080
        pcServerJob = lifecycleScope.launch(Dispatchers.IO) {
            var ss: java.net.ServerSocket? = null
            try {
                ss = java.net.ServerSocket(pcServerPort)
                ss.reuseAddress = true
                pcServerSocket = ss
                while (pcServerEnabled) {
                    val sock = try {
                        ss.accept()
                    } catch (_: Exception) {
                        break
                    }
                    val w = try {
                        java.io.BufferedWriter(java.io.OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))
                    } catch (_: Exception) {
                        try { sock.close() } catch (_: Exception) {}
                        continue
                    }
                    synchronized(pcServerClients) { pcServerClients.add(w) }

                    launch(Dispatchers.IO) {
                        val r = try {
                            java.io.BufferedReader(java.io.InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                        } catch (_: Exception) {
                            null
                        }
                        if (r != null) {
                            try {
                                while (pcServerEnabled && !sock.isClosed) {
                                    val line = r.readLine() ?: break
                                    if (line == "PING") {
                                        try {
                                            w.write("PONG\n")
                                            w.flush()
                                        } catch (_: Exception) {
                                            break
                                        }
                                    } else if (line.startsWith("HELLO")) {
                                        val seq = line.removePrefix("HELLO").trim().toLongOrNull() ?: 0L
                                        sendPcServerBacklog(w, seq)
                                    } else if (line.startsWith("CMD")) {
                                        val cmd = line.removePrefix("CMD").trim()
                                        runOnUiThread {
                                            when (cmd) {
                                                "STOP" -> vm.stopMonitoring()
                                                "CLEAR_EVENTS" -> vm.clearEvents()
                                                "CLEAR_HISTORY" -> clearHistory()
                                            }
                                        }
                                        try {
                                            w.write("OK\n")
                                            w.flush()
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                            }
                        }
                        synchronized(pcServerClients) { pcServerClients.remove(w) }
                        try { sock.close() } catch (_: Exception) {}
                    }
                }
            } finally {
                pcServerSocket = null
                try { ss?.close() } catch (_: Exception) {}
                synchronized(pcServerClients) {
                    for (w in pcServerClients) {
                        try { w.close() } catch (_: Exception) {}
                    }
                    pcServerClients.clear()
                }
                pcServerJob = null
            }
        }
    }

    private fun stopPcServer() {
        pcServerJob?.cancel()
        pcServerJob = null
        try { pcServerSocket?.close() } catch (_: Exception) {}
        pcServerSocket = null
        synchronized(pcServerClients) {
            for (w in pcServerClients) {
                try { w.close() } catch (_: Exception) {}
            }
            pcServerClients.clear()
        }
    }

    private suspend fun sendPcServerBacklog(w: java.io.BufferedWriter, afterSeq: Long) {
        val dao = SvcEventDb.get(applicationContext).dao()
        try {
            if (afterSeq > 0L) {
                var cursor = afterSeq
                var sent = 0
                while (sent < pcServerBacklogLimit) {
                    val chunkLimit = minOf(2000, pcServerBacklogLimit - sent)
                    val chunk = try { dao.afterSeq(cursor, chunkLimit) } catch (_: Exception) { emptyList() }
                    if (chunk.isEmpty()) break
                    for (e in chunk) {
                        w.write(entityToJsonLineWithResolved(e))
                        cursor = e.seq
                    }
                    w.flush()
                    sent += chunk.size
                    if (chunk.size < chunkLimit) break
                }
            } else {
                val list = try { dao.latest(pcServerBacklogLimit).asReversed() } catch (_: Exception) { emptyList() }
                if (list.isEmpty()) return
                for (e in list) {
                    w.write(entityToJsonLineWithResolved(e))
                }
                w.flush()
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun entityToJsonLineWithResolved(e: SvcEventEntity): String {
        // Reconstruct a temporary SvcEvent to resolve addresses
        val btList = if (e.bt.isBlank()) emptyList() else e.bt.split("|").mapNotNull { it.toLongOrNull(16) }
        val tmpEvent = StatusParser.SvcEvent(
            seq = e.seq, nr = e.nr, name = e.name,
            tgid = e.tgid, pid = e.pid, uid = e.uid, comm = e.comm,
            pc = e.pc, caller = e.caller, fp = e.fp, sp = e.sp, bt = btList,
            cloneFn = e.cloneFn, ret = e.ret,
            a0 = e.a0, a1 = e.a1, a2 = e.a2, a3 = e.a3, a4 = e.a4, a5 = e.a5,
            desc = e.desc
        )
        return eventToJsonLineWithResolved(tmpEvent)
    }

    private suspend fun eventToJsonLineWithResolved(e: StatusParser.SvcEvent): String {
        val resolved = resolveEventFields(e)
        val obj = JSONObject().apply {
            put("seq", e.seq)
            put("nr", e.nr)
            put("name", e.name)
            put("tgid", e.tgid)
            put("pid", e.pid)
            put("uid", e.uid)
            put("comm", e.comm)
            put("pc", e.pc)
            put("pc_resolved", resolved["pc"])
            put("caller", e.caller)
            put("caller_resolved", resolved["caller"])
            put("fp", e.fp)
            put("sp", e.sp)
            put("bt", JSONArray(e.bt))
            put("bt_resolved", resolved["bt"])
            put("clone_fn", e.cloneFn)
            put("ret", e.ret)
            put("a0", e.a0); put("a1", e.a1); put("a2", e.a2)
            put("a3", e.a3); put("a4", e.a4); put("a5", e.a5)
            put("desc", e.desc)
            eventCallChain[e.seq]?.let { put("fp_chain", it) }
        }
        return obj.toString() + "\n"
    }

    private suspend fun resolveEventFields(e: StatusParser.SvcEvent): Map<String, String> {
        val pcRes = AddressResolver.formatAddrSoOffset(e.tgid, e.pc)
        val callerRes = AddressResolver.formatAddrSoOffset(e.tgid, e.caller)
        val btRes = if (e.bt.isNotEmpty()) {
            e.bt.map { addr -> AddressResolver.formatAddrSoOffset(e.tgid, addr) }.joinToString(" ")
        } else ""
        return mapOf("pc" to pcRes, "caller" to callerRes, "bt" to btRes)
    }

    private fun broadcastPcServerEvents(events: List<StatusParser.SvcEvent>) {
        if (!pcServerEnabled) return
        if (events.isEmpty()) return
        lifecycleScope.launch {
            val lines = events.map { eventToJsonLineWithResolved(it) }
            synchronized(pcServerClients) {
                val it = pcServerClients.iterator()
                while (it.hasNext()) {
                    val w = it.next()
                    try {
                        for (ln in lines) w.write(ln)
                        w.flush()
                    } catch (_: Exception) {
                        try { w.close() } catch (_: Exception) {}
                        it.remove()
                    }
                }
            }
        }
    }

    private fun enqueueRelayEvents(events: List<StatusParser.SvcEvent>) {
        if (!relayEnabled) return
        if (events.isEmpty()) return
        val maxSeq = relayLastEnqueuedSeq
        val newEvents = events.filter { it.seq > maxSeq }.sortedBy { it.seq }
        if (newEvents.isEmpty()) return
        relayLastEnqueuedSeq = newEvents.last().seq
        for (e in newEvents) {
            if (relayQueue.size >= 5000) relayQueue.removeFirst()
            relayQueue.addLast(e)
        }
    }

    private suspend fun postRelayBatch(events: List<StatusParser.SvcEvent>): Boolean {
        val arr = JSONArray()
        for (e in events) {
            val resolved = resolveEventFields(e)
            arr.put(JSONObject().apply {
                put("seq", e.seq)
                put("nr", e.nr)
                put("name", e.name)
                put("tgid", e.tgid)
                put("pid", e.pid)
                put("uid", e.uid)
                put("comm", e.comm)
                put("pc", e.pc)
                put("pc_resolved", resolved["pc"])
                put("caller", e.caller)
                put("caller_resolved", resolved["caller"])
                put("fp", e.fp)
                put("sp", e.sp)
                put("bt", JSONArray(e.bt))
                put("bt_resolved", resolved["bt"])
                put("clone_fn", e.cloneFn)
                put("ret", e.ret)
                put("a0", e.a0); put("a1", e.a1); put("a2", e.a2)
                put("a3", e.a3); put("a4", e.a4); put("a5", e.a5)
                put("desc", e.desc)
                eventCallChain[e.seq]?.let { put("fp_chain", it) }
            })
        }
        return postRelayRaw(arr.toString())
    }

    private fun postRelayRaw(body: String): Boolean {
        val host = relayHost.trim().removePrefix("http://").removePrefix("https://")
        if (host.isBlank()) return false
        if (relayPort <= 0 || relayPort > 65535) return false

        var conn: HttpURLConnection? = null
        return try {
            val url = URL("http://$host:$relayPort/api/ingest")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2500
                readTimeout = 4500
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            BufferedOutputStream(conn.outputStream).use { os ->
                os.write(body.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val code = conn.responseCode
            if (code !in 200..299) relayLastError = "HTTP $code (/api/ingest)"
            code in 200..299
        } catch (_: Exception) {
            relayLastError = "Network connection failed (/api/ingest)"
            false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun testRelayOnce(): Boolean {
        relayLastError = ""
        val okStats = getRelayStats()
        if (!okStats && (relayPort == 5000 || relayPort == 5001)) {
            val alt = if (relayPort == 5001) 5000 else 5001
            val old = relayPort
            relayPort = alt
            val okAlt = getRelayStats()
            if (okAlt) {
                prefs.edit().putInt("pc_relay_port", relayPort).apply()
                return postRelayRaw("[]")
            }
            relayPort = old
        }
        if (!okStats) return false
        return postRelayRaw("[]")
    }

    private fun getRelayStats(): Boolean {
        val host = relayHost.trim().removePrefix("http://").removePrefix("https://")
        if (host.isBlank()) {
            relayLastError = "Host is empty"
            return false
        }
        if (relayPort <= 0 || relayPort > 65535) {
            relayLastError = "Invalid port"
            return false
        }
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("http://$host:$relayPort/api/stats")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2000
                readTimeout = 3000
            }
            val code = conn.responseCode
            if (code !in 200..299) relayLastError = "HTTP $code (/api/stats)"
            code in 200..299
        } catch (_: Exception) {
            relayLastError = "Network connection failed (/api/stats)"
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun kickResolveCallChain(events: List<StatusParser.SvcEvent>) {
        if (resolvingChain) return
        if (events.isEmpty()) return
        val batch = events.asReversed()
            .filter { it.fp > 0L && !eventCallChain.containsKey(it.seq) }
            .take(16)
        if (batch.isEmpty()) return

        resolvingChain = true
        lifecycleScope.launch {
            try {
                for (e in batch) {
                    val callerResolved = AddressResolver.formatAddrSoOffset(e.tgid, e.caller)
                    val chain = buildFpCallChain(e, callerResolved)
                    if (chain.isNotBlank()) {
                        eventCallChain[e.seq] = chain
                        withContext(Dispatchers.IO) {
                            SvcEventDb.get(applicationContext).dao().updateFpChain(e.seq, chain)
                        }
                    }
                }
            } finally {
                resolvingChain = false
                updateEventList(filterEvents(lastEventsAll))
            }
        }
    }

    private suspend fun buildFpCallChain(evt: StatusParser.SvcEvent, callerResolved: String): String {
        if (evt.tgid <= 0) return ""
        if (evt.fp <= 0L) return ""
        if (callerResolved.contains("(unmapped)")) return ""
        if (callerResolved.startsWith("[anon:")) return ""

        val lines = ArrayList<String>(16)
        lines.add("#0 $callerResolved")

        fun stripPtr(v: Long): Long = v and 0x00FFFFFFFFFFFFFFL

        var fp = stripPtr(evt.fp)
        val seen = HashSet<Long>()
        var depth = 0
        while (depth < 15) {
            if (!seen.add(fp)) break
            val words = KpmBridge.readProcMemQwords(evt.tgid, fp, 2)
            if (words.size < 2) break
            val nextFp = stripPtr(words[0])
            val lr = stripPtr(words[1])
            if (nextFp <= 0L || lr == 0L) break
            if (nextFp <= fp) break
            if (nextFp - fp > 0x40000L) break

            val lrResolved = AddressResolver.formatAddrSoOffset(evt.tgid, lr)
            if (lrResolved.contains("(unmapped)")) break
            if (lrResolved.startsWith("[anon:")) break
            lines.add("#${depth + 1} $lrResolved")
            fp = nextFp
            depth++
        }

        if (lines.size <= 1) return ""
        return lines.joinToString("\n")
    }

    private fun kickResolveForSearch() {
        if (resolvingSearch) return
        val q = eventSearchQuery.trim()
        if (q.isEmpty()) return
        val snapshot = lastEventsAll
        if (snapshot.isEmpty()) return
        if (snapshot.all { eventSearchExtra.containsKey(it.seq) }) return

        resolvingSearch = true
        lifecycleScope.launch {
            try {
                val pending = snapshot.filter { !eventSearchExtra.containsKey(it.seq) }
                for (chunk in pending.chunked(200)) {
                    for (e in chunk) {
                        val pcResolved = AddressResolver.formatAddrSoOffset(e.tgid, e.pc)
                        val callerResolved = AddressResolver.formatAddrSoOffset(e.tgid, e.caller)
                        val cloneResolved = if (e.cloneFn != 0L) AddressResolver.formatAddrSoOffset(e.tgid, e.cloneFn) else ""
                        val fdResolved = if (nrUsesFd(e.nr)) {
                            val r = KpmBridge.readProcFdLink(e.tgid, e.a0)
                            if (r.isNotBlank()) r else ""
                        } else {
                            ""
                        }
                        val chainResolved = buildFpCallChain(e, callerResolved)
                        if (chainResolved.isNotBlank()) {
                            eventCallChain[e.seq] = chainResolved
                            withContext(Dispatchers.IO) {
                                SvcEventDb.get(applicationContext).dao().updateFpChain(e.seq, chainResolved)
                            }
                        }
                        val kernelBtResolved = if (e.bt.isNotEmpty()) {
                            buildString {
                                var idx = 0
                                for (a in e.bt) {
                                    if (a == 0L) continue
                                    appendLine("#$idx ${AddressResolver.formatAddrSoOffset(e.tgid, a)}")
                                    idx++
                                    if (idx >= 16) break
                                }
                            }.trim()
                        } else {
                            ""
                        }

                        val blob = buildString {
                            appendLine("#${e.seq}  ${e.name}(${e.nr})")
                            appendLine("Category: ${StatusParser.syscallCategory(e.nr)}")
                            appendLine("TGID: ${e.tgid}  PID: ${e.pid}  UID: ${e.uid}")
                            appendLine("Process: ${e.comm}")
                            appendLine()
                            appendLine("pc: $pcResolved")
                            appendLine("caller: $callerResolved")
                            if (kernelBtResolved.isNotBlank()) {
                                appendLine()
                                appendLine("bt:")
                                appendLine(kernelBtResolved)
                            }
                            if (e.cloneFn != 0L) appendLine("clone_fn: $cloneResolved")
                            if (fdResolved.isNotEmpty()) appendLine("fd(${e.a0}): $fdResolved")
                            if (chainResolved.isNotBlank()) {
                                appendLine()
                                appendLine("Call chain:")
                                appendLine(chainResolved)
                            }
                            if (e.bt.isNotEmpty()) {
                                appendLine()
                                append("bt_raw:")
                                e.bt.forEach { append(" 0x").append(java.lang.Long.toHexString(it)) }
                                appendLine()
                            }
                            appendLine()
                            appendLine("a0: 0x${java.lang.Long.toHexString(e.a0)} (${e.a0})")
                            appendLine("a1: 0x${java.lang.Long.toHexString(e.a1)} (${e.a1})")
                            appendLine("a2: 0x${java.lang.Long.toHexString(e.a2)} (${e.a2})")
                            appendLine("a3: 0x${java.lang.Long.toHexString(e.a3)} (${e.a3})")
                            appendLine("a4: 0x${java.lang.Long.toHexString(e.a4)} (${e.a4})")
                            appendLine("a5: 0x${java.lang.Long.toHexString(e.a5)} (${e.a5})")
                            appendLine()
                            appendLine(e.desc)

                            val addrs = extractHexAddrs(e.desc).take(8)
                            if (addrs.isNotEmpty()) {
                                appendLine()
                                addrs.forEach { a ->
                                    val abs = "0x${java.lang.Long.toHexString(a)}"
                                    val so = AddressResolver.resolveAddress(e.tgid, a)
                                    appendLine(if (so.isNotEmpty()) "$abs -> $so" else "$abs -> unmapped")
                                }
                            }
                        }
                        eventSearchExtra[e.seq] = blob
                    }
                }
            } finally {
                resolvingSearch = false
                updateEventList(filterEvents(lastEventsAll))
            }
        }
    }

    private fun historyFile(): File {
        return File(filesDir, "events_history.jsonl")
    }

    private fun persistNewEvents(events: List<StatusParser.SvcEvent>) {
        val maxSeq = events.maxOfOrNull { it.seq } ?: 0L
        if (maxSeq < historyLastSeq) {
            historyLastSeq = 0L
        }
        val newEvents = events.filter { it.seq > historyLastSeq }
        if (newEvents.isEmpty()) return
        historyLastSeq = newEvents.maxOf { it.seq }
        prefs.edit().putLong("history_last_seq", historyLastSeq).apply()

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val f = historyFile()
                if (f.exists() && f.length() > 20L * 1024L * 1024L) {
                    val bak = File(f.parentFile, "events_history.${System.currentTimeMillis()}.jsonl")
                    f.renameTo(bak)
                }
                val sb = StringBuilder()
                for (e in newEvents) {
                    sb.append("{\"seq\":").append(e.seq)
                        .append(",\"nr\":").append(e.nr)
                        .append(",\"name\":\"").append(e.name.replace("\"", "\\\"")).append("\"")
                        .append(",\"pid\":").append(e.pid)
                        .append(",\"uid\":").append(e.uid)
                        .append(",\"comm\":\"").append(e.comm.replace("\"", "\\\"")).append("\"")
                        .append(",\"desc\":\"").append(e.desc.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")).append("\"")
                        .append("}\n")
                }
                f.appendText(sb.toString())
            }
        }
    }

    private fun shareHistory() {
        lifecycleScope.launch {
            val src = historyFile()
            if (!src.exists() || src.length() == 0L) {
                tvMsg.text = "Tip: No history logs"
                return@launch
            }
            val dst = withContext(Dispatchers.IO) {
                val out = File(cacheDir, "events_history.jsonl")
                src.copyTo(out, overwrite = true)
                out
            }
            shareFile(dst, "application/x-ndjson")
        }
    }

    private fun clearHistory() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val f = historyFile()
                if (f.exists()) f.delete()
            }
            historyLastSeq = 0L
            prefs.edit().putLong("history_last_seq", 0L).apply()
            tvMsg.text = "Tip: History logs cleared"
        }
    }

    private fun showEventDetail(evt: StatusParser.SvcEvent) {
        vm.viewModelScope_launch {
            val pcResolved = AddressResolver.formatAddrSoOffset(evt.tgid, evt.pc)
            val callerResolved = AddressResolver.formatAddrSoOffset(evt.tgid, evt.caller)
            val cloneResolved = if (evt.cloneFn != 0L) AddressResolver.formatAddrSoOffset(evt.tgid, evt.cloneFn) else ""
            val chainResolved = eventCallChain[evt.seq].orEmpty().ifBlank {
                val r = buildFpCallChain(evt, callerResolved)
                if (r.isNotBlank()) eventCallChain[evt.seq] = r
                r
            }
            val kernelBtResolved = if (evt.bt.isNotEmpty()) {
                buildString {
                    var idx = 0
                    for (a in evt.bt) {
                        if (a == 0L) continue
                        val resolved = AddressResolver.formatAddrSoOffset(evt.tgid, a)
                        appendLine("#$idx $resolved")
                        idx++
                        if (idx >= 16) break
                    }
                }.trim()
            } else {
                ""
            }

            val fdResolved = if (nrUsesFd(evt.nr)) {
                val r = KpmBridge.readProcFdLink(evt.tgid, evt.a0)
                if (r.isNotBlank()) r else ""
            } else {
                ""
            }

            val detail = buildString {
                appendLine("═══ Syscall Details ═══")
                appendLine()
                appendLine("#${evt.seq}  ${evt.name}(${evt.nr})")
                appendLine("Category: ${StatusParser.syscallCategory(evt.nr)}")
                appendLine("TGID: ${evt.tgid}  PID: ${evt.pid}  UID: ${evt.uid}")
                appendLine("Process: ${evt.comm}")
                appendLine()
                appendLine("pc: $pcResolved")
                appendLine("caller: $callerResolved")
                if (evt.fp != 0L) appendLine("fp: 0x${java.lang.Long.toHexString(evt.fp)}")
                if (evt.sp != 0L) appendLine("sp: 0x${java.lang.Long.toHexString(evt.sp)}")
                if (evt.nr == 220 && evt.cloneFn != 0L) {
                    appendLine("clone_fn: $cloneResolved")
                }
                if (kernelBtResolved.isNotBlank()) {
                    appendLine()
                    appendLine("═══ Kernel Backtrace (bt) ═══")
                    appendLine(kernelBtResolved)
                }
                if (fdResolved.isNotEmpty()) {
                    appendLine("fd(${evt.a0}): $fdResolved")
                }
                if (chainResolved.isNotBlank()) {
                    appendLine()
                    appendLine("═══ Call Chain (FP) ═══")
                    appendLine(chainResolved)
                }
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

                val addrs = extractHexAddrs(evt.desc).take(8)
                if (addrs.isNotEmpty()) {
                    appendLine()
                    appendLine("═══ desc Address Resolution ═══")
                    addrs.forEach { a ->
                        val abs = "0x${java.lang.Long.toHexString(a)}"
                        val so = AddressResolver.resolveAddress(evt.tgid, a)
                        appendLine(if (so.isNotEmpty()) "$abs -> $so" else "$abs -> unmapped")
                    }
                }
            }

            AlertDialog.Builder(this@MainActivity)
                .setTitle("${evt.name}(${evt.nr})")
                .setMessage(detail)
                .setPositiveButton("OK", null)
                .setNegativeButton("PID Analysis") { _, _ -> showPidSidebar(evt.tgid) }
                .setNeutralButton("Copy") { _, _ ->
                    val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clip.setPrimaryClip(android.content.ClipData.newPlainText("svc_event", detail))
                    tvMsg.text = "Tip: Copied to clipboard"
                }
                .show()
        }
    }

    private fun exportCsv() {
        lifecycleScope.launch {
            tvMsg.text = "Tip: Exporting CSV in background..."
            runCatching {
                val file = exportAllEventsCsvResolved()
                shareFile(file, "text/csv")
                tvMsg.text = "Tip: CSV exported (all events, resolved)"
            }.onFailure { e ->
                tvMsg.text = "Tip: CSV export failed: ${e.message}"
            }
        }
    }

    private fun exportJson() {
        lifecycleScope.launch {
            tvMsg.text = "Tip: Exporting JSONL in background..."
            runCatching {
                val file = exportAllEventsJsonlResolved()
                shareFile(file, "application/x-ndjson")
                tvMsg.text = "Tip: JSONL exported (all events, resolved)"
            }.onFailure { e ->
                tvMsg.text = "Tip: JSONL export failed: ${e.message}"
            }
        }
    }

    private suspend fun exportAllEventsCsvResolved(): File = withContext(Dispatchers.IO) {
        val dao = SvcEventDb.get(applicationContext).dao()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outDir = File(getExternalFilesDir(null), "exports").apply { mkdirs() }
        val outFile = File(outDir, "svc_events_resolved_$ts.csv")
        var cursor = 0L
        var wroteAny = false
        outFile.bufferedWriter().use { w ->
            w.write("seq,nr,name,tgid,pid,uid,comm,pc_resolved,caller_resolved,bt_resolved,desc")
            w.newLine()
            while (true) {
                val chunk = dao.afterSeq(cursor, 1000)
                if (chunk.isEmpty()) break
                for (entity in chunk) {
                    val line = entityToCsvResolved(entity)
                    w.write(line)
                    w.newLine()
                    cursor = entity.seq
                    wroteAny = true
                }
                w.flush()
            }
        }
        if (!wroteAny) throw IllegalStateException("No events available for export")
        outFile
    }

    private suspend fun exportAllEventsJsonlResolved(): File = withContext(Dispatchers.IO) {
        val dao = SvcEventDb.get(applicationContext).dao()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outDir = File(getExternalFilesDir(null), "exports").apply { mkdirs() }
        val outFile = File(outDir, "svc_events_resolved_$ts.jsonl")
        var cursor = 0L
        var wroteAny = false
        outFile.bufferedWriter().use { w ->
            while (true) {
                val chunk = dao.afterSeq(cursor, 1000)
                if (chunk.isEmpty()) break
                for (entity in chunk) {
                    w.write(entityToJsonLineWithResolved(entity))
                    cursor = entity.seq
                    wroteAny = true
                }
                w.flush()
            }
        }
        if (!wroteAny) throw IllegalStateException("No events available for export")
        outFile
    }

    private suspend fun entityToCsvResolved(e: SvcEventEntity): String {
        val resolved = resolveEventFields(
            StatusParser.SvcEvent(
                seq = e.seq, nr = e.nr, name = e.name,
                tgid = e.tgid, pid = e.pid, uid = e.uid, comm = e.comm,
                pc = e.pc, caller = e.caller, fp = e.fp, sp = e.sp,
                bt = if (e.bt.isBlank()) emptyList() else e.bt.split("|").mapNotNull { it.toLongOrNull(16) },
                cloneFn = e.cloneFn, ret = e.ret,
                a0 = e.a0, a1 = e.a1, a2 = e.a2, a3 = e.a3, a4 = e.a4, a5 = e.a5,
                desc = e.desc
            )
        )
        val descEscaped = e.desc.replace("\"", "\"\"")
        val commEscaped = e.comm.replace("\"", "\"\"")
        return "${e.seq},${e.nr},${e.name},${e.tgid},${e.pid},${e.uid},\"$commEscaped\",\"${resolved["pc"]}\",\"${resolved["caller"]}\",\"${resolved["bt"]}\",\"$descEscaped\""
    }

    private fun shareFile(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share"))
    }

    /* ══════════════════════════════════════════════════════════════
     *  START/STOP click handler
     * ══════════════════════════════════════════════════════════════ */

    private fun onStartStopClick() {
        if (vm.monitoring.value == true) {
            vm.stopMonitoring()
        } else {
            val app = vm.selectedApp
            if (app == null) {
                tvMsg.text = "Tip: Please select a target app"
                return
            }
            // Reset maps tracking for this PID
            AddressResolver.resetForPid(app.uid)
            val nrs = currentNrList
            if (nrs.isEmpty()) {
                vm.startMonitoring(app.uid)
            } else {
                vm.startMonitoringWithNrs(app.uid, nrs)
            }
        }
    }

    /* ══════════════════════════════════════════════════════════════
     *  UI HELPERS
     * ══════════════════════════════════════════════════════════════ */

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun makeCard(block: LinearLayout.() -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cCard)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
            elevation = dp(2).toFloat()
            block()
        }
    }

    private fun makeLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(cPrimary)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        }
    }

    private fun makeValue(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(cText)
            setPadding(0, dp(2), 0, dp(2))
        }
    }

    private fun refreshNrHighlights(nrs: List<Int>) {
        val set = nrs.toHashSet()
        for ((nr, tv) in nrNameViews) {
            if (set.contains(nr)) {
                tv.setTextColor(cGreen)
                tv.typeface = Typeface.DEFAULT_BOLD
            } else {
                tv.setTextColor(cText)
                tv.typeface = Typeface.DEFAULT
            }
        }
    }

    private fun renderFilterList(hooks: List<StatusParser.HookInfo>) {
        filterListContainer.removeAllViews()
        nrNameViews.clear()
        val syscallHooked = hooks.mapNotNull { if (it.nr >= 0) it.nr else null }.toHashSet()
        hookedNrSet = syscallHooked
        val nonSysHooks = hooks.filter { it.nr < 0 }
        if (syscallHooked.isEmpty() && nonSysHooks.isEmpty()) {
            filterListContainer.addView(TextView(this).apply {
                text = "No installed hooks"
                setTextColor(cSecondary)
                setPadding(0, dp(4), 0, dp(4))
            })
            return
        }

        if (nonSysHooks.isNotEmpty()) {
            filterListContainer.addView(TextView(this).apply {
                text = "🔧 Other hooks"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(cPrimary)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(8), 0, dp(4))
            })
            nonSysHooks.forEach { h ->
                filterListContainer.addView(TextView(this).apply {
                    text = "${h.name}  (${h.method})"
                    setTextColor(cText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dp(8), dp(2), 0, dp(2))
                })
            }
        }

        val used = HashSet<Int>()
        StatusParser.categories.forEach { cat ->
            val list = cat.syscalls.filter { syscallHooked.contains(it.nr) }
            if (list.isEmpty()) return@forEach
            filterListContainer.addView(TextView(this).apply {
                text = "${cat.icon} ${cat.name}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(cPrimary)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(8), 0, dp(4))
            })
            list.forEach { sc ->
                used.add(sc.nr)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(2), 0, dp(2))
                }
                row.addView(TextView(this).apply {
                    text = sc.nr.toString()
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(cSecondary)
                    minWidth = dp(48)
                })
                val nameView = TextView(this).apply {
                    text = "${sc.name}  ${sc.description}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(cText)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                nrNameViews[sc.nr] = nameView
                row.addView(nameView)
                row.addView(Button(this).apply {
                    text = "+"
                    setOnClickListener { vm.addNr(sc.nr) }
                })
                row.addView(Button(this).apply {
                    text = "-"
                    setOnClickListener { vm.removeNr(sc.nr) }
                })
                filterListContainer.addView(row)
            }
        }

        val extra = hooks.filter { !used.contains(it.nr) }
        if (extra.isNotEmpty()) {
            filterListContainer.addView(TextView(this).apply {
                text = "＊ Other"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(cPrimary)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(8), 0, dp(4))
            })
            extra.forEach { h ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(2), 0, dp(2))
                }
                row.addView(TextView(this).apply {
                    text = h.nr.toString()
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(cSecondary)
                    minWidth = dp(48)
                })
                val nameView = TextView(this).apply {
                    text = h.name
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(cText)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                nrNameViews[h.nr] = nameView
                row.addView(nameView)
                row.addView(Button(this).apply {
                    text = "+"
                    setOnClickListener { vm.addNr(h.nr) }
                })
                row.addView(Button(this).apply {
                    text = "-"
                    setOnClickListener { vm.removeNr(h.nr) }
                })
                filterListContainer.addView(row)
            }
        }
        renderAllNrList(etAllNrFilter.text?.toString() ?: "")
    }

    private fun renderSelectedNrs(nrs: List<Int>) {
        llSelectedNrs.removeAllViews()
        if (nrs.isEmpty()) {
            llSelectedNrs.addView(TextView(this).apply {
                text = "No syscalls selected"
                setTextColor(cSecondary)
            })
            return
        }

        nrs.sorted().forEach { nr ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
            }
            row.addView(TextView(this).apply {
                text = "${StatusParser.nrToName(nr)}($nr)"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(cText)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "Delete"
                setTextColor(cRed)
                isAllCaps = false
                setOnClickListener { vm.removeNr(nr) }
            })
            llSelectedNrs.addView(row)
        }
    }

    private fun applyPresetUi(presetId: String) {
        vm.selectedPreset = presetId
        vm.applyPreset(presetId)
    }

    private fun nrUsesFd(nr: Int): Boolean {
        return when (nr) {
            57, 62, 63, 64, 65, 66, 71, 80, 29, 46, 45, 43, 25 -> true
            else -> false
        }
    }

    private fun extractHexAddrs(text: String): List<Long> {
        val re = Regex("0x[0-9a-fA-F]{6,16}")
        val seen = LinkedHashSet<Long>()
        for (m in re.findAll(text)) {
            val raw = m.value.substring(2)
            val v = raw.toLongOrNull(16) ?: continue
            if (v != 0L) seen.add(v)
            if (seen.size >= 16) break
        }
        return seen.toList()
    }

    /* helper for filter tab buttons to launch coroutine */
    private fun MainViewModel.viewModelScope_launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    override fun onDestroy() {
        super.onDestroy()
        vm.stopPolling()
    }
}
