package moe.fuqiuluo.mamu.floating.controller

import android.content.ClipboardManager
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.data.settings.memoryDisplayFormats
import moe.fuqiuluo.mamu.data.settings.memoryPreviewInfiniteScroll
import moe.fuqiuluo.mamu.data.settings.memoryRegionCacheInterval
import moe.fuqiuluo.mamu.databinding.FloatingMemoryPreviewLayoutBinding
import moe.fuqiuluo.mamu.driver.LocalMemoryOps
import moe.fuqiuluo.mamu.driver.FreezeManager
import moe.fuqiuluo.mamu.driver.SearchEngine
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.adapter.InfiniteMemoryAdapter
import moe.fuqiuluo.mamu.floating.data.local.MemoryBackupManager
import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import moe.fuqiuluo.mamu.floating.data.model.MemoryDisplayFormat
import moe.fuqiuluo.mamu.floating.data.model.MemoryPreviewItem
import moe.fuqiuluo.mamu.floating.data.model.MemoryRange
import moe.fuqiuluo.mamu.floating.data.model.SavedAddress
import moe.fuqiuluo.mamu.floating.dialog.AddressActionDialog
import moe.fuqiuluo.mamu.floating.dialog.AddressActionSource
import moe.fuqiuluo.mamu.floating.dialog.BatchModifyValueDialog
import moe.fuqiuluo.mamu.floating.dialog.ModifyValueDialog
import moe.fuqiuluo.mamu.floating.dialog.ModuleListDialog
import moe.fuqiuluo.mamu.floating.dialog.ExportMemoryDialog
import moe.fuqiuluo.mamu.floating.event.AddressValueChangedEvent
import moe.fuqiuluo.mamu.floating.event.FloatingEventBus
import moe.fuqiuluo.mamu.floating.event.SaveAndFreezeEvent
import moe.fuqiuluo.mamu.floating.event.SaveMemoryPreviewEvent
import moe.fuqiuluo.mamu.floating.event.SearchResultsUpdatedEvent
import moe.fuqiuluo.mamu.floating.event.UIActionEvent
import moe.fuqiuluo.mamu.floating.ext.divideToSimpleMemoryRange
import moe.fuqiuluo.mamu.floating.ext.divideToSimpleMemoryRangeParallel
import moe.fuqiuluo.mamu.utils.ValueTypeUtils
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import moe.fuqiuluo.mamu.widget.ToolbarAction
import moe.fuqiuluo.mamu.widget.multiChoiceDialog
import moe.fuqiuluo.mamu.widget.simpleSingleChoiceDialog

class MemoryPreviewController(
    context: Context,
    binding: FloatingMemoryPreviewLayoutBinding,
    notification: NotificationOverlay
) : FloatingController<FloatingMemoryPreviewLayoutBinding>(context, binding, notification) {

    companion object {
        private val PAGE_SIZE = LocalMemoryOps.getPageSize()
        private const val TAG = "MemoryPreviewCtrl"
        private const val MEMORY_ROW_POOL_SIZE = 32
        private const val MAX_NAVIGATION_HISTORY = 100
        private const val DEFAULT_PAGE_COUNT = 3  // 默认显示3页
    }

    private lateinit var currentFormats: MutableList<MemoryDisplayFormat>
    private var currentStartAddress: Long = 0L
    private var targetAddress: Long? = null

    private val navigationHistory = mutableListOf<Long>()
    private var navigationIndex = -1
    private var isNavigating = false

    private var memoryRegions: List<DisplayMemRegionEntry> = emptyList()
    private var memoryRegionsCacheTime: Long = 0L

    private val mmkv by lazy { MMKV.defaultMMKV() }

    private lateinit var adapter: InfiniteMemoryAdapter

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun initialize() {
        currentFormats = mmkv.memoryDisplayFormats.toMutableList()

        setupAdapter()
        setupToolbar()
        setupRecyclerView()
        setupStatusBar()
        updateFormatDisplay()
        updateInfiniteScrollMode()

        subscribeToNavigateEvents()
        loadInitialPlaceholderPage()
    }

    private fun updateInfiniteScrollMode() {
        val infiniteScrollEnabled = mmkv.memoryPreviewInfiniteScroll
        adapter.setInfiniteScrollEnabled(infiniteScrollEnabled)
    }

    private fun setupAdapter() {
        adapter = InfiniteMemoryAdapter(
            onRowClick = { memoryRow -> showModifyValueDialog(memoryRow) },
            onRowLongClick = { memoryRow ->
                showAddressActionDialog(memoryRow)
                true
            },
            onSelectionChanged = { selectedCount ->
                // 更新底部栏选中数量
                FloatingEventBus.tryEmitUIAction(UIActionEvent.UpdateSelectedCount(selectedCount))
            },
            onDataRequest = { pageAlignedAddress, callback ->
                requestPageData(pageAlignedAddress, callback)
            },
            onBoundaryReached = { isTop ->
                handleBoundaryReached(isTop)
            },
            onNavigationClick = { targetAddress, isNext ->
                jumpToAddressForNavigation(targetAddress, isNext)
            }
        )
        adapter.setFormats(currentFormats)
    }

    /**
     * 处理边界To达事件
     * @param isTop true 表示To达顶部边界，false 表示To达底部边界
     */
    private fun handleBoundaryReached(isTop: Boolean) {
        if (isTop) {
            // 向上扩展，需要保持滚动位置
            val layoutManager = binding.memoryPreviewRecyclerView.layoutManager as? LinearLayoutManager
            val firstVisiblePosition = layoutManager?.findFirstVisibleItemPosition() ?: 0
            val firstVisibleView = layoutManager?.findViewByPosition(firstVisiblePosition)
            val offset = firstVisibleView?.top ?: 0
            
            val expanded = adapter.expandTop(1)
            if (expanded) {
                // 扩展后调整滚动位置，保持视觉上的连续性
                val rowsPerPage = PAGE_SIZE / adapter.getAlignment()
                val newPosition = firstVisiblePosition + rowsPerPage
                layoutManager?.scrollToPositionWithOffset(newPosition, offset)
            }
        } else {
            // 向下扩展，不需要调整滚动位置
            adapter.expandBottom(1)
        }
    }

    /**
     * 请求页面数据（页面对齐的Address）
     * 关键：确保 native Functions读取时Address是页面对齐的
     */
    private fun requestPageData(pageAlignedAddress: Long, callback: (ByteArray?) -> Unit) {
        if (!WuwaDriver.isProcessBound) {
            callback(null)
            return
        }

        // 验证Address是否页面对齐
        if (pageAlignedAddress % PAGE_SIZE != 0L) {
            Log.e(TAG, "Address未对齐To页面边界: 0x${pageAlignedAddress.toString(16)}")
            callback(null)
            return
        }

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val data = WuwaDriver.readMemory(pageAlignedAddress, PAGE_SIZE)
                withContext(Dispatchers.Main) {
                    callback(data)
                }
            } catch (e: Exception) {
                Log.e(TAG, "读取MemoryFailed: 0x${pageAlignedAddress.toString(16)}", e)
                withContext(Dispatchers.Main) {
                    callback(null)
                }
            }
        }
    }

    private fun loadInitialPlaceholderPage() {
        currentStartAddress = 0L
        targetAddress = null
        
        updateInfiniteScrollMode()
        
        val infiniteScrollEnabled = mmkv.memoryPreviewInfiniteScroll
        val defaultRows = if (infiniteScrollEnabled) {
            (DEFAULT_PAGE_COUNT * PAGE_SIZE) / adapter.getAlignment()
        } else {
            PAGE_SIZE / adapter.getAlignment()
        }
        
        adapter.setAddressRange(0L, defaultRows)
        updateEmptyState()
    }

    private fun subscribeToNavigateEvents() {
        coroutineScope.launch {
            FloatingEventBus.navigateToMemoryAddressEvents.collect { event ->
                jumpToAddress(event.address)
            }
        }
    }

    private fun setupToolbar() {
        val toolbar = binding.previewToolbar

        val actions = listOf(
            ToolbarAction(1, R.drawable.calculate_24px, "Offset Calculator") { showOffsetCalculator() },
            ToolbarAction(200, R.drawable.icon_arrow_left_alt_24px, "Back") { navigateBack() },
            ToolbarAction(201, R.drawable.icon_arrow_right_alt_24px, "Forward") { navigateForward() },
            ToolbarAction(100, R.drawable.baseline_forward_24, "Go to") { showModuleListDialog() },
            ToolbarAction(10, R.drawable.icon_save_24px, "Export Memory") { showExportMemoryDialog() },
            ToolbarAction(2, R.drawable.icon_save_24px, "Save") { saveSelectedToAddresses() },
            ToolbarAction(3, R.drawable.icon_edit_24px, "Edit") { showBatchModifyDialog() },
            ToolbarAction(4, R.drawable.flip_to_front_24px, "Cross-select") { crossSelectBetween() },
            ToolbarAction(5, R.drawable.search_check_24px, "Use as Search Results") { setSelectedAsSearchResults() },
            ToolbarAction(6, R.drawable.deselect_24px, "Clear Selection") { adapter.clearSelection() },
            ToolbarAction(7, R.drawable.compare_arrows_24px, "Calculate Offset XOR") { calculateOffsetXor() },
            ToolbarAction(8, R.drawable.select_all_24px, "Select All") { adapter.selectAll() },
            ToolbarAction(9, R.drawable.flip_to_front_24px, "Invert Selection") { adapter.invertSelection() },
        )

        toolbar.setActions(actions)
        val options = actions.map { it.label }.toTypedArray()
        val icons = actions.map { it.icon }.toTypedArray()
        toolbar.setOverflowCallback {
            context.simpleSingleChoiceDialog(
                showTitle = false,
                options = options,
                icons = icons,
                showRadioButton = false,
                onSingleChoice = { which ->
                    if (which < actions.size) actions[which].onClick.invoke()
                }
            )
        }
    }

    private fun setupRecyclerView() {
        val viewPool = RecyclerView.RecycledViewPool().apply {
            setMaxRecycledViews(0, MEMORY_ROW_POOL_SIZE)
        }

        binding.memoryPreviewRecyclerView.apply {
            visibility = View.VISIBLE
            layoutManager = LinearLayoutManager(context)
            adapter = this@MemoryPreviewController.adapter
            setHasFixedSize(true)
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            setRecycledViewPool(viewPool)
            (layoutManager as? LinearLayoutManager)?.initialPrefetchItemCount = 20
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
            
            // 添加滚动监听器，检测边界并触发扩展
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val firstVisible = lm.findFirstVisibleItemPosition()
                    val lastVisible = lm.findLastVisibleItemPosition()
                    if (firstVisible != RecyclerView.NO_POSITION && lastVisible != RecyclerView.NO_POSITION) {
                        this@MemoryPreviewController.adapter.checkBoundary(firstVisible, lastVisible)
                    }
                }
            })
        }

        binding.fastScroller.attachToRecyclerView(binding.memoryPreviewRecyclerView)
        preCreateViewHolders(viewPool)
    }

    private fun preCreateViewHolders(viewPool: RecyclerView.RecycledViewPool) {
        coroutineScope.launch(Dispatchers.Default) {
            try {
                withContext(Dispatchers.Main) {
                    repeat(MEMORY_ROW_POOL_SIZE) {
                        val holder = adapter.createViewHolder(binding.memoryPreviewRecyclerView, 0)
                        viewPool.putRecycledView(holder)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "预创建 ViewHolder Failed: ${e.message}")
            }
        }
    }

    private fun setupStatusBar() {
        binding.filterStatusText.setOnClickListener {
            notification.showWarning("Filter support is coming soon")
        }
        binding.formatDisplayText.setOnClickListener { showFormatSettingsDialog() }
        binding.refreshButton.setOnClickListener { refreshCurrentView() }
    }

    private fun showFormatSettingsDialog() {
        val allFormats = MemoryDisplayFormat.getAllFormatsSortedByPriority().toTypedArray()
        val formatNames = allFormats.map { it.code + ": " + it.displayName }.toTypedArray()
        val formatColors = allFormats.map { it.textColor }.toIntArray()
        val checkedItems = BooleanArray(allFormats.size) { currentFormats.contains(allFormats[it]) }

        context.multiChoiceDialog(
            title = "Choose value display formats",
            options = formatNames,
            checkedItems = checkedItems,
            itemColors = formatColors,
            onMultiChoice = { newCheckedItems ->
                currentFormats.clear()
                newCheckedItems.forEachIndexed { index, isChecked ->
                    if (isChecked) currentFormats.add(allFormats[index])
                }
                if (currentFormats.isEmpty()) {
                    currentFormats.add(MemoryDisplayFormat.DWORD)
                    notification.showWarning("Select at least one display format")
                }
                mmkv.memoryDisplayFormats = currentFormats
                updateFormatDisplay()
                
                // 保存当前可见的Address，以便在格式改变后Restore滚动位置
                val layoutManager = binding.memoryPreviewRecyclerView.layoutManager as? LinearLayoutManager
                val firstVisiblePosition = layoutManager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
                val firstVisibleView = layoutManager?.findViewByPosition(firstVisiblePosition)
                val topOffset = firstVisibleView?.top ?: 0
                
                // 计算当前可见行对应的实际MemoryAddress
                val currentVisibleAddress = if (firstVisiblePosition != RecyclerView.NO_POSITION && firstVisiblePosition < adapter.getTotalRows()) {
                    adapter.rowToAddress(firstVisiblePosition)
                } else null
                
                // 更新格式，这会改变 alignment
                adapter.setFormats(currentFormats)
                refreshCurrentView()
                
                // 根据相同的MemoryAddress重新计算行号并滚动
                if (currentVisibleAddress != null) {
                    val newRow = adapter.addressToRow(currentVisibleAddress)
                    val newTotalRows = adapter.getTotalRows()
                    // 虽然 totalRows 在 setFormats 中不变，但新的行号可能因对齐变化而越界
                    if (newRow >= 0 && newRow < newTotalRows) {
                        // 保持相同的顶部Offset以提供更平滑的体验
                        layoutManager?.scrollToPositionWithOffset(newRow, topOffset)
                    }
                }
            }
        )
    }

    private fun updateFormatDisplay() {
        val spanBuilder = SpannableStringBuilder()
        currentFormats.forEachIndexed { index, format ->
            if (index > 0) spanBuilder.append(",")
            val start = spanBuilder.length
            spanBuilder.append(format.code)
            spanBuilder.setSpan(
                ForegroundColorSpan(format.textColor), start, spanBuilder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        binding.formatDisplayText.text = spanBuilder
    }

    private fun jumpToAddress(requestedAddress: Long) {
        if (!WuwaDriver.isProcessBound) {
            notification.showError("No process bound")
            return
        }

        val pageStartAddress = (requestedAddress / PAGE_SIZE) * PAGE_SIZE
        val isPageAligned = requestedAddress == pageStartAddress

        if (!isNavigating) {
            val currentHistoryAddress = if (navigationIndex >= 0 && navigationIndex < navigationHistory.size) {
                navigationHistory[navigationIndex]
            } else -1L
            if (requestedAddress != currentHistoryAddress) {
                addToNavigationHistory(requestedAddress)
            }
        }

        currentStartAddress = pageStartAddress
        targetAddress = requestedAddress

        updateMemoryRegionsCache()
        
        // 更新无限滚动模式设置
        updateInfiniteScrollMode()
        
        val infiniteScrollEnabled = mmkv.memoryPreviewInfiniteScroll
        
        if (infiniteScrollEnabled) {
            // 无限滚动模式：计算三页的行数
            val defaultRows = (DEFAULT_PAGE_COUNT * PAGE_SIZE) / adapter.getAlignment()
            
            // 根据是否页面对齐决定起始Address
            val startAddress = if (isPageAligned) {
                // 页面对齐：From当前页开始，显示当前页+后两页
                pageStartAddress
            } else {
                // 非页面对齐：From前一页开始，Target address在中间
                if (pageStartAddress >= PAGE_SIZE) pageStartAddress - PAGE_SIZE else 0L
            }
            
            adapter.setAddressRange(startAddress, defaultRows)
            adapter.setHighlightAddress(requestedAddress)
            adapter.setMemoryRegions(memoryRegions)

            scrollToAddress(requestedAddress, isPageAligned)
        } else {
            // 固定页面模式：只显示一页（base address To base address + PAGE_SIZE）
            val singlePageRows = PAGE_SIZE / adapter.getAlignment()
            
            adapter.setAddressRange(pageStartAddress, singlePageRows)
            adapter.setHighlightAddress(requestedAddress)
            adapter.setMemoryRegions(memoryRegions)
            
            // 滚动ToTarget address在页面内的位置
            scrollToAddress(requestedAddress, false)
        }
        
        notification.showSuccess("Jumped to address: ${String.format("%X", requestedAddress)}")
    }

    /**
     * 专门用于Previous page/Next page导航的跳转方法
     * @param targetAddress 目标页面的起始Address
     * @param isNext true 表示Next page（滚动To顶部），false 表示Previous page（滚动To底部）
     */
    private fun jumpToAddressForNavigation(targetAddress: Long, isNext: Boolean) {
        if (!WuwaDriver.isProcessBound) {
            notification.showError("No process bound")
            return
        }

        val pageStartAddress = (targetAddress / PAGE_SIZE) * PAGE_SIZE

        if (!isNavigating) {
            val currentHistoryAddress = if (navigationIndex >= 0 && navigationIndex < navigationHistory.size) {
                navigationHistory[navigationIndex]
            } else -1L
            if (targetAddress != currentHistoryAddress) {
                addToNavigationHistory(targetAddress)
            }
        }

        currentStartAddress = pageStartAddress
        this.targetAddress = targetAddress

        updateMemoryRegionsCache()
        updateInfiniteScrollMode()

        // 固定页面模式：只显示一页
        val singlePageRows = PAGE_SIZE / adapter.getAlignment()

        adapter.setAddressRange(pageStartAddress, singlePageRows)
        adapter.setHighlightAddress(null)  // 导航时不高亮
        adapter.setMemoryRegions(memoryRegions)

        // 根据导航方向滚动To对应位置
        val layoutManager = binding.memoryPreviewRecyclerView.layoutManager as? LinearLayoutManager
        if (layoutManager != null) {
            if (isNext) {
                // Next page：滚动To第一个Memory行（位置1，因为位置0是Previous page导航）
                layoutManager.scrollToPositionWithOffset(1, 0)
            } else {
                // Previous page：滚动To最后一个Memory行（位置 = singlePageRows，因为位置0是导航，最后一个导航在 singlePageRows+1）
                layoutManager.scrollToPositionWithOffset(singlePageRows, 0)
            }
        }

        val direction = if (isNext) "Next page" else "Previous page"
        notification.showSuccess("$direction: ${String.format("%X", pageStartAddress)}")
    }

    private fun scrollToAddress(address: Long, showAtTop: Boolean = false) {
        val alignment = adapter.getAlignment()
        val baseAddress = adapter.getBaseAddress()
        val targetRow = ((address - baseAddress) / alignment).toInt()

        val layoutManager = binding.memoryPreviewRecyclerView.layoutManager as? LinearLayoutManager
        if (layoutManager != null && targetRow >= 0) {
            val offset = if (showAtTop) {
                0  // 显示在顶部
            } else {
                binding.memoryPreviewRecyclerView.height / 2  // 显示在中间
            }
            layoutManager.scrollToPositionWithOffset(targetRow, offset)
        }
    }

    private fun updateMemoryRegionsCache() {
        val cacheInterval = mmkv.memoryRegionCacheInterval.toLong()
        val now = System.currentTimeMillis()
        val cacheValid = cacheInterval > 0 && memoryRegions.isNotEmpty() &&
                (now - memoryRegionsCacheTime) < cacheInterval

        if (!cacheValid) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val regions = WuwaDriver.queryMemRegionsWithRetry()
                        .divideToSimpleMemoryRangeParallel()
                        .sortedBy { it.start }
                    withContext(Dispatchers.Main) {
                        memoryRegions = regions
                        memoryRegionsCacheTime = System.currentTimeMillis()
                        adapter.setMemoryRegions(regions)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "更新Memory区域缓存失败", e)
                }
            }
        }
    }

    private fun addToNavigationHistory(address: Long) {
        if (navigationIndex >= 0 && navigationIndex < navigationHistory.size - 1) {
            navigationHistory.subList(navigationIndex + 1, navigationHistory.size).clear()
        }
        if (navigationHistory.isEmpty() || navigationHistory.last() != address) {
            navigationHistory.add(address)
            navigationIndex = navigationHistory.size - 1
            while (navigationHistory.size > MAX_NAVIGATION_HISTORY) {
                navigationHistory.removeAt(0)
                navigationIndex--
            }
        }
    }

    private fun navigateBack() {
        if (navigationHistory.isEmpty() || navigationIndex <= 0) {
            notification.showWarning("Already at the earliest entry")
            return
        }
        isNavigating = true
        navigationIndex--
        jumpToAddress(navigationHistory[navigationIndex])
        isNavigating = false
        notification.showSuccess("Moved back (${navigationIndex + 1}/${navigationHistory.size})")
    }

    private fun navigateForward() {
        if (navigationHistory.isEmpty() || navigationIndex >= navigationHistory.size - 1) {
            notification.showWarning("Already at the latest entry")
            return
        }
        isNavigating = true
        navigationIndex++
        jumpToAddress(navigationHistory[navigationIndex])
        isNavigating = false
        notification.showSuccess("Moved forward (${navigationIndex + 1}/${navigationHistory.size})")
    }

    private fun refreshCurrentView() {
        adapter.refreshAll()
        notification.showSuccess("Refreshed")
    }

    /**
     * 静默刷新当前视图（不显示通知）
     * 用于悬浮窗打开/Close时自动刷新
     */
    fun refreshSilently() {
        adapter.refreshAll()
    }

    private fun showAddressActionDialog(memoryRow: MemoryPreviewItem.MemoryRow) {
        if (!WuwaDriver.isProcessBound) {
            notification.showError("No process bound")
            return
        }

        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val defaultType = DisplayValueType.DWORD

        coroutineScope.launch {
            val currentValue = withContext(Dispatchers.IO) {
                try {
                    val bytes = WuwaDriver.readMemory(memoryRow.address, defaultType.memorySize.toInt())
                    if (bytes != null) ValueTypeUtils.bytesToDisplayValue(bytes, defaultType) else "?"
                } catch (e: Exception) { "?" }
            }

            AddressActionDialog(
                context = context,
                notification = notification,
                clipboardManager = clipboardManager,
                address = memoryRow.address,
                value = currentValue,
                valueType = defaultType,
                coroutineScope = coroutineScope,
                callbacks = object : AddressActionDialog.Callbacks {
                    override fun onShowOffsetCalculator(address: Long) {
                        FloatingEventBus.tryEmitUIAction(
                            UIActionEvent.ShowOffsetCalculatorDialog(initialBaseAddress = address)
                        )
                    }
                    override fun onJumpToAddress(address: Long) { jumpToAddress(address) }
                    override fun onJumpToPointer(fromAddress: Long, toAddress: Long) {
                        // 确保来源Address在导航History中，以便后退时能回To长按的位置
                        val currentHistoryAddress = if (navigationIndex >= 0 && navigationIndex < navigationHistory.size) {
                            navigationHistory[navigationIndex]
                        } else -1L
                        if (fromAddress != currentHistoryAddress) {
                            addToNavigationHistory(fromAddress)
                        }
                        jumpToAddress(toAddress)
                    }
                },
                source = AddressActionSource.MEMORY_PREVIEW,
                displayFormats = currentFormats
            ).show()
        }
    }

    private fun showModifyValueDialog(memoryRow: MemoryPreviewItem.MemoryRow) {
        if (!WuwaDriver.isProcessBound) {
            notification.showError("No process bound")
            return
        }

        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val defaultType = DisplayValueType.DWORD

        coroutineScope.launch {
            val currentValue = withContext(Dispatchers.IO) {
                try {
                    val bytes = WuwaDriver.readMemory(memoryRow.address, defaultType.memorySize.toInt())
                    if (bytes != null) ValueTypeUtils.bytesToDisplayValue(bytes, defaultType) else ""
                } catch (e: Exception) { "" }
            }

            ModifyValueDialog(
                context = context,
                notification = notification,
                clipboardManager = clipboardManager,
                address = memoryRow.address,
                currentValue = currentValue,
                defaultType = defaultType,
                onConfirm = { addr, oldValue, newValue, valueType, freeze ->
                    try {
                        val dataBytes = ValueTypeUtils.parseExprToBytes(newValue, valueType)
                        MemoryBackupManager.saveBackup(addr, oldValue, valueType)
                        val success = WuwaDriver.writeMemory(addr, dataBytes)
                        if (success) {
                            coroutineScope.launch {
                                FloatingEventBus.emitAddressValueChanged(
                                    AddressValueChangedEvent(addr, newValue, valueType.nativeId,
                                        AddressValueChangedEvent.Source.MEMORY_PREVIEW)
                                )
                            }
                            
                            // 如果勾选了冻结，保存ToAddress列表并冻结
                            if (freeze) {
                                coroutineScope.launch {
                                    // 查找对应的MemoryRange
                                    val range = memoryRegions.find { region ->
                                        addr >= region.start && addr < region.end
                                    }
                                    // 发送保存并冻结事件
                                    FloatingEventBus.emitSaveAndFreeze(
                                        SaveAndFreezeEvent(
                                            address = addr,
                                            value = newValue,
                                            valueType = valueType,
                                            range = range
                                        )
                                    )
                                }
                                // 直接添加To冻结管理器
                                FreezeManager.addFrozen(addr, dataBytes, valueType.nativeId)
                            }
                            
                            val pageAddress = (addr / PAGE_SIZE) * PAGE_SIZE
                            adapter.refreshPage(pageAddress)
                            notification.showSuccess(context.getString(R.string.modify_success_message, String.format("%X", addr)))
                        } else {
                            notification.showError(context.getString(R.string.modify_failed_message, String.format("%X", addr)))
                        }
                    } catch (e: IllegalArgumentException) {
                        notification.showError(context.getString(R.string.error_invalid_value_format, e.message ?: "Unknown error"))
                    } catch (e: Exception) {
                        notification.showError(context.getString(R.string.error_modify_failed, e.message ?: "Unknown error"))
                    }
                }
            ).show()
        }
    }

    private fun showOffsetCalculator() {
        val initialBaseAddress = if (adapter.getSelectedCount() > 0) {
            adapter.getSelectedAddresses().first()
        } else currentStartAddress

        FloatingEventBus.tryEmitUIAction(UIActionEvent.ShowOffsetCalculatorDialog(initialBaseAddress))
    }

    private fun showModuleListDialog() {
        if (!WuwaDriver.isProcessBound) {
            notification.showError("No process bound")
            return
        }

        val currentPid = WuwaDriver.currentBindPid
        if (currentPid <= 0 || !WuwaDriver.isProcessAlive(currentPid)) {
            notification.showError("The target process has exited")
            return
        }

        coroutineScope.launch {
            try {
                val regions = withContext(Dispatchers.IO) { WuwaDriver.queryMemRegionsWithRetry(currentPid) }
                val modules = regions.divideToSimpleMemoryRange().sortedBy { it.start }

                if (modules.isEmpty()) {
                    notification.showWarning("No modules were found")
                    return@launch
                }

                ModuleListDialog(
                    context = context,
                    modules = modules,
                    notification = notification,
                    onModuleSelected = { selectedModule ->
                        jumpToAddress(selectedModule.start)
                        notification.showSuccess("Jumped to: ${selectedModule.name.substringAfterLast("/")}")
                    },
                    onGoto = { address -> jumpToAddress(address) }
                ).show()
            } catch (e: Exception) {
                Log.e(TAG, "加载Module List失败", e)
                notification.showError("Failed to load the module list: ${e.message ?: "Unknown error"}")
            }
        }
    }

    private fun showExportMemoryDialog() {
        if (!WuwaDriver.isProcessBound) {
            notification.showError("No process bound")
            return
        }

        val currentPid = WuwaDriver.currentBindPid
        if (currentPid <= 0 || !WuwaDriver.isProcessAlive(currentPid)) {
            notification.showError("The target process has exited")
            return
        }

        coroutineScope.launch {
            try {
                val regions = withContext(Dispatchers.IO) {
                    WuwaDriver.queryMemRegionsWithRetry(currentPid)
                        .divideToSimpleMemoryRange()
                        .sortedBy { it.start }
                }

                val startAddress = targetAddress ?: currentStartAddress
                val endAddress = startAddress + PAGE_SIZE - 1

                ExportMemoryDialog(
                    context = context,
                    notification = notification,
                    coroutineScope = coroutineScope,
                    memoryRegions = regions,
                    defaultStartAddress = startAddress,
                    defaultEndAddress = endAddress
                ).show()
            } catch (e: Exception) {
                Log.e(TAG, "加载Memory段失败", e)
                notification.showError("Failed to load memory ranges: ${e.message ?: "Unknown error"}")
            }
        }
    }
    private fun saveSelectedToAddresses() {
        val selectedAddresses = adapter.getSelectedAddresses()
        if (selectedAddresses.isEmpty()) {
            notification.showWarning("No items selected")
            return
        }

        val selectedRows = adapter.getSelectedRows()
        
        // 筛选出可保存的类型
        val savableFormats = MemoryDisplayFormat.filterSavableFormats(currentFormats)
        
        if (savableFormats.isEmpty()) {
            notification.showWarning("No savable types are available in the current display format")
            return
        }
        
        if (savableFormats.size == 1) {
            // 只有一种可保存类型，直接保存
            val valueType = savableFormats.first().toDisplayValueType() ?: DisplayValueType.DWORD
            coroutineScope.launch {
                FloatingEventBus.emitSaveMemoryPreview(SaveMemoryPreviewEvent(selectedRows, memoryRegions, valueType))
                notification.showSuccess("Saved ${selectedRows.size} addresses (${valueType.code})")
            }
        } else {
            // 多种可保存类型，弹窗选择
            val formatNames = savableFormats.map { "${it.code}: ${it.displayName}" }.toTypedArray()
            val formatColors = savableFormats.map { it.textColor }.toTypedArray()
            
            context.simpleSingleChoiceDialog(
                title = "Choose a value type to save",
                options = formatNames,
                textColors = formatColors,
                showRadioButton = false,
                onSingleChoice = { which ->
                    val selectedFormat = savableFormats[which]
                    val valueType = selectedFormat.toDisplayValueType() ?: DisplayValueType.DWORD
                    coroutineScope.launch {
                        FloatingEventBus.emitSaveMemoryPreview(SaveMemoryPreviewEvent(selectedRows, memoryRegions, valueType))
                        notification.showSuccess("Saved ${selectedRows.size} addresses (${valueType.code})")
                    }
                }
            )
        }
    }

    private fun showBatchModifyDialog() {
        val selectedAddresses = adapter.getSelectedAddresses()
        if (selectedAddresses.isEmpty()) {
            notification.showWarning("No items selected")
            return
        }
        if (!WuwaDriver.isProcessBound) {
            notification.showError("No process bound")
            return
        }

        val selectedRows = adapter.getSelectedRows()
        val tempAddresses = selectedRows.map { row ->
            SavedAddress(row.address, "0x${row.address.toString(16).uppercase()}",
                DisplayValueType.DWORD.nativeId, "", false, row.memoryRange ?: MemoryRange.O)
        }

        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        BatchModifyValueDialog(
            context = context,
            clipboardManager = clipboardManager,
            notification = notification,
            savedAddresses = tempAddresses,
            onConfirm = { items, newValue, valueType, freeze ->
                batchModifyMemoryValues(items.map { it.address }, newValue, valueType, freeze)
            }
        ).show()
    }

    private fun batchModifyMemoryValues(addresses: List<Long>, newValue: String, valueType: DisplayValueType, freeze: Boolean) {
        coroutineScope.launch {
            try {
                val dataBytes = ValueTypeUtils.parseExprToBytes(newValue, valueType)
                val results = withContext(Dispatchers.IO) {
                    WuwaDriver.batchWriteMemory(addresses.toLongArray(), Array(addresses.size) { dataBytes })
                }
                var successCount = 0
                var failureCount = 0
                
                results.forEachIndexed { index, success ->
                    if (success) {
                        val address = addresses[index]
                        
                        // 如果勾选了冻结，添加To冻结管理器并保存ToAddress列表
                        if (freeze) {
                            FreezeManager.addFrozen(address, dataBytes, valueType.nativeId)
                            
                            // 查找对应的MemoryRange
                            val row = adapter.getSelectedRows().find { it.address == address }
                            val memRange = row?.memoryRange ?: MemoryRange.O
                            val range = DisplayMemRegionEntry(
                                start = address,
                                end = address + valueType.memorySize,
                                type = 0x03, // readable + writable
                                name = "",
                                range = memRange
                            )
                            
                            // 发送保存并冻结事件
                            FloatingEventBus.emitSaveAndFreeze(
                                SaveAndFreezeEvent(
                                    address = address,
                                    value = newValue,
                                    valueType = valueType,
                                    range = range
                                )
                            )
                        }
                        successCount++
                    } else {
                        failureCount++
                    }
                }
                
                if (failureCount == 0) {
                    val freezeMsg = if (freeze) " and froze" else ""
                    notification.showSuccess("Updated$freezeMsg $successCount addresses")
                    adapter.refreshAll()
                } else {
                    notification.showWarning("Success: $successCount, Failed: $failureCount")
                }
            } catch (e: IllegalArgumentException) {
                notification.showError("Invalid value format: ${e.message}")
            } catch (e: Exception) {
                notification.showError("Batch update failed: ${e.message}")
            }
        }
    }

    private fun crossSelectBetween() {
        val selectedAddresses = adapter.getSelectedAddresses()
        if (selectedAddresses.size < 2) {
            notification.showWarning("Select at least 2 items to use cross-select")
            return
        }

        val minAddress = selectedAddresses.minOrNull() ?: return
        val maxAddress = selectedAddresses.maxOrNull() ?: return
        val alignment = adapter.getAlignment()
        val addressesToSelect = mutableListOf<Long>()
        var addr = minAddress
        while (addr <= maxAddress) {
            addressesToSelect.add(addr)
            addr += alignment
        }
        adapter.selectAddresses(addressesToSelect)
        notification.showSuccess("Cross-selected ${addressesToSelect.size} addresses")
    }

    private fun setSelectedAsSearchResults() {
        val selectedAddresses = adapter.getSelectedAddresses()
        if (selectedAddresses.isEmpty()) {
            notification.showWarning("No items selected")
            return
        }

        val selectedRows = adapter.getSelectedRows()
        coroutineScope.launch {
            val types = Array(selectedRows.size) { DisplayValueType.DWORD }
            val success = withContext(Dispatchers.IO) {
                SearchEngine.addResultsFromAddresses(selectedRows.map { it.address }, types)
            }
            if (success) {
                val totalCount = SearchEngine.getTotalResultCount()
                notification.showSuccess("Set ${selectedRows.size} addresses as search results")
                val ranges = selectedRows.map { row ->
                    DisplayMemRegionEntry(row.address, row.address + DisplayValueType.DWORD.memorySize,
                        0x03, row.memoryRange?.displayName ?: "Unknown", row.memoryRange ?: MemoryRange.O)
                }
                FloatingEventBus.emitSearchResultsUpdated(SearchResultsUpdatedEvent(totalCount, ranges))
            } else {
                notification.showError("Failed to set search results")
            }
        }
    }

    private fun calculateOffsetXor() {
        val selectedAddresses = adapter.getSelectedAddresses()
        if (selectedAddresses.size < 2) {
            notification.showWarning("Please select at least 2 addresses")
            return
        }

        val selectedRows = adapter.getSelectedRows()
        val tempAddresses = selectedRows.map { row ->
            SavedAddress(row.address, "0x${row.address.toString(16).uppercase()}",
                DisplayValueType.DWORD.nativeId, "", false, row.memoryRange ?: MemoryRange.O)
        }
        FloatingEventBus.tryEmitUIAction(UIActionEvent.ShowOffsetXorDialog(tempAddresses))
    }

    private fun updateEmptyState() {
        val hasData = adapter.itemCount > 0
        binding.emptyStateView.visibility = if (hasData) View.GONE else View.VISIBLE
        binding.memoryPreviewRecyclerView.visibility = if (hasData) View.VISIBLE else View.GONE
    }

    override fun cleanup() {
        super.cleanup()
        binding.fastScroller.detachFromRecyclerView()
        coroutineScope.cancel()
    }
}
