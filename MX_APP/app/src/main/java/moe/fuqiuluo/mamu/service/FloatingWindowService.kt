package moe.fuqiuluo.mamu.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.mamu.MainActivity
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.data.settings.filterLinuxProcess
import moe.fuqiuluo.mamu.data.settings.filterSystemProcess
import moe.fuqiuluo.mamu.data.settings.selectedMemoryRanges
import moe.fuqiuluo.mamu.data.settings.topMostLayer
import moe.fuqiuluo.mamu.databinding.FloatingFullscreenLayoutBinding
import moe.fuqiuluo.mamu.databinding.FloatingWindowLayoutBinding
import moe.fuqiuluo.mamu.driver.FreezeManager
import moe.fuqiuluo.mamu.driver.ProcessDeathMonitor
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.FloatingWindowStateManager
import moe.fuqiuluo.mamu.floating.adapter.ProcessListAdapter
import moe.fuqiuluo.mamu.floating.controller.BreakpointController
import moe.fuqiuluo.mamu.floating.controller.MemoryPreviewController
import moe.fuqiuluo.mamu.floating.controller.SavedAddressController
import moe.fuqiuluo.mamu.floating.controller.SearchController
import moe.fuqiuluo.mamu.floating.controller.SettingsController
import moe.fuqiuluo.mamu.floating.data.model.DisplayProcessInfo
import moe.fuqiuluo.mamu.floating.data.model.MemoryRange
import moe.fuqiuluo.mamu.floating.dialog.CustomDialog
import moe.fuqiuluo.mamu.floating.dialog.MemoryRangeDialog
import moe.fuqiuluo.mamu.floating.dialog.OffsetCalculatorDialog
import moe.fuqiuluo.mamu.floating.dialog.OffsetXorDialog
import moe.fuqiuluo.mamu.floating.event.FloatingEventBus
import moe.fuqiuluo.mamu.floating.event.NavigateToMemoryAddressEvent
import moe.fuqiuluo.mamu.floating.event.ProcessStateEvent
import moe.fuqiuluo.mamu.floating.event.UIActionEvent
import moe.fuqiuluo.mamu.floating.ext.applyOpacity
import moe.fuqiuluo.mamu.floating.ext.divideToSimpleMemoryRange
import moe.fuqiuluo.mamu.floating.listener.DraggableFloatingIconTouchListener
import moe.fuqiuluo.mamu.utils.ApplicationUtils
import moe.fuqiuluo.mamu.utils.RootConfigManager
import moe.fuqiuluo.mamu.utils.RootShellExecutor
import moe.fuqiuluo.mamu.utils.onError
import moe.fuqiuluo.mamu.utils.onSuccess
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import moe.fuqiuluo.mamu.widget.RealtimeMonitorOverlay

private const val TAG = "FloatingWindowService"
private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "floating_window_service"

class FloatingWindowService : Service(), ProcessDeathMonitor.Callback {
    // Coroutine scope
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Window manager
    private lateinit var windowManager: WindowManager

    // Binding for the collapsed floating icon UI
    private lateinit var floatingIconBinding: FloatingWindowLayoutBinding

    // Binding for the expanded fullscreen UI
    private lateinit var fullscreenBinding: FloatingFullscreenLayoutBinding

    private val floatingIconView: View get() = floatingIconBinding.root
    private val fullscreenView: View get() = fullscreenBinding.root

    // Notifications
    private val notification by lazy {
        NotificationOverlay(this)
    }

    // Feature controllers
    private lateinit var searchController: SearchController
    private lateinit var settingsController: SettingsController
    private lateinit var savedAddressController: SavedAddressController
    private lateinit var memoryPreviewController: MemoryPreviewController
    private lateinit var breakpointController: BreakpointController

    // Cache the screen orientation to avoid redundant layout work
    private var currentOrientation = Configuration.ORIENTATION_UNDEFINED

    // Tab index constants
    private companion object TabIndices {
        const val TAB_SETTINGS = 0
        const val TAB_SEARCH = 1
        const val TAB_SAVED_ADDRESSES = 2
        const val TAB_MEMORY_PREVIEW = 3
        const val TAB_BREAKPOINTS = 4
    }

    // Tracks whether a tab switch is programmatic to avoid recursive callbacks
    private var isProgrammaticTabSwitch = false

    // Dialog lock for process selection to prevent duplicate popups
    private val isProcessDialogShowing = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()

        // Create the foreground-service notification
        createNotificationChannel()
        val notification = createForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Create a context wrapped with the Material Components theme
        val themedContext = ContextThemeWrapper(this, R.style.Theme_MX)

        floatingIconBinding =
            FloatingWindowLayoutBinding.inflate(LayoutInflater.from(themedContext))
        fullscreenBinding =
            FloatingFullscreenLayoutBinding.inflate(LayoutInflater.from(themedContext))

        setupFloatingIcon()
        setupFullscreenView()

        if (!WuwaDriver.loaded) {
            Toast.makeText(this, "Driver load failed. Please restart the app.", Toast.LENGTH_SHORT).show()
            throw RuntimeException("WuwaDriver is not loaded")
        }

        initializeControllers()
        subscribeToUIActionEvents()
        subscribeToMemoryRangeChangedEvents()
        subscribeToProcessStateEvents()

        // Notify listeners that the overlay has started
        FloatingWindowStateManager.setActive(true)
    }

    /**
     * Subscribe to UI action request events
     */
    private fun subscribeToUIActionEvents() {
        coroutineScope.launch {
            FloatingEventBus.uiActionEvents.collect { event ->
                when (event) {
                    is UIActionEvent.ShowProcessSelectionDialog -> showProcessSelectionDialog()

                    is UIActionEvent.ShowMemoryRangeDialog -> showMemoryRangeDialog()

                    is UIActionEvent.ShowOffsetCalculatorDialog -> showOffsetCalculatorDialog(event.initialBaseAddress)

                    is UIActionEvent.ShowOffsetXorDialog -> {
                        showOffsetXorDialog(event.selectedAddresses)
                    }

                    is UIActionEvent.BindProcessRequest -> handleBindProcess(event.process)

                    is UIActionEvent.UnbindProcessRequest -> handleUnbindProcess(isUserInitiated = true)

                    is UIActionEvent.ExitOverlayRequest -> stopSelf()

                    is UIActionEvent.ApplyOpacityRequest -> fullscreenBinding.applyOpacity()

                    is UIActionEvent.HideFloatingWindow -> hideFullscreen()

                    is UIActionEvent.SwitchToSettingsTab -> {
                        isProgrammaticTabSwitch = true
                        fullscreenBinding.tabLayout.selectTab(
                            fullscreenBinding.tabLayout.getTabAt(
                                TAB_SETTINGS
                            )
                        )
                        fullscreenBinding.sidebarNavigationRail.selectedItemId =
                            R.id.navigation_settings
                        updateNavigationRailIndicator(
                            fullscreenBinding.sidebarNavigationRail,
                            R.id.navigation_settings
                        )
                        isProgrammaticTabSwitch = false
                        switchToTab(TAB_SETTINGS)
                    }

                    is UIActionEvent.SwitchToSearchTab -> {
                        isProgrammaticTabSwitch = true
                        fullscreenBinding.tabLayout.selectTab(
                            fullscreenBinding.tabLayout.getTabAt(
                                TAB_SEARCH
                            )
                        )
                        fullscreenBinding.sidebarNavigationRail.selectedItemId =
                            R.id.navigation_search
                        updateNavigationRailIndicator(
                            fullscreenBinding.sidebarNavigationRail,
                            R.id.navigation_search
                        )
                        isProgrammaticTabSwitch = false
                        switchToTab(TAB_SEARCH)
                    }

                    is UIActionEvent.SwitchToSavedAddressesTab -> {
                        isProgrammaticTabSwitch = true
                        fullscreenBinding.tabLayout.selectTab(
                            fullscreenBinding.tabLayout.getTabAt(
                                TAB_SAVED_ADDRESSES
                            )
                        )
                        fullscreenBinding.sidebarNavigationRail.selectedItemId =
                            R.id.navigation_saved_addresses
                        updateNavigationRailIndicator(
                            fullscreenBinding.sidebarNavigationRail,
                            R.id.navigation_saved_addresses
                        )
                        isProgrammaticTabSwitch = false
                        switchToTab(TAB_SAVED_ADDRESSES)
                    }

                    is UIActionEvent.SwitchToMemoryPreviewTab -> {
                        isProgrammaticTabSwitch = true
                        fullscreenBinding.tabLayout.selectTab(
                            fullscreenBinding.tabLayout.getTabAt(
                                TAB_MEMORY_PREVIEW
                            )
                        )
                        fullscreenBinding.sidebarNavigationRail.selectedItemId =
                            R.id.navigation_memory_preview
                        updateNavigationRailIndicator(
                            fullscreenBinding.sidebarNavigationRail,
                            R.id.navigation_memory_preview
                        )
                        isProgrammaticTabSwitch = false
                        switchToTab(TAB_MEMORY_PREVIEW)
                    }

                    is UIActionEvent.SwitchToBreakpointsTab -> {
                        isProgrammaticTabSwitch = true
                        fullscreenBinding.tabLayout.selectTab(
                            fullscreenBinding.tabLayout.getTabAt(
                                TAB_BREAKPOINTS
                            )
                        )
                        fullscreenBinding.sidebarNavigationRail.selectedItemId =
                            R.id.navigation_breakpoints
                        updateNavigationRailIndicator(
                            fullscreenBinding.sidebarNavigationRail,
                            R.id.navigation_breakpoints
                        )
                        isProgrammaticTabSwitch = false
                        switchToTab(TAB_BREAKPOINTS)
                    }

                    is UIActionEvent.JumpToMemoryPreview -> {
                        // 先切换ToMemory预览tab
                        isProgrammaticTabSwitch = true
                        fullscreenBinding.tabLayout.selectTab(
                            fullscreenBinding.tabLayout.getTabAt(
                                TAB_MEMORY_PREVIEW
                            )
                        )
                        fullscreenBinding.sidebarNavigationRail.selectedItemId =
                            R.id.navigation_memory_preview
                        updateNavigationRailIndicator(
                            fullscreenBinding.sidebarNavigationRail,
                            R.id.navigation_memory_preview
                        )
                        isProgrammaticTabSwitch = false
                        switchToTab(TAB_MEMORY_PREVIEW)

                        FloatingEventBus.tryEmitNavigateToMemoryAddress(
                            NavigateToMemoryAddressEvent(address = event.address)
                        )
                    }

                    is UIActionEvent.UpdateSearchBadge -> {
                        updateTabBadge(TAB_SEARCH, event.count, event.total)
                    }

                    is UIActionEvent.UpdateSavedAddressBadge -> {
                        updateTabBadge(TAB_SAVED_ADDRESSES, event.count, null)
                    }

                    is UIActionEvent.UpdateSelectedCount -> {
                        updateSelectedCount(event.count)
                    }
                }
            }
        }
    }

    /**
     * Subscribe to memory-range configuration changes
     */
    private fun subscribeToMemoryRangeChangedEvents() {
        coroutineScope.launch {
            FloatingEventBus.memoryRangeChangedEvents.collect {
                updateBottomInfoBar()
            }
        }
    }

    /**
     * Subscribe to process-state changes to refresh the top icon
     */
    private fun subscribeToProcessStateEvents() {
        coroutineScope.launch {
            FloatingEventBus.processStateEvents.collect { event ->
                when (event.type) {
                    ProcessStateEvent.Type.BOUND -> {
                        updateTopIcon(event.process)
                    }

                    ProcessStateEvent.Type.UNBOUND, ProcessStateEvent.Type.DIED -> {
                        updateTopIcon(null)
                    }
                }
            }
        }
    }

    /**
     * Handle process bind requests
     */
    private fun handleBindProcess(process: DisplayProcessInfo) {
        if (!WuwaDriver.isAllowedBindProc(process.packageName ?: process.cmdline)) {
            notification.showError("Binding to this process is not allowed!")
            return
        }

        // Unbind the currently attached process first, if any
        if (WuwaDriver.isProcessBound) {
            FreezeManager.clearAll()
            WuwaDriver.unbindProcess()
            ProcessDeathMonitor.stop()
        }

        runCatching {
            val success = WuwaDriver.bindProcess(process.pid)
            if (!success) {
                notification.showError(getString(R.string.error_bind_process_failed))
                return
            }

            // Start process-death monitoring
            ProcessDeathMonitor.start(process.pid, this)
            
            // Start the freeze manager
            FreezeManager.start()
        }.onFailure {
            it.printStackTrace()
            notification.showError(
                getString(R.string.error_bind_process_failed_with_reason, it.message.orEmpty())
            )
        }.onSuccess {
            notification.showSuccess(getString(R.string.success_process_selected, process.name))

            // Publish the process-bound event
            coroutineScope.launch {
                FloatingEventBus.emitProcessState(
                    ProcessStateEvent(ProcessStateEvent.Type.BOUND, process)
                )
            }
        }
    }

    /**
     * Handle process unbinding
     * @param isUserInitiated Whether the action was user initiated (terminate-process button)
     */
    private fun handleUnbindProcess(isUserInitiated: Boolean) {
        if (!WuwaDriver.isProcessBound) {
            if (isUserInitiated) {
                notification.showError(getString(R.string.error_no_bound_process))
            }
            return
        }

        val pid = WuwaDriver.currentBindPid

        if (isUserInitiated) {
            // The user explicitly terminated the process
            RootShellExecutor.exec(
                suCmd = RootConfigManager.getCustomRootCommand(), "kill -9 $pid", 1000
            ).onSuccess {
                notification.showSuccess(getString(R.string.success_process_terminated))
            }.onError {
                notification.showError(getString(R.string.error_terminate_failed))
            }
        }

        // Stop the freeze manager and clear all frozen entries
        FreezeManager.clearAll()

        WuwaDriver.unbindProcess()
        ProcessDeathMonitor.stop()

        // Publish the process-unbound event
        coroutineScope.launch {
            FloatingEventBus.emitProcessState(
                ProcessStateEvent(ProcessStateEvent.Type.UNBOUND, null)
            )
        }
    }

    /**
     * Handle process death
     */
    private fun handleProcessDied(pid: Int) {
        notification.showError(getString(R.string.error_process_died, pid))

        // Stop the freeze manager and clear all frozen entries
        FreezeManager.clearAll()

        if (WuwaDriver.isProcessBound) {
            WuwaDriver.unbindProcess()
        }
        ProcessDeathMonitor.stop()

        // Publish the process-died event
        coroutineScope.launch {
            FloatingEventBus.emitProcessState(
                ProcessStateEvent(ProcessStateEvent.Type.DIED, null)
            )
        }
    }

    /**
     * Show the process-selection dialog
     */
    @SuppressLint("SetTextI18n")
    private fun showProcessSelectionDialog() {
        // Atomically try to acquire the lock; failure means another dialog is already visible
        if (!isProcessDialogShowing.compareAndSet(false, true)) return

        coroutineScope.launch {
            runCatching {
                val mmkv = MMKV.defaultMMKV()
                val filterSystem = mmkv.filterSystemProcess
                val filterLinux = mmkv.filterLinuxProcess

                val processList = withContext(Dispatchers.IO) {
                    WuwaDriver.listProcessesWithInfo().filter { process ->
                        when {
                            filterSystem && ApplicationUtils.isSystemApp(
                                this@FloatingWindowService, process.uid
                            ) -> false

                            filterLinux && process.uid < 1000 -> false
                            else -> true
                        }
                    }.map { process ->
                        when {
                            process.name.isEmpty() || ApplicationUtils.isSystemApp(
                                this@FloatingWindowService, process.uid
                            ) -> {
                                DisplayProcessInfo(
                                    icon = ApplicationUtils.getAndroidIcon(this@FloatingWindowService),
                                    name = process.name,
                                    packageName = null,
                                    pid = process.pid,
                                    uid = process.uid,
                                    prio = 1,
                                    rss = process.rss,
                                    cmdline = process.name
                                )
                            }

                            else -> {
                                val packageName = process.name.split(":").first()
                                var prio = 3

                                val appIcon = ApplicationUtils.getAppIconByPackageName(
                                    this@FloatingWindowService, packageName
                                ) ?: ApplicationUtils.getAppIconByUid(
                                    this@FloatingWindowService, process.uid
                                ) ?: ApplicationUtils.getAndroidIcon(this@FloatingWindowService)
                                    .also { prio-- }

                                val appName = ApplicationUtils.getAppNameByPackageName(
                                    this@FloatingWindowService, packageName
                                ) ?: ApplicationUtils.getAppNameByUid(
                                    this@FloatingWindowService, process.uid
                                ) ?: process.name.also { prio-- }

                                DisplayProcessInfo(
                                    icon = appIcon,
                                    name = appName,
                                    packageName = packageName,
                                    pid = process.pid,
                                    uid = process.uid,
                                    prio = prio,
                                    rss = process.rss,
                                    cmdline = process.name
                                )
                            }
                        }
                    }.sortedByDescending { it.rss }  // Sort by memory usage in descending order
                }

                val adapter = ProcessListAdapter(this@FloatingWindowService, processList)
                CustomDialog(
                    context = this@FloatingWindowService,
                    title = getString(R.string.settings_select_process),
                    adapter = adapter,
                ).apply {
                    onItemClick = { position ->
                        val selectedProcess = processList[position]
                        FloatingEventBus.tryEmitUIAction(
                            UIActionEvent.BindProcessRequest(selectedProcess)
                        )
                    }
                    onCancel = { isProcessDialogShowing.set(false) }
                    onDismiss = { isProcessDialogShowing.set(false) }
                    show()
                }
            }.onFailure {
                isProcessDialogShowing.set(false)
                Log.e(TAG, it.stackTraceToString())
                notification.showError("Failed to load process list: ${it.message}")
            }
        }
    }

    /**
     * Show the memory-range selection dialog
     */
    private fun showMemoryRangeDialog() {
        val mmkv = MMKV.defaultMMKV()
        val allRanges = MemoryRange.entries.toTypedArray()
        val selectedRanges = mmkv.selectedMemoryRanges
        val checkedItems = allRanges.map { selectedRanges.contains(it) }.toBooleanArray()

        // Default selected memory ranges
        val defaultRanges = setOf(
            MemoryRange.Jh,
            MemoryRange.Ch,
            MemoryRange.Ca,
            MemoryRange.Cd,
            MemoryRange.Cb,
            MemoryRange.Ps,
            MemoryRange.An
        )
        val defaultCheckedItems = allRanges.map { defaultRanges.contains(it) }.toBooleanArray()

        val memorySizes = if (WuwaDriver.isProcessBound) runCatching {
            val regions = WuwaDriver.queryMemRegions().divideToSimpleMemoryRange()
            regions.groupBy { it.range }.mapValues { (_, entries) ->
                entries.sumOf { it.end - it.start }
            }
        }.getOrNull() else {
            null
        }

        val dialog = MemoryRangeDialog(
            context = this,
            memoryRanges = allRanges,
            checkedItems = checkedItems,
            memorySizes = memorySizes,
            defaultCheckedItems = defaultCheckedItems
        )

        dialog.onMultiChoice = { newCheckedItems ->
            val newRanges = allRanges.filterIndexed { index, _ -> newCheckedItems[index] }.toSet()
            mmkv.selectedMemoryRanges = newRanges
            notification.showSuccess(getString(R.string.success_memory_range_saved))

            // Emit the memory-range changed event
            coroutineScope.launch {
                FloatingEventBus.emitMemoryRangeChanged()
            }
        }

        dialog.show()
    }

    /**
     * Show the offset-calculator dialog
     */
    private fun showOffsetCalculatorDialog(initialBaseAddress: Long?) {
        val dialog = OffsetCalculatorDialog(
            context = this,
            notification = notification,
            clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager,
            initialBaseAddress = initialBaseAddress
        )

        dialog.show()
    }

    /**
     * Show the offset-XOR dialog
     */
    private fun showOffsetXorDialog(selectedAddresses: List<moe.fuqiuluo.mamu.floating.data.model.SavedAddress>) {
        val dialog = OffsetXorDialog(
            context = this,
            notification = notification,
            clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager,
            selectedAddresses = selectedAddresses
        )
        dialog.show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingIcon() {
        val preferTopMost = MMKV.defaultMMKV().topMostLayer

        var layoutFlag = if (preferTopMost) {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

        val iconSizePx = resources.getDimensionPixelSize(R.dimen.overlay_icon_size)
        val params = WindowManager.LayoutParams(
            iconSizePx,
            iconSizePx,
            layoutFlag,
            // Enable hardware acceleration to improve rendering performance
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = dp(100)

        runCatching {
            windowManager.addView(floatingIconView, params)
        }.onFailure {
            if (preferTopMost) {
                Log.w(
                    TAG,
                    "Failed to create TYPE_SYSTEM_ALERT window, falling back to TYPE_APPLICATION_OVERLAY",
                    it
                )
                layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                }
                params.type = layoutFlag
                windowManager.addView(floatingIconView, params)
                notification.showError(getString(R.string.error_topmost_fallback))
            } else {
                throw it
            }
        }

        floatingIconView.setOnTouchListener(
            DraggableFloatingIconTouchListener(
                floatingIconView = floatingIconView,
                params = params,
                windowManager = windowManager,
                touchSlop = ViewConfiguration.get(this).scaledTouchSlop,
                showFullscreen = ::showFullscreen
            )
        )
    }

    private fun setupFullscreenView() {
        fullscreenView.visibility = View.GONE

        val preferTopMost = MMKV.defaultMMKV().topMostLayer

        var layoutFlag = if (preferTopMost) {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            // Enable hardware acceleration to improve rendering performance
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START

        runCatching {
            windowManager.addView(fullscreenView, params)
        }.onFailure {
            if (preferTopMost) {
                Log.w(
                    TAG,
                    "Failed to create TYPE_SYSTEM_ALERT fullscreen window, falling back to TYPE_APPLICATION_OVERLAY",
                    it
                )
                layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                }
                params.type = layoutFlag
                windowManager.addView(fullscreenView, params)
            } else {
                throw it
            }
        }

        fullscreenBinding.applyOpacity()

        setupTopBar()

        initializeBottomInfoBar()

        // Default to the Search tab (using the TabLayout and NavigationRail APIs)
        isProgrammaticTabSwitch = true
        fullscreenBinding.tabLayout.selectTab(fullscreenBinding.tabLayout.getTabAt(TAB_SEARCH))
        fullscreenBinding.sidebarNavigationRail.selectedItemId = R.id.navigation_search
        isProgrammaticTabSwitch = false
        switchToTab(TAB_SEARCH)

        // Initialize layout direction from the current screen orientation
        currentOrientation = resources.configuration.orientation
        adjustLayoutForOrientation(currentOrientation)
    }

    private fun setupTopBar() {
        // App icon in the top toolbar
        fullscreenBinding.attachedAppIcon.setOnClickListener {
            showProcessSelectionDialog()
        }

        // Close button
        fullscreenBinding.btnCloseFullscreen.setOnClickListener {
            hideFullscreen()
        }

        // App icon in the sidebar
        fullscreenBinding.sidebarAppIcon.setOnClickListener {
            showProcessSelectionDialog()
        }

        // Sidebar close button
        fullscreenBinding.sidebarBtnClose.setOnClickListener {
            hideFullscreen()
        }

        // Top-right close button in landscape mode
        fullscreenBinding.btnCloseLandscape.setOnClickListener {
            hideFullscreen()
        }

        // Configure the TabLayout in the top toolbar
        fullscreenBinding.tabLayout.apply {
            removeAllTabs()
            addTab(
                newTab().setIcon(R.drawable.icon_settings_24px)
                    .setContentDescription(getString(R.string.tab_settings))
            )
            addTab(
                newTab().setIcon(R.drawable.icon_search_24px)
                    .setContentDescription(getString(R.string.tab_search))
            )
            addTab(
                newTab().setIcon(R.drawable.icon_save_24px)
                    .setContentDescription(getString(R.string.tab_saved_addresses))
            )
            addTab(
                newTab().setIcon(R.drawable.icon_list_24px)
                    .setContentDescription(getString(R.string.tab_memory_preview))
            )
            addTab(
                newTab().setIcon(R.drawable.icon_bug_report_24px)
                    .setContentDescription(getString(R.string.tab_breakpoints))
            )

            addOnTabSelectedListener(object :
                com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                    if (isProgrammaticTabSwitch) return
                    tab?.let {
                        switchToTab(it.position)
                        // Sync the sidebar NavigationRail
                        val itemId = getNavigationItemIdByIndex(it.position)
                        if (fullscreenBinding.sidebarNavigationRail.selectedItemId != itemId) {
                            isProgrammaticTabSwitch = true
                            fullscreenBinding.sidebarNavigationRail.selectedItemId = itemId
                            updateNavigationRailIndicator(
                                fullscreenBinding.sidebarNavigationRail,
                                itemId
                            )
                            isProgrammaticTabSwitch = false
                        }
                    }
                }

                override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
                override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            })
        }

        // Configure the sidebar NavigationRail
        fullscreenBinding.sidebarNavigationRail.apply {
            setOnItemSelectedListener { item ->
                if (isProgrammaticTabSwitch) return@setOnItemSelectedListener true

                val tabIndex = getTabIndexByNavigationItemId(item.itemId)
                switchToTab(tabIndex)

                // Update the custom indicator
                updateNavigationRailIndicator(this, item.itemId)

                // Sync the top TabLayout
                if (fullscreenBinding.tabLayout.selectedTabPosition != tabIndex) {
                    isProgrammaticTabSwitch = true
                    fullscreenBinding.tabLayout.selectTab(
                        fullscreenBinding.tabLayout.getTabAt(
                            tabIndex
                        )
                    )
                    isProgrammaticTabSwitch = false
                }
                true
            }

            // Add the custom left-side indicator
            post {
                addCustomIndicatorToNavigationRail(this)
            }
        }
    }

    /**
     * Add the custom left indicator to the NavigationRail
     */
    private fun addCustomIndicatorToNavigationRail(navigationRail: com.google.android.material.navigationrail.NavigationRailView) {
        try {
            val menuView = navigationRail.getChildAt(0) as? ViewGroup ?: return

            for (i in 0 until menuView.childCount) {
                val itemView = menuView.getChildAt(i) as? ViewGroup ?: continue

                // Create the left-side indicator
                val indicator = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        dp(4), // width: 4dp
                        dp(32) // height: 32dp
                    ).apply {
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    }
                    setBackgroundColor(resources.getColor(R.color.floating_primary, null))
                    visibility = View.GONE // Hidden by default
                    tag = "custom_indicator"
                }

                // Add the indicator to the item
                if (itemView is FrameLayout) {
                    itemView.addView(indicator, 0) // Add it at the back of the view stack
                }
            }

            // Refresh the indicator visibility state
            updateNavigationRailIndicator(navigationRail, navigationRail.selectedItemId)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to add custom indicator to NavigationRail", e)
        }
    }

    /**
     * Update NavigationRail indicator visibility
     */
    private fun updateNavigationRailIndicator(
        navigationRail: com.google.android.material.navigationrail.NavigationRailView,
        selectedItemId: Int
    ) {
        try {
            val menuView = navigationRail.getChildAt(0) as? ViewGroup ?: return

            for (i in 0 until menuView.childCount) {
                val itemView = menuView.getChildAt(i) as? ViewGroup ?: continue
                val indicator = itemView.findViewWithTag<View>("custom_indicator") ?: continue

                val menuItem = navigationRail.menu.getItem(i)
                indicator.visibility =
                    if (menuItem.itemId == selectedItemId) View.VISIBLE else View.GONE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update NavigationRail indicator", e)
        }
    }

    /**
     * Resolve the NavigationRail item ID from a tab index
     */
    private fun getNavigationItemIdByIndex(index: Int): Int {
        return when (index) {
            TAB_SETTINGS -> R.id.navigation_settings
            TAB_SEARCH -> R.id.navigation_search
            TAB_SAVED_ADDRESSES -> R.id.navigation_saved_addresses
            TAB_MEMORY_PREVIEW -> R.id.navigation_memory_preview
            TAB_BREAKPOINTS -> R.id.navigation_breakpoints
            else -> R.id.navigation_search
        }
    }

    /**
     * Resolve the tab index from a NavigationRail item ID
     */
    private fun getTabIndexByNavigationItemId(itemId: Int): Int {
        return when (itemId) {
            R.id.navigation_settings -> TAB_SETTINGS
            R.id.navigation_search -> TAB_SEARCH
            R.id.navigation_saved_addresses -> TAB_SAVED_ADDRESSES
            R.id.navigation_memory_preview -> TAB_MEMORY_PREVIEW
            R.id.navigation_breakpoints -> TAB_BREAKPOINTS
            else -> TAB_SEARCH
        }
    }

    /**
     * Switch the content view by tab index
     */
    private fun switchToTab(tabIndex: Int) {
        val contentId = when (tabIndex) {
            TAB_SETTINGS -> R.id.content_settings
            TAB_SEARCH -> R.id.content_search
            TAB_SAVED_ADDRESSES -> R.id.content_saved_addresses
            TAB_MEMORY_PREVIEW -> R.id.content_memory_preview
            TAB_BREAKPOINTS -> R.id.content_breakpoints
            else -> R.id.content_search
        }

        val contentContainer = fullscreenBinding.contentContainer

        // Hide all content views
        for (i in 0 until contentContainer.childCount) {
            contentContainer.getChildAt(i).visibility = View.GONE
        }

        // Show the selected content view
        contentContainer.findViewById<View>(contentId)?.visibility = View.VISIBLE

        // Clear the selected-count display when switching tabs
        updateSelectedCount(0)
    }

    /**
     * Update the tab badge count
     * @param tabIndex Tab index
     * @param count Currently displayed count
     * @param total Total count (optional, used for count/total formatting)
     */
    @SuppressLint("SetTextI18n")
    private fun updateTabBadge(tabIndex: Int, count: Int, total: Int?) {
        // Update the top TabLayout badge in portrait mode
        val tab = fullscreenBinding.tabLayout.getTabAt(tabIndex)
        // Update the sidebar NavigationRail badge in landscape mode
        val menuItemId = getNavigationItemIdByIndex(tabIndex)

        if (count <= 0 && (total == null || total <= 0)) {
            // Clear the badge
            tab?.removeBadge()
            fullscreenBinding.sidebarNavigationRail.removeBadge(menuItemId)
        } else {
            // Compute the badge text
            val badgeText = if (count > 9999) "9999+" else "$count"

            // Update the TabLayout badge
            tab?.let {
                val badge = it.orCreateBadge
                badge.backgroundColor = getColor(R.color.floating_primary)
                badge.badgeTextColor = getColor(android.R.color.white)
                badge.maxCharacterCount = 6
                if (total != null && total > 0) {
                    badge.clearNumber()
                    badge.text = badgeText
                } else {
                    if (count > 9999) {
                        badge.text = badgeText
                    } else {
                        badge.number = count
                    }
                }
            }

            // Update the NavigationRail badge
            val railBadge = fullscreenBinding.sidebarNavigationRail.getOrCreateBadge(menuItemId)
            railBadge.backgroundColor = getColor(R.color.floating_primary)
            railBadge.badgeTextColor = getColor(android.R.color.white)
            railBadge.maxCharacterCount = 6
            if (total != null && total > 0) {
                railBadge.clearNumber()
                railBadge.text = badgeText
            } else {
                if (count > 9999) {
                    railBadge.text = badgeText
                } else {
                    railBadge.number = count
                }
            }
        }
    }


    private fun initializeControllers() {
        // Initialize savedAddressController first because searchController depends on it
        savedAddressController = SavedAddressController(
            context = this,
            binding = fullscreenBinding.contentSavedAddresses,
            notification = notification
        )

        // TODO: Reattach badge views to the new TabLayout
        // Set up badge views for the top toolbar and sidebar
        // savedAddressController.setAddressCountBadgeView(
        //     fullscreenBinding.badgeSavedAddresses, fullscreenBinding.sidebarBadgeSavedAddresses
        // )

        searchController = SearchController(
            context = this,
            binding = fullscreenBinding.contentSearch,
            notification = notification,
            clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager,
        )

        settingsController = SettingsController(
            context = this, binding = fullscreenBinding.contentSettings, notification = notification
        )

        memoryPreviewController = MemoryPreviewController(
            context = this,
            binding = fullscreenBinding.contentMemoryPreview,
            notification = notification
        )

        breakpointController = BreakpointController(
            context = this,
            binding = fullscreenBinding.contentBreakpoints,
            notification = notification
        )

        // Initialize all controllers
        searchController.initialize()
        settingsController.initialize()
        savedAddressController.initialize()
        memoryPreviewController.initialize()
        breakpointController.initialize()
    }

    private fun initializeBottomInfoBar() {
        updateBottomInfoBar()

        fullscreenBinding.tvSelectedMemoryRanges.setOnClickListener {
            // Emit the show-memory-range-dialog event
            FloatingEventBus.tryEmitUIAction(UIActionEvent.ShowMemoryRangeDialog)
        }
    }

    private fun hideFullscreen() {
        // Hide the search progress dialog if a search is in progress
        searchController.hideSearchProgressIfNeeded()

        fullscreenView.visibility = View.GONE
        floatingIconView.visibility = View.VISIBLE

        // Refresh the memory browser
        memoryPreviewController.refreshSilently()

        // Show all realtime monitors again
        RealtimeMonitorOverlay.showAll()
    }

    private fun showFullscreen() {
        // Hide all realtime monitors to reduce interference
        RealtimeMonitorOverlay.hideAll()

        fullscreenView.visibility = View.VISIBLE
        floatingIconView.visibility = View.GONE

        // Refresh the memory browser
        memoryPreviewController.refreshSilently()

        // Refresh values in the search-result list
        searchController.refreshSilently()

        // Restore the search progress dialog if a search is in progress
        searchController.showSearchProgressIfNeeded()
        // Restore the fuzzy-search dialog if the search completed and still has results
        searchController.showFuzzySearchDialogIfCompleted()
        // Restore the pointer-scan dialog while scanning
        searchController.showPointerScannerProgressIfNeeded()

        // Automatically show the process-selection dialog if nothing is bound
        if (!WuwaDriver.isProcessBound) {
            showProcessSelectionDialog()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Only relayout when the orientation truly changes to avoid unnecessary rebuilds
        if (currentOrientation != newConfig.orientation) {
            currentOrientation = newConfig.orientation
            adjustLayoutForOrientation(newConfig.orientation)
        }
    }

    private fun adjustLayoutForOrientation(orientation: Int) {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE

        // Toggle visibility between the top toolbar and the sidebar
        fullscreenBinding.toolbarContainer.visibility = if (isLandscape) View.GONE else View.VISIBLE
        fullscreenBinding.sidebarContainer.visibility = if (isLandscape) View.VISIBLE else View.GONE
        // In landscape mode, show the top-right close button and hide the sidebar close button
        fullscreenBinding.btnCloseLandscape.visibility = if (isLandscape) View.VISIBLE else View.GONE
        fullscreenBinding.sidebarBtnClose.visibility = if (isLandscape) View.GONE else View.VISIBLE

        // Update constraints for the content area
        val contentContainer = fullscreenBinding.contentContainer
        val layoutParams =
            contentContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams

        if (isLandscape) {
            // Landscape: anchor the content area to the right of the sidebar
            layoutParams.topToBottom =
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            layoutParams.topToTop =
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            layoutParams.startToEnd = R.id.sidebar_container
            layoutParams.startToStart =
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        } else {
            // Portrait: anchor the content area below the top toolbar
            layoutParams.topToBottom = R.id.toolbar_container
            layoutParams.topToTop =
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            layoutParams.startToEnd =
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            layoutParams.startToStart =
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        }

        contentContainer.layoutParams = layoutParams

        // Update constraints for the bottom info bar
        val bottomInfoBar = fullscreenBinding.bottomInfoBar
        val bottomLayoutParams =
            bottomInfoBar.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams

        if (isLandscape) {
            bottomLayoutParams.startToEnd = R.id.sidebar_container
            bottomLayoutParams.startToStart =
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        } else {
            bottomLayoutParams.startToEnd =
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            bottomLayoutParams.startToStart =
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        }

        bottomInfoBar.layoutParams = bottomLayoutParams

        // TabLayout manages indicator state automatically, so no manual sync is needed

        if (::searchController.isInitialized) {
            searchController.adjustLayoutForOrientation(orientation)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.mipmap.ic_launcher).setContentIntent(pendingIntent).setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    }

    override fun onDestroy() {
        super.onDestroy()

        notification.destroy()
        windowManager.removeView(floatingIconView)
        windowManager.removeView(fullscreenView)

        if (WuwaDriver.isProcessBound) {
            WuwaDriver.unbindProcess()
            ProcessDeathMonitor.stop()
        }

        // Clean up controllers
        searchController.cleanup()
        settingsController.cleanup()
        savedAddressController.cleanup()
        memoryPreviewController.cleanup()
        breakpointController.cleanup()

        // Cancel coroutines
        coroutineScope.cancel()

        // Notify listeners that the overlay has closed
        FloatingWindowStateManager.setActive(false)
    }

    private fun updateTopIcon(process: DisplayProcessInfo?) {
        val fullscreenIconView = fullscreenBinding.attachedAppIcon
        val sidebarIconView = fullscreenBinding.sidebarAppIcon
        val floatingIconView = floatingIconBinding.appIcon

        if (process == null) {
            fullscreenIconView.setImageResource(R.mipmap.ic_launcher)
            sidebarIconView.setImageResource(R.mipmap.ic_launcher)
            floatingIconView.setImageResource(R.mipmap.ic_launcher)
            return
        }

        // Reuse the icon already resolved in DisplayProcessInfo
        fullscreenIconView.setImageDrawable(process.icon)
        sidebarIconView.setImageDrawable(process.icon)
        floatingIconView.setImageDrawable(process.icon)
    }

    private fun updateBottomInfoBar() {
        val mmkv = MMKV.defaultMMKV()
        val selectedRanges = mmkv.selectedMemoryRanges
        val tvSelectedMemoryRanges = fullscreenBinding.tvSelectedMemoryRanges

        if (selectedRanges.isEmpty()) {
            tvSelectedMemoryRanges.text = getString(R.string.memory_range_unselected)
            return
        }

        val sortedRanges = selectedRanges.sortedBy { it.ordinal }
        val rangeText = sortedRanges.joinToString(",") { it.code }
        val spannable = SpannableString(rangeText)

        var start = 0
        sortedRanges.forEach { range ->
            val end = start + range.code.length
            spannable.setSpan(
                ForegroundColorSpan(range.color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            start = end + 1
        }

        tvSelectedMemoryRanges.text = spannable
    }

    /**
     * Update the selected-address count in the bottom bar
     */
    @SuppressLint("SetTextI18n")
    private fun updateSelectedCount(count: Int) {
        fullscreenBinding.tvSelectedCount.text = "[$count]"
    }

    override fun onProcessDied(pid: Int) {
        handleProcessDied(pid)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}