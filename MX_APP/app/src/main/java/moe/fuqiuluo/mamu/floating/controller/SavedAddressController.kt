package moe.fuqiuluo.mamu.floating.controller

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.data.settings.saveListUpdateInterval
import moe.fuqiuluo.mamu.databinding.FloatingSavedAddressesLayoutBinding
import moe.fuqiuluo.mamu.driver.ExactSearchResultItem
import moe.fuqiuluo.mamu.driver.FreezeManager
import moe.fuqiuluo.mamu.driver.FuzzySearchResultItem
import moe.fuqiuluo.mamu.driver.SearchEngine
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.adapter.SavedAddressAdapter
import moe.fuqiuluo.mamu.floating.data.local.MemoryBackupManager
import moe.fuqiuluo.mamu.floating.data.local.SavedAddressRepository
import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry
import moe.fuqiuluo.mamu.floating.data.model.DisplayProcessInfo
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import moe.fuqiuluo.mamu.floating.data.model.MemoryRange
import moe.fuqiuluo.mamu.floating.data.model.SavedAddress
import moe.fuqiuluo.mamu.floating.dialog.AddressActionDialog
import moe.fuqiuluo.mamu.floating.dialog.AddressActionSource
import moe.fuqiuluo.mamu.floating.dialog.BatchModifyValueDialog
import moe.fuqiuluo.mamu.floating.dialog.ExportAddressDialog
import moe.fuqiuluo.mamu.floating.dialog.ImportAddressDialog
import moe.fuqiuluo.mamu.floating.dialog.ModifyValueDialog
import moe.fuqiuluo.mamu.floating.dialog.OffsetXorDialog
import moe.fuqiuluo.mamu.floating.dialog.RemoveOptionsDialog
import moe.fuqiuluo.mamu.floating.event.AddressValueChangedEvent
import moe.fuqiuluo.mamu.floating.event.BatchAddressValueChangedEvent
import moe.fuqiuluo.mamu.floating.event.FloatingEventBus
import moe.fuqiuluo.mamu.floating.event.ProcessStateEvent
import moe.fuqiuluo.mamu.floating.event.SaveAndFreezeEvent
import moe.fuqiuluo.mamu.floating.event.SearchResultsUpdatedEvent
import moe.fuqiuluo.mamu.floating.event.UIActionEvent
import moe.fuqiuluo.mamu.utils.ByteFormatUtils.formatBytes
import moe.fuqiuluo.mamu.utils.ValueTypeUtils
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import moe.fuqiuluo.mamu.widget.RealtimeMonitorOverlay
import moe.fuqiuluo.mamu.widget.ToolbarAction
import moe.fuqiuluo.mamu.widget.simpleSingleChoiceDialog

class SavedAddressController(
    context: Context,
    binding: FloatingSavedAddressesLayoutBinding,
    notification: NotificationOverlay
) : FloatingController<FloatingSavedAddressesLayoutBinding>(context, binding, notification) {
    // 保存的Address列表（Memory中）
    private val savedAddresses = mutableListOf<SavedAddress>()

    // Address数量 badge views (支持多个，用于顶部工具栏和侧边栏)
    private val addressCountBadgeViews = mutableListOf<TextView>()

    // 列表适配器
    private val adapter: SavedAddressAdapter = SavedAddressAdapter(
        onItemClick = { address, position ->
            showModifyValueDialog(address)
        },
        onItemLongClick = { address, position ->
            showAddressActionDialog(address)
            true
        },
        onFreezeToggle = { address, isFrozen ->
            handleFreezeToggle(address, isFrozen)
        },
        onItemDelete = { address ->
            deleteAddress(address.address)
        },
        onSelectionChanged = { selectedCount ->
            // 更新底部栏选中数量
            FloatingEventBus.tryEmitUIAction(UIActionEvent.UpdateSelectedCount(selectedCount))
        }
    )

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 自动更新协程任务
    private var autoUpdateJob: Job? = null

    override fun initialize() {
        setupToolbar()
        setupRecyclerView()
        setupRefreshButton()
        updateProcessDisplay(null)
        updateEmptyState()
        updateAddressCountBadge()
        updateSavedCountText()

        subscribeToAddressEvents()
        subscribeToProcessStateEvents()
        subscribeToSaveSearchResultsEvents()
        subscribeToSaveMemoryPreviewEvents()
        subscribeToSaveAndFreezeEvents()
    }

    /**
     * 订阅Address值变更事件
     * 当Search结果界面修改值时，同步更新保存Address界面的显示
     */
    private fun subscribeToAddressEvents() {
        // 订阅单个Address变更事件
        coroutineScope.launch {
            FloatingEventBus.addressValueChangedEvents
                .filter { it.source != AddressValueChangedEvent.Source.SAVED_ADDRESS }
                .collect { event ->
                    updateAddressValueByAddress(event.address, event.newValue)
                }
        }

        // 订阅批量Address变更事件
        coroutineScope.launch {
            FloatingEventBus.batchAddressValueChangedEvents
                .filter { it.source != AddressValueChangedEvent.Source.SAVED_ADDRESS }
                .collect { event ->
                    event.changes.forEach { change ->
                        updateAddressValueByAddress(change.address, change.newValue)
                    }
                }
        }
    }

    /**
     * 订阅进程状态变更事件
     */
    private fun subscribeToProcessStateEvents() {
        coroutineScope.launch {
            FloatingEventBus.processStateEvents.collect { event ->
                when (event.type) {
                    ProcessStateEvent.Type.BOUND -> {
                        // 绑定新进程时，先停止旧的更新，清空Address，再启动新的更新
                        stopAutoUpdate()
                        clearAll()
                        updateProcessDisplay(event.process)
                        startAutoUpdate()
                    }

                    ProcessStateEvent.Type.UNBOUND,
                    ProcessStateEvent.Type.DIED -> {
                        // 进程解绑或死亡时，立即停止更新并清空
                        stopAutoUpdate()
                        clearAll()
                        updateProcessDisplay(null)
                    }
                }
            }
        }
    }

    /**
     * 订阅保存Search结果事件
     */
    private fun subscribeToSaveSearchResultsEvents() {
        coroutineScope.launch {
            FloatingEventBus.saveSearchResultsEvents.collect { event ->
                // 将Search结果转换为 SavedAddress 并保存
                val savedAddresses = event.selectedItems.mapNotNull { item ->
                    when (item) {
                        is ExactSearchResultItem -> {
                            // 查找对应的MemoryRange
                            val range = event.ranges?.find { range ->
                                item.address >= range.start && item.address < range.end
                            }?.range ?: return@mapNotNull null

                            SavedAddress(
                                address = item.address,
                                name = "Var #${String.format("%X", item.address)}",
                                valueType = item.valueType,
                                value = item.value,
                                isFrozen = false,
                                range = range
                            )
                        }

                        is FuzzySearchResultItem -> {
                            // 查找对应的MemoryRange
                            val range = event.ranges?.find { range ->
                                item.address >= range.start && item.address < range.end
                            }?.range ?: return@mapNotNull null

                            SavedAddress(
                                address = item.address,
                                name = "Var #${String.format("%X", item.address)}",
                                valueType = item.valueType,
                                value = item.value,
                                isFrozen = false,
                                range = range
                            )
                        }

                        else -> null
                    }
                }
                saveAddresses(savedAddresses)
            }
        }
    }

    /**
     * 订阅保存Memory预览事件
     */
    private fun subscribeToSaveMemoryPreviewEvents() {
        coroutineScope.launch {
            FloatingEventBus.saveMemoryPreviewEvents.collect { event ->
                // 对ranges进行排序（如果存在），以便使用二分查找
                val sortedRanges = event.ranges?.sortedBy { it.start }

                // 转换MemoryRow为SavedAddress，使用事件中指定的类型
                val addresses = event.selectedItems.map { row ->
                    // 使用二分查找Fromranges中找To匹配的range
                    val range = findRangeForAddress(row.address, sortedRanges)
                        ?: row.memoryRange
                        ?: MemoryRange.O

                    SavedAddress(
                        address = row.address,
                        name = "Var #${String.format("%X", row.address)}",
                        valueType = event.valueType.nativeId,
                        value = "",
                        isFrozen = false,
                        range = range
                    )
                }

                saveAddresses(addresses)
            }
        }
    }

    /**
     * 订阅保存并冻结Address事件
     */
    private fun subscribeToSaveAndFreezeEvents() {
        coroutineScope.launch {
            FloatingEventBus.saveAndFreezeEvents.collect { event ->
                // 检查Address是否已存在
                val existingIndex = savedAddresses.indexOfFirst { it.address == event.address }
                
                if (existingIndex >= 0) {
                    // Address已存在，更新值和冻结状态
                    savedAddresses[existingIndex] = savedAddresses[existingIndex].copy(
                        value = event.value,
                        valueType = event.valueType.nativeId,
                        isFrozen = true
                    )
                    adapter.updateAddress(savedAddresses[existingIndex])
                } else {
                    // Address不存在，创建新的 SavedAddress
                    val range = event.range?.range ?: MemoryRange.O
                    val newAddress = SavedAddress(
                        address = event.address,
                        name = "Var #${String.format("%X", event.address)}",
                        valueType = event.valueType.nativeId,
                        value = event.value,
                        isFrozen = true,
                        range = range
                    )
                    savedAddresses.add(newAddress)
                    adapter.updateAddresses(savedAddresses)
                    updateEmptyState()
                    updateAddressCountBadge()
                    updateSavedCountText()
                }
                
                notification.showSuccess("Saved and froze: 0x${event.address.toString(16).uppercase()}")
            }
        }
    }

    /**
     * 使用二分查找在排序的ranges中找To包含指定address的range
     * @param address 要查找的MemoryAddress
     * @param sortedRanges 已按start排序的MemoryRange列表
     * @return 找To的MemoryRange，如果未找To返回null
     */
    private fun findRangeForAddress(
        address: Long,
        sortedRanges: List<DisplayMemRegionEntry>?
    ): MemoryRange? {
        if (sortedRanges.isNullOrEmpty()) return null

        // 使用二分查找
        val index = sortedRanges.binarySearch { range ->
            when {
                address < range.start -> 1  // address在range之前，继续向左查找
                address >= range.end -> -1  // address在range之后，继续向右查找
                else -> 0  // address在range内，找To了
            }
        }

        return if (index >= 0) sortedRanges[index].range else null
    }

    /**
     * 根据Address更新值（用于事件同步）
     */
    private fun updateAddressValueByAddress(address: Long, newValue: String) {
        val index = savedAddresses.indexOfFirst { it.address == address }
        if (index >= 0) {
            savedAddresses[index] = savedAddresses[index].copy(value = newValue)
            adapter.updateAddress(savedAddresses[index])
        }
    }

    fun setAddressCountBadgeView(vararg badges: TextView) {
        addressCountBadgeViews.clear()
        addressCountBadgeViews.addAll(badges)
        updateAddressCountBadge()
    }

    private fun updateAddressCountBadge() {
        val count = savedAddresses.size
        addressCountBadgeViews.forEach { badge ->
            if (count > 0) {
                badge.text = if (count > 99) "99+" else count.toString()
                badge.visibility = View.VISIBLE
            } else {
                badge.visibility = View.GONE
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateSavedCountText() {
        val count = savedAddresses.size
        binding.savedCountText.text = "($count)"

        // 更新顶部Tab Badge
        FloatingEventBus.tryEmitUIAction(
            UIActionEvent.UpdateSavedAddressBadge(count)
        )
    }

    private fun setupToolbar() {
        val toolbar = binding.savedToolbar

        val actions = listOf(
            ToolbarAction(
                id = 1,
                icon = R.drawable.select_all_24px,
                label = "Select All"
            ) {
                adapter.selectAll()
            },
            ToolbarAction(
                id = 2,
                icon = R.drawable.flip_to_front_24px,
                label = "Invert Selection"
            ) {
                adapter.invertSelection()
            },
            ToolbarAction(
                id = 3,
                icon = R.drawable.icon_edit_24px,
                label = "Edit Selected Values"
            ) {
                showBatchModifyDialog()
            },
            ToolbarAction(
                id = 4,
                icon = R.drawable.icon_delete_24px,
                label = "Delete"
            ) {
                showRemoveDialog()
            },
            ToolbarAction(
                id = 5,
                icon = R.drawable.icon_save_24px,
                label = "Save Addresses to File"
            ) {
                exportAddresses()
            },
            ToolbarAction(
                id = 6,
                icon = R.drawable.undo_24px,
                label = "Restore"
            ) {
                restoreSelectedValues()
            },
            ToolbarAction(
                id = 7,
                icon = R.drawable.icon_list_24px,
                label = "Load Addresses from File"
            ) {
                showLoadAddressesDialog()
            },
            ToolbarAction(
                id = 8,
                icon = R.drawable.search_check_24px,
                label = "Use as Search Results"
            ) {
                setSelectedAsSearchResults()
            },
            ToolbarAction(
                id = 9,
                icon = R.drawable.compare_arrows_24px,
                label = "Calculate Offset XOR"
            ) {
                calculateOffsetXor()
            },
            ToolbarAction(
                id = 10,
                icon = R.drawable.type_auto_24px,
                label = "Change Selected Type"
            ) {
                showChangeTypeDialog()
            },
            ToolbarAction(
                id = 11,
                icon = R.drawable.deselect_24px,
                label = "Clear Selection"
            ) {
                adapter.deselectAll()
            },
            ToolbarAction(
                id = 12,
                icon = R.drawable.calculate_24px,
                label = "Offset Calculator"
            ) {
                showOffsetCalculator()
            },
            ToolbarAction(
                id = 13,
                icon = R.drawable.icon_list_24px,
                label = "Import Selected"
            ) {
                showImportAddressDialog()
            },
            ToolbarAction(
                id = 14,
                icon = R.drawable.icon_save_24px,
                label = "Export Selected"
            ) {
                exportSelectedAddresses()
            },
            ToolbarAction(
                id = 15,
                icon = R.drawable.icon_visibility_24px,
                label = "Monitor Selected in Realtime"
            ) {
                showRealtimeMonitorForSelected()
            },
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
                    if (which < actions.size) {
                        actions[which].onClick.invoke()
                    }
                }
            )
        }
    }

    private fun setupRecyclerView() {
        binding.savedAddressesRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@SavedAddressController.adapter

            setHasFixedSize(true)
            if (itemAnimator != null && itemAnimator is SimpleItemAnimator) {
                (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            }
        }

        // 绑定快速滚动条
        binding.savedFastScroller.attachToRecyclerView(binding.savedAddressesRecyclerView)
    }

    private fun setupRefreshButton() {
        binding.refreshButton.setOnClickListener {
            refreshAddresses()
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateProcessDisplay(process: DisplayProcessInfo?) {
        process?.let {
            val memoryB = (it.rss * 1024)
            binding.processInfoText.text =
                "[${it.pid}] ${it.name} [${formatBytes(memoryB, 0)}]"
            binding.processStatusIcon.setIconResource(R.drawable.icon_pause_24px)
        } ?: run {
            binding.processInfoText.text = "No process selected"
            binding.processStatusIcon.setIconResource(R.drawable.icon_play_arrow_24px)
        }
    }

    /**
     * 保存单个Address
     */
    private fun saveAddress(address: SavedAddress) {
        val existingIndex = savedAddresses.indexOfFirst { it.address == address.address }
        if (existingIndex >= 0) {
            savedAddresses[existingIndex] = address
            adapter.updateAddress(address)
        } else {
            savedAddresses.add(address)
            adapter.addAddress(address)
        }
        updateEmptyState()
        updateAddressCountBadge()
        updateSavedCountText()
    }

    /**
     * 批量保存Address
     */
    private fun saveAddresses(addresses: List<SavedAddress>) {
        if (addresses.isEmpty()) {
            return
        }

        addresses.forEach { newAddr ->
            val existingIndex = savedAddresses.indexOfFirst { it.address == newAddr.address }
            if (existingIndex >= 0) {
                savedAddresses[existingIndex] = newAddr
            } else {
                savedAddresses.add(newAddr)
            }
        }
        adapter.updateAddresses(savedAddresses)
        updateEmptyState()
        updateAddressCountBadge()
        updateSavedCountText()

        notification.showSuccess("Saved ${addresses.size} addresses")
    }

    /**
     * DeleteAddress
     */
    private fun deleteAddress(address: Long) {
        // 如果该Address被冻结，先Cancel冻结
        FreezeManager.removeFrozen(address)
        
        savedAddresses.removeIf { it.address == address }
        adapter.setAddresses(savedAddresses)
        updateEmptyState()
        updateAddressCountBadge()
        updateSavedCountText()
        notification.showSuccess("Deleted")
    }

    /**
     * 清空所有Address（进程切换或死亡时调用）
     */
    fun clearAll() {
        // 清空所有冻结
        FreezeManager.clearAll()
        
        savedAddresses.clear()
        adapter.setAddresses(emptyList())
        updateEmptyState()
        updateAddressCountBadge()
        updateSavedCountText()
    }

    /**
     * 刷新所有Address的值（使用批量读取提高效率）
     */
    private fun refreshAddresses() {
        if (savedAddresses.isEmpty()) {
            notification.showWarning("No saved addresses")
            return
        }

        if (!WuwaDriver.isProcessBound) {
            notification.showError("No process bound")
            return
        }

        coroutineScope.launch {
            var successCount = 0
            var failCount = 0

            val addrs = mutableListOf<Long>()
            val sizes = mutableListOf<Int>()

            for (address in savedAddresses) {
                addrs.add(address.address)
                val valueType = address.displayValueType ?: DisplayValueType.DWORD
                sizes.add(valueType.memorySize.toInt())
            }

            val results = withContext(Dispatchers.IO) {
                WuwaDriver.batchReadMemory(addrs.toLongArray(), sizes.toIntArray())
            }

            // 更新 UI
            results.forEachIndexed { index, bytes ->
                val address = savedAddresses[index]
                val valueType = address.displayValueType ?: DisplayValueType.DWORD

                if (bytes != null) {
                    try {
                        val newValue = ValueTypeUtils.bytesToDisplayValue(bytes, valueType)
                        savedAddresses[index] = address.copy(value = newValue)
                        adapter.updateAddress(savedAddresses[index])
                        successCount++
                    } catch (e: Exception) {
                        failCount++
                    }
                } else {
                    failCount++
                }
            }

            if (failCount == 0) {
                notification.showSuccess("Refreshed $successCount addresses")
            } else {
                notification.showWarning("Success: $successCount, Failed: $failCount")
            }
        }
    }

    /**
     * 显示Address Actions对话框
     */
    private fun showAddressActionDialog(savedAddress: SavedAddress) {
        val clipboardManager =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val valueType = savedAddress.displayValueType ?: DisplayValueType.DWORD

        val dialog = AddressActionDialog(
            context = context,
            notification = notification,
            clipboardManager = clipboardManager,
            address = savedAddress.address,
            value = savedAddress.value,
            valueType = valueType,
            coroutineScope = coroutineScope,
            callbacks = object : AddressActionDialog.Callbacks {
                override fun onShowOffsetCalculator(address: Long) {
                    // 调用Offset Calculator，传入当前Address作为初始基址
                    FloatingEventBus.tryEmitUIAction(
                        UIActionEvent.ShowOffsetCalculatorDialog(
                            initialBaseAddress = address
                        )
                    )
                }

                override fun onJumpToAddress(address: Long) {
                    // 发送跳Go toMemory预览的事件
                    FloatingEventBus.tryEmitUIAction(
                        UIActionEvent.JumpToMemoryPreview(address)
                    )
                }
            },
            source = AddressActionSource.SAVED_ADDRESS,
            memoryRange = savedAddress.range
        )

        dialog.show()
    }

    /**
     * 显示修改单个Address值的对话框
     */
    private fun showModifyValueDialog(address: SavedAddress) {
        if (!WuwaDriver.isProcessBound) {
            notification.showError("No process bound")
            return
        }

        val clipboardManager =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val dialog = ModifyValueDialog(
            context = context,
            notification = notification,
            clipboardManager = clipboardManager,
            savedAddress = address,
            onConfirm = { addr, oldValue, newValue, valueType, freeze ->
                try {
                    val dataBytes = ValueTypeUtils.parseExprToBytes(newValue, valueType)

                    // 保存备份
                    MemoryBackupManager.saveBackup(addr, oldValue, valueType)

                    val success = WuwaDriver.writeMemory(addr, dataBytes)
                    if (success) {
                        // 更新Memory中的Address值
                        val index = savedAddresses.indexOfFirst { it.address == addr }
                        if (index >= 0) {
                            // 更新冻结状态
                            val newFrozenState = freeze || savedAddresses[index].isFrozen
                            savedAddresses[index] = savedAddresses[index].copy(
                                value = newValue,
                                isFrozen = newFrozenState
                            )
                            adapter.updateAddress(savedAddresses[index])
                            
                            // 如果需要冻结，更新冻结的值
                            if (newFrozenState) {
                                FreezeManager.addFrozen(addr, dataBytes, valueType.nativeId)
                            }
                        }

                        // 发送事件通知其他界面同步更新
                        coroutineScope.launch {
                            FloatingEventBus.emitAddressValueChanged(
                                AddressValueChangedEvent(
                                    address = addr,
                                    newValue = newValue,
                                    valueType = valueType.nativeId,
                                    source = AddressValueChangedEvent.Source.SAVED_ADDRESS
                                )
                            )
                        }

                        notification.showSuccess(
                            context.getString(
                                R.string.modify_success_message,
                                String.format("%X", addr)
                            )
                        )
                    } else {
                        notification.showError(
                            context.getString(
                                R.string.modify_failed_message,
                                String.format("%X", addr)
                            )
                        )
                    }
                } catch (e: IllegalArgumentException) {
                    notification.showError(
                        context.getString(
                            R.string.error_invalid_value_format,
                            e.message ?: "Unknown error"
                        )
                    )
                } catch (e: Exception) {
                    notification.showError(
                        context.getString(
                            R.string.error_modify_failed,
                            e.message ?: "Unknown error"
                        )
                    )
                }
            }
        )

        dialog.show()
    }

    /**
     * 显示批量修改对话框
     */
    private fun showBatchModifyDialog() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            notification.showWarning("No items selected")
            return
        }

        if (!WuwaDriver.isProcessBound) {
            notification.showError("No process bound")
            return
        }

        val clipboardManager =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val dialog = BatchModifyValueDialog(
            context = context,
            notification = notification,
            clipboardManager = clipboardManager,
            savedAddresses = selectedItems,
            onConfirm = { items, newValue, valueType, freeze ->
                batchModifyValues(items, newValue, valueType, freeze)
            }
        )

        dialog.show()
    }

    /**
     * 批量修改值（使用批量写入接口提高效率）
     */
    private fun batchModifyValues(
        items: List<SavedAddress>,
        newValue: String,
        valueType: DisplayValueType,
        freeze: Boolean
    ) {
        coroutineScope.launch {
            try {
                val dataBytes = ValueTypeUtils.parseExprToBytes(newValue, valueType)

                // 保存备份
                items.forEach { item ->
                    MemoryBackupManager.saveBackup(item.address, item.value, valueType)
                }

                // 准备批量写入参数
                val addrs = items.map { it.address }.toLongArray()
                val dataArray = Array(items.size) { dataBytes }

                // 批量写入Memory
                val results = withContext(Dispatchers.IO) {
                    WuwaDriver.batchWriteMemory(addrs, dataArray)
                }

                // 统计结果并更新 UI
                var successCount = 0
                var failureCount = 0
                val successfulChanges = mutableListOf<BatchAddressValueChangedEvent.AddressChange>()

                results.forEachIndexed { index, success ->
                    if (success) {
                        val item = items[index]
                        val addrIndex = savedAddresses.indexOfFirst { it.address == item.address }
                        if (addrIndex >= 0) {
                            // 如果勾选了冻结，或者该Address已经被冻结，则更新冻结状态
                            val newFrozenState = freeze || savedAddresses[addrIndex].isFrozen
                            savedAddresses[addrIndex] = item.copy(
                                value = newValue,
                                isFrozen = newFrozenState
                            )
                            adapter.updateAddress(savedAddresses[addrIndex])
                            
                            // 如果需要冻结，更新冻结管理器
                            if (newFrozenState) {
                                FreezeManager.addFrozen(item.address, dataBytes, valueType.nativeId)
                            }
                        }
                        successfulChanges.add(
                            BatchAddressValueChangedEvent.AddressChange(
                                address = item.address,
                                newValue = newValue,
                                valueType = valueType.nativeId
                            )
                        )
                        successCount++
                    } else {
                        failureCount++
                    }
                }

                // 发送批量事件通知其他界面同步更新
                if (successfulChanges.isNotEmpty()) {
                    FloatingEventBus.emitBatchAddressValueChanged(
                        BatchAddressValueChangedEvent(
                            changes = successfulChanges,
                            source = AddressValueChangedEvent.Source.SAVED_ADDRESS
                        )
                    )
                }

                if (failureCount == 0) {
                    val freezeMsg = if (freeze) " and froze" else ""
                    notification.showSuccess("Updated$freezeMsg $successCount addresses")
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

    /**
     * 显示Delete对话框
     */
    private fun showRemoveDialog() {
        val selectedCount = adapter.getSelectedItems().size
        val totalCount = savedAddresses.size

        if (totalCount == 0) {
            notification.showWarning("No addresses to remove")
            return
        }

        val dialog = RemoveOptionsDialog(
            context = context,
            selectedCount = selectedCount
        )

        dialog.onRemoveAll = {
            clearAll()
            notification.showSuccess("Cleared all addresses")
        }

        dialog.onRestoreAndRemove = {
            restoreAndRemoveSelected()
        }

        dialog.onRemoveSelected = {
            removeSelectedAddresses()
        }

        dialog.show()
    }

    /**
     * Restore并移除选中的Address
     */
    private fun restoreAndRemoveSelected() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            notification.showWarning("No items selected")
            return
        }

        if (!WuwaDriver.isProcessBound) {
            notification.showError("No process bound")
            return
        }

        coroutineScope.launch {
            var restoreCount = 0
            var failCount = 0
            val successfulChanges = mutableListOf<BatchAddressValueChangedEvent.AddressChange>()

            withContext(Dispatchers.IO) {
                selectedItems.forEach { item ->
                    val backup = MemoryBackupManager.getBackup(item.address)
                    if (backup != null) {
                        try {
                            val dataBytes = ValueTypeUtils.parseExprToBytes(
                                backup.originalValue,
                                backup.originalType
                            )
                            if (WuwaDriver.writeMemory(item.address, dataBytes)) {
                                successfulChanges.add(
                                    BatchAddressValueChangedEvent.AddressChange(
                                        address = item.address,
                                        newValue = backup.originalValue,
                                        valueType = backup.originalType.nativeId
                                    )
                                )
                                MemoryBackupManager.removeBackup(item.address)
                                restoreCount++
                            } else {
                                failCount++
                            }
                        } catch (e: Exception) {
                            failCount++
                        }
                    }
                }
            }

            // 发送批量事件通知其他界面同步更新
            if (successfulChanges.isNotEmpty()) {
                FloatingEventBus.emitBatchAddressValueChanged(
                    BatchAddressValueChangedEvent(
                        changes = successfulChanges,
                        source = AddressValueChangedEvent.Source.SAVED_ADDRESS
                    )
                )
            }

            // 移除Address
            selectedItems.forEach { item ->
                savedAddresses.removeIf { it.address == item.address }
            }
            adapter.setAddresses(savedAddresses)
            updateEmptyState()
            updateAddressCountBadge()
            updateSavedCountText()

            if (failCount == 0) {
                notification.showSuccess("Restored and removed $restoreCount addresses")
            } else {
                notification.showWarning("Restore: $restoreCount, Failed: $failCount")
            }
        }
    }

    /**
     * 移除选中的Address
     */
    private fun removeSelectedAddresses() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            notification.showWarning("No items selected")
            return
        }

        selectedItems.forEach { item ->
            savedAddresses.removeIf { it.address == item.address }
        }
        adapter.setAddresses(savedAddresses)
        updateEmptyState()
        updateAddressCountBadge()
        updateSavedCountText()

        notification.showSuccess("Removed ${selectedItems.size} addresses")
    }

    /**
     * ExportAddressTo文件
     */
    private fun exportAddresses() {
        if (savedAddresses.isEmpty()) {
            notification.showWarning("There are no addresses to export")
            return
        }

        coroutineScope.launch {
            val success = withContext(Dispatchers.IO) {
                SavedAddressRepository.saveAddresses(context, savedAddresses)
            }

            if (success) {
                notification.showSuccess("Saved ${savedAddresses.size} addresses to file")
            } else {
                notification.showError("Save failed")
            }
        }
    }

    /**
     * Restore选中Address的原始值
     */
    private fun restoreSelectedValues() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            notification.showWarning("No items selected")
            return
        }

        if (!WuwaDriver.isProcessBound) {
            notification.showError("No process bound")
            return
        }

        coroutineScope.launch {
            var restoreCount = 0
            var noBackupCount = 0
            var failCount = 0
            val successfulChanges = mutableListOf<BatchAddressValueChangedEvent.AddressChange>()

            withContext(Dispatchers.IO) {
                selectedItems.forEach { item ->
                    val backup = MemoryBackupManager.getBackup(item.address)
                    if (backup != null) {
                        try {
                            val dataBytes = ValueTypeUtils.parseExprToBytes(
                                backup.originalValue,
                                backup.originalType
                            )
                            if (WuwaDriver.writeMemory(item.address, dataBytes)) {
                                withContext(Dispatchers.Main) {
                                    val index =
                                        savedAddresses.indexOfFirst { it.address == item.address }
                                    if (index >= 0) {
                                        savedAddresses[index] =
                                            item.copy(value = backup.originalValue)
                                        adapter.updateAddress(savedAddresses[index])
                                    }
                                }
                                successfulChanges.add(
                                    BatchAddressValueChangedEvent.AddressChange(
                                        address = item.address,
                                        newValue = backup.originalValue,
                                        valueType = backup.originalType.nativeId
                                    )
                                )
                                MemoryBackupManager.removeBackup(item.address)
                                restoreCount++
                            } else {
                                failCount++
                            }
                        } catch (e: Exception) {
                            failCount++
                        }
                    } else {
                        noBackupCount++
                    }
                }
            }

            // 发送批量事件通知其他界面同步更新
            if (successfulChanges.isNotEmpty()) {
                FloatingEventBus.emitBatchAddressValueChanged(
                    BatchAddressValueChangedEvent(
                        changes = successfulChanges,
                        source = AddressValueChangedEvent.Source.SAVED_ADDRESS
                    )
                )
            }

            when {
                restoreCount > 0 && failCount == 0 && noBackupCount == 0 -> {
                    notification.showSuccess("Restored $restoreCount addresses")
                }

                noBackupCount > 0 -> {
                    notification.showWarning("Restored: $restoreCount, no backup: $noBackupCount")
                }

                else -> {
                    notification.showWarning("Restore: $restoreCount, Failed: $failCount")
                }
            }
        }
    }

    /**
     * 显示载入Address对话框
     */
    private fun showLoadAddressesDialog() {
        coroutineScope.launch {
            val savedLists = withContext(Dispatchers.IO) {
                SavedAddressRepository.getSavedListNames(context)
            }

            if (savedLists.isEmpty()) {
                notification.showWarning("There are no saved address lists")
                return@launch
            }

            context.simpleSingleChoiceDialog(
                title = "Choose an address list",
                options = savedLists.toTypedArray(),
                showRadioButton = false,
                onSingleChoice = { which ->
                    loadAddressesFromFile(savedLists[which])
                }
            )
        }
    }

    /**
     * From文件加载Address
     */
    private fun loadAddressesFromFile(fileName: String) {
        coroutineScope.launch {
            val loadedAddresses = withContext(Dispatchers.IO) {
                SavedAddressRepository.loadAddresses(context, fileName)
            }

            if (loadedAddresses.isNotEmpty()) {
                saveAddresses(loadedAddresses)
                notification.showSuccess("Loaded ${loadedAddresses.size} addresses")
            } else {
                notification.showError("Load failed or the file is empty")
            }
        }
    }

    /**
     * 显示导入Address对话框
     */
    private fun showImportAddressDialog() {
        val dialog = ImportAddressDialog(
            context = context,
            notification = notification,
            coroutineScope = coroutineScope,
            onImportComplete = { importedAddresses ->
                saveAddresses(importedAddresses)
            }
        )
        dialog.show()
    }

    /**
     * Export选中的AddressTo文件
     */
    private fun exportSelectedAddresses() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.size < 2) {
            notification.showWarning("Please select at least 2 addresses")
            return
        }

        if (!WuwaDriver.isProcessBound) {
            notification.showError("No process bound")
            return
        }

        // 获取当前进程的包名作为默认文件名
        val processInfo = WuwaDriver.getProcessInfo(WuwaDriver.currentBindPid)
        val defaultFileName = processInfo?.name ?: "export_${System.currentTimeMillis()}"

        // 创建ranges列表
        val ranges = selectedItems.map { item ->
            val size = item.displayValueType?.memorySize ?: 4
            DisplayMemRegionEntry(
                start = item.address,
                end = item.address + size,
                type = 0x03,
                name = item.range.displayName,
                range = item.range
            )
        }

        val dialog = ExportAddressDialog(
            context = context,
            notification = notification,
            coroutineScope = coroutineScope,
            selectedItems = selectedItems,
            ranges = ranges,
            defaultFileName = defaultFileName
        )

        dialog.show()
    }

    /**
     * 将选中的Address设为Search结果
     */
    private fun setSelectedAsSearchResults() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            notification.showWarning("No items selected")
            return
        }

        coroutineScope.launch {
            val addresses = selectedItems.map { it.address }
            val types = selectedItems.map {
                it.displayValueType ?: DisplayValueType.DWORD
            }.toTypedArray()

            val success = withContext(Dispatchers.IO) {
                SearchEngine.addResultsFromAddresses(addresses, types)
            }

            if (success) {
                val totalCount = SearchEngine.getTotalResultCount()
                notification.showSuccess("Set ${selectedItems.size} addresses as search results")

                // 为每个Address创建独立的 DisplayMemRegionEntry，避免不连续Address的问题
                val ranges = selectedItems.map { item ->
                    val size = item.displayValueType?.memorySize ?: 4
                    DisplayMemRegionEntry(
                        start = item.address,
                        end = item.address + size,
                        type = 0x03, // r/w
                        name = item.range.displayName,
                        range = item.range
                    )
                }

                // 发送Search结果更新事件
                coroutineScope.launch {
                    FloatingEventBus.emitSearchResultsUpdated(
                        SearchResultsUpdatedEvent(totalCount, ranges)
                    )
                }
            } else {
                notification.showError("Failed to set search results")
            }
        }
    }

    /**
     * 计算选中Address的偏移异或（通过Service显示对话框）
     */
    private fun calculateOffsetXor() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.size < 2) {
            notification.showWarning("Please select at least 2 addresses")
            return
        }

        FloatingEventBus.tryEmitUIAction(
            UIActionEvent.ShowOffsetXorDialog(selectedItems)
        )
    }

    /**
     * 显示更改类型对话框
     */
    private fun showChangeTypeDialog() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            notification.showWarning("No items selected")
            return
        }

        val allValueTypes = DisplayValueType.entries.filter { !it.isDisabled }.toTypedArray()
        val valueTypeNames = allValueTypes.map { it.displayName }.toTypedArray()
        val valueTypeColors = allValueTypes.map { it.textColor }.toTypedArray()

        context.simpleSingleChoiceDialog(
            title = "Choose a new type",
            options = valueTypeNames,
            textColors = valueTypeColors,
            showRadioButton = false,
            onSingleChoice = { which ->
                val newType = allValueTypes[which]
                changeSelectedAddressTypes(selectedItems, newType)
            }
        )
    }

    /**
     * 更改选中Address的类型并刷新Memory值
     */
    private fun changeSelectedAddressTypes(items: List<SavedAddress>, newType: DisplayValueType) {
        if (!WuwaDriver.isProcessBound) {
            // No process bound时，只更改类型不刷新值
            items.forEach { item ->
                val index = savedAddresses.indexOfFirst { it.address == item.address }
                if (index >= 0) {
                    savedAddresses[index] = item.copy(valueType = newType.nativeId)
                    adapter.updateAddress(savedAddresses[index])
                }
            }
            notification.showWarning("Type changed, but the value cannot be refreshed because no process is bound")
            return
        }

        coroutineScope.launch {
            // 准备批量读取参数
            val addrs = items.map { it.address }.toLongArray()
            val sizes = IntArray(items.size) { newType.memorySize.toInt() }

            // 批量读取Memory
            val results = withContext(Dispatchers.IO) {
                WuwaDriver.batchReadMemory(addrs, sizes)
            }

            // 更新类型和值
            var successCount = 0
            var failCount = 0

            results.forEachIndexed { index, bytes ->
                val item = items[index]
                val addrIndex = savedAddresses.indexOfFirst { it.address == item.address }

                if (addrIndex >= 0) {
                    if (bytes != null) {
                        try {
                            val newValue = ValueTypeUtils.bytesToDisplayValue(bytes, newType)
                            savedAddresses[addrIndex] = item.copy(
                                valueType = newType.nativeId,
                                value = newValue
                            )
                            adapter.updateAddress(savedAddresses[addrIndex])
                            successCount++
                        } catch (e: Exception) {
                            // 转换失败，只更新类型
                            savedAddresses[addrIndex] = item.copy(valueType = newType.nativeId)
                            adapter.updateAddress(savedAddresses[addrIndex])
                            failCount++
                        }
                    } else {
                        // 读取失败，只更新类型
                        savedAddresses[addrIndex] = item.copy(valueType = newType.nativeId)
                        adapter.updateAddress(savedAddresses[addrIndex])
                        failCount++
                    }
                }
            }

            if (failCount == 0) {
                notification.showSuccess("Changed $successCount addresses to type ${newType.code}")
            } else {
                notification.showWarning("Succeeded: $successCount, read failed: $failCount")
            }
        }
    }

    /**
     * 显示Offset Calculator
     */
    private fun showOffsetCalculator() {
        val selectedItems = adapter.getSelectedItems()
        var initialBaseAddress: Long? = null
        if (selectedItems.isNotEmpty()) {
            initialBaseAddress = selectedItems.firstOrNull()?.address
        }

        FloatingEventBus.tryEmitUIAction(
            UIActionEvent.ShowOffsetCalculatorDialog(
                initialBaseAddress = initialBaseAddress
            )
        )
    }

    /**
     * 显示实时监视悬浮窗（选中的Address）
     */
    private fun showRealtimeMonitorForSelected() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            notification.showError("Please select an address to monitor first")
            return
        }

        RealtimeMonitorOverlay(context, selectedItems).show()
        notification.showSuccess("Added ${selectedItems.size} addresses to real-time monitoring")
    }

    private fun updateEmptyState() {
        if (savedAddresses.isEmpty()) {
            binding.emptyStateView.visibility = View.VISIBLE
            binding.savedAddressesRecyclerView.visibility = View.GONE
        } else {
            binding.emptyStateView.visibility = View.GONE
            binding.savedAddressesRecyclerView.visibility = View.VISIBLE
        }
    }

    /**
     * 启动自动更新（只更新可见部分）
     */
    fun startAutoUpdate() {
        // 如果已有任务在运行，先停止
        stopAutoUpdate()

        autoUpdateJob = coroutineScope.launch {
            while (isActive) {
                val interval = MMKV.defaultMMKV().saveListUpdateInterval.toLong()
                delay(interval)
                updateVisibleAddresses()
            }
        }
    }

    /**
     * 停止自动更新
     */
    fun stopAutoUpdate() {
        autoUpdateJob?.cancel()
        autoUpdateJob = null
    }

    /**
     * 只更新可见Range的Address值（优化性能）
     */
    private suspend fun updateVisibleAddresses() {
        // 记录当前进程 PID（窗口期保护）
        val currentPid = WuwaDriver.currentBindPid
        if (savedAddresses.isEmpty() || currentPid <= 0) {
            return
        }

        // 获取可见Range
        val layoutManager = binding.savedAddressesRecyclerView.layoutManager as? LinearLayoutManager
            ?: return

        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val lastVisible = layoutManager.findLastVisibleItemPosition()

        if (firstVisible < 0 || lastVisible < 0 || firstVisible > lastVisible) {
            return
        }

        // 安全的边界检查
        val safeFirst = firstVisible.coerceIn(0, savedAddresses.size - 1)
        val safeLast = lastVisible.coerceIn(0, savedAddresses.size - 1)

        if (safeFirst > safeLast) return

        // 创建快照（防止并发修改）
        val snapshot = try {
            savedAddresses.subList(safeFirst, safeLast + 1).toList()
        } catch (e: Exception) {
            return
        }

        // 准备批量读取参数
        val addrs = snapshot.map { it.address }.toLongArray()
        val sizes = snapshot.map {
            it.displayValueType?.memorySize?.toInt() ?: DisplayValueType.DWORD.memorySize.toInt()
        }.toIntArray()

        val results = try {
            withContext(Dispatchers.IO) {
                WuwaDriver.batchReadMemory(addrs, sizes)
            }
        } catch (e: Exception) {
            return
        }

        // 关键检查：进程是否还是同一个？
        if (WuwaDriver.currentBindPid != currentPid) {
            // 进程已切换，丢弃这批数据
            return
        }

        // 安全更新 UI（通过Address查找，即使列表变化也不会崩溃）
        results.forEachIndexed { index, bytes ->
            try {
                val snapshotItem = snapshot[index]
                val currentIndex =
                    savedAddresses.indexOfFirst { it.address == snapshotItem.address }

                if (currentIndex >= 0 && bytes != null) {
                    val valueType = savedAddresses[currentIndex].displayValueType
                        ?: DisplayValueType.DWORD
                    val newValue = ValueTypeUtils.bytesToDisplayValue(bytes, valueType)

                    // 只在值变化时更新，避免无意义的刷新
                    if (savedAddresses[currentIndex].value != newValue) {
                        savedAddresses[currentIndex] =
                            savedAddresses[currentIndex].copy(value = newValue)
                        adapter.updateAddress(savedAddresses[currentIndex])
                    }
                }
            } catch (e: Exception) {
                // 忽略单个Address的Error，继续处理其他Address
            }
        }
    }

    /**
     * 处理冻结状态切换
     */
    private fun handleFreezeToggle(address: SavedAddress, isFrozen: Boolean) {
        val index = savedAddresses.indexOfFirst { it.address == address.address }
        if (index < 0) return

        // 更新Memory中的状态
        savedAddresses[index] = savedAddresses[index].copy(isFrozen = isFrozen)

        if (isFrozen) {
            // 添加To冻结管理器
            val valueType = DisplayValueType.fromNativeId(address.valueType)
            if (valueType != null) {
                val success = FreezeManager.addFrozen(address.address, address.value, valueType)
                if (success) {
                    notification.showSuccess("Frozen: 0x${address.address.toString(16).uppercase()}")
                } else {
                    notification.showError("Freeze failed")
                    // 回滚状态
                    savedAddresses[index] = savedAddresses[index].copy(isFrozen = false)
                    adapter.notifyItemChanged(index)
                }
            } else {
                notification.showError("Unsupported value type")
                savedAddresses[index] = savedAddresses[index].copy(isFrozen = false)
                adapter.notifyItemChanged(index)
            }
        } else {
            // From冻结管理器移除
            FreezeManager.removeFrozen(address.address)
            notification.showSuccess("Unfroze value")
        }
    }

    override fun cleanup() {
        super.cleanup()
        binding.savedFastScroller.detachFromRecyclerView()
        stopAutoUpdate()
        coroutineScope.cancel()
    }
}