package moe.fuqiuluo.mamu.floating.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 悬浮窗事件总线
 * 用于在不同控制器之间广播事件，实现解耦通信
 */
object FloatingEventBus {
    // Address值变更事件流（单个）
    private val _addressValueChangedEvents = MutableSharedFlow<AddressValueChangedEvent>(
        replay = 0,
        extraBufferCapacity = 16
    )
    val addressValueChangedEvents: SharedFlow<AddressValueChangedEvent> = _addressValueChangedEvents.asSharedFlow()

    // Address值变更事件流（批量）
    private val _batchAddressValueChangedEvents = MutableSharedFlow<BatchAddressValueChangedEvent>(
        replay = 0,
        extraBufferCapacity = 8
    )
    val batchAddressValueChangedEvents: SharedFlow<BatchAddressValueChangedEvent> = _batchAddressValueChangedEvents.asSharedFlow()

    // 进程状态变更事件流
    private val _processStateEvents = MutableSharedFlow<ProcessStateEvent>(
        replay = 0,  // 1 ？保留最新状态，新订阅者可以立即获取当前状态
        extraBufferCapacity = 4
    )
    val processStateEvents: SharedFlow<ProcessStateEvent> = _processStateEvents.asSharedFlow()

    // UI 操作请求事件流
    private val _uiActionEvents = MutableSharedFlow<UIActionEvent>(
        replay = 0,
        extraBufferCapacity = 8
    )
    val uiActionEvents: SharedFlow<UIActionEvent> = _uiActionEvents.asSharedFlow()

    // MemoryRange配置变更事件流
    private val _memoryRangeChangedEvents = MutableSharedFlow<MemoryRangeChangedEvent>(
        replay = 0,
        extraBufferCapacity = 4
    )
    val memoryRangeChangedEvents: SharedFlow<MemoryRangeChangedEvent> = _memoryRangeChangedEvents.asSharedFlow()

    // 保存Search结果事件流
    private val _saveSearchResultsEvents = MutableSharedFlow<SaveSearchResultsEvent>(
        replay = 0,
        extraBufferCapacity = 8
    )
    val saveSearchResultsEvents: SharedFlow<SaveSearchResultsEvent> = _saveSearchResultsEvents.asSharedFlow()

    // Search结果更新事件流（From保存Address界面Search后）
    private val _searchResultsUpdatedEvents = MutableSharedFlow<SearchResultsUpdatedEvent>(
        replay = 0,
        extraBufferCapacity = 4
    )
    val searchResultsUpdatedEvents: SharedFlow<SearchResultsUpdatedEvent> = _searchResultsUpdatedEvents.asSharedFlow()

    // 导航ToMemoryAddress事件流
    private val _navigateToMemoryAddressEvents = MutableSharedFlow<NavigateToMemoryAddressEvent>(
        replay = 0,  // 1？保留最新事件，确保Tab切换后订阅者能收To
        extraBufferCapacity = 4
    )
    val navigateToMemoryAddressEvents: SharedFlow<NavigateToMemoryAddressEvent> = _navigateToMemoryAddressEvents.asSharedFlow()

    // 保存Memory预览ToAddress事件流
    private val _saveMemoryPreviewEvents = MutableSharedFlow<SaveMemoryPreviewEvent>(
        replay = 0,
        extraBufferCapacity = 8
    )
    val saveMemoryPreviewEvents: SharedFlow<SaveMemoryPreviewEvent> = _saveMemoryPreviewEvents.asSharedFlow()

    // 保存并冻结Address事件流
    private val _saveAndFreezeEvents = MutableSharedFlow<SaveAndFreezeEvent>(
        replay = 0,
        extraBufferCapacity = 8
    )
    val saveAndFreezeEvents: SharedFlow<SaveAndFreezeEvent> = _saveAndFreezeEvents.asSharedFlow()

    /**
     * 发送Address值变更事件（单个）
     */
    suspend fun emitAddressValueChanged(event: AddressValueChangedEvent) {
        _addressValueChangedEvents.emit(event)
    }

    /**
     * 发送Address值变更事件（单个）- 非挂起版本
     */
    fun tryEmitAddressValueChanged(event: AddressValueChangedEvent): Boolean {
        return _addressValueChangedEvents.tryEmit(event)
    }

    /**
     * 发送Address值变更事件（批量）
     * 用于批量修改操作，避免发送大量单个事件造成性能问题
     */
    suspend fun emitBatchAddressValueChanged(event: BatchAddressValueChangedEvent) {
        _batchAddressValueChangedEvents.emit(event)
    }

    /**
     * 发送Address值变更事件（批量）- 非挂起版本
     */
    fun tryEmitBatchAddressValueChanged(event: BatchAddressValueChangedEvent): Boolean {
        return _batchAddressValueChangedEvents.tryEmit(event)
    }

    /**
     * 发送进程状态变更事件
     */
    suspend fun emitProcessState(event: ProcessStateEvent) {
        _processStateEvents.emit(event)
    }

    /**
     * 发送进程状态变更事件 - 非挂起版本
     */
    fun tryEmitProcessState(event: ProcessStateEvent): Boolean {
        return _processStateEvents.tryEmit(event)
    }

    /**
     * 发送 UI 操作请求事件
     */
    suspend fun emitUIAction(event: UIActionEvent) {
        _uiActionEvents.emit(event)
    }

    /**
     * 发送 UI 操作请求事件 - 非挂起版本
     */
    fun tryEmitUIAction(event: UIActionEvent): Boolean {
        return _uiActionEvents.tryEmit(event)
    }

    /**
     * 发送MemoryRange配置变更事件
     */
    suspend fun emitMemoryRangeChanged() {
        _memoryRangeChangedEvents.emit(MemoryRangeChangedEvent)
    }

    /**
     * 发送MemoryRange配置变更事件 - 非挂起版本
     */
    fun tryEmitMemoryRangeChanged(): Boolean {
        return _memoryRangeChangedEvents.tryEmit(MemoryRangeChangedEvent)
    }

    /**
     * 发送保存Search结果事件
     */
    suspend fun emitSaveSearchResults(event: SaveSearchResultsEvent) {
        _saveSearchResultsEvents.emit(event)
    }

    /**
     * 发送保存Search结果事件 - 非挂起版本
     */
    fun tryEmitSaveSearchResults(event: SaveSearchResultsEvent): Boolean {
        return _saveSearchResultsEvents.tryEmit(event)
    }

    /**
     * 发送Search结果更新事件
     */
    suspend fun emitSearchResultsUpdated(event: SearchResultsUpdatedEvent) {
        _searchResultsUpdatedEvents.emit(event)
    }

    /**
     * 发送Search结果更新事件 - 非挂起版本
     */
    fun tryEmitSearchResultsUpdated(event: SearchResultsUpdatedEvent): Boolean {
        return _searchResultsUpdatedEvents.tryEmit(event)
    }

    /**
     * 发送导航ToMemoryAddress事件
     */
    suspend fun emitNavigateToMemoryAddress(event: NavigateToMemoryAddressEvent) {
        _navigateToMemoryAddressEvents.emit(event)
    }

    /**
     * 发送导航ToMemoryAddress事件 - 非挂起版本
     */
    fun tryEmitNavigateToMemoryAddress(event: NavigateToMemoryAddressEvent): Boolean {
        return _navigateToMemoryAddressEvents.tryEmit(event)
    }

    /**
     * 发送保存Memory预览事件
     */
    suspend fun emitSaveMemoryPreview(event: SaveMemoryPreviewEvent) {
        _saveMemoryPreviewEvents.emit(event)
    }

    /**
     * 发送保存Memory预览事件 - 非挂起版本
     */
    fun tryEmitSaveMemoryPreview(event: SaveMemoryPreviewEvent): Boolean {
        return _saveMemoryPreviewEvents.tryEmit(event)
    }

    /**
     * 发送保存并冻结Address事件
     */
    suspend fun emitSaveAndFreeze(event: SaveAndFreezeEvent) {
        _saveAndFreezeEvents.emit(event)
    }

    /**
     * 发送保存并冻结Address事件 - 非挂起版本
     */
    fun tryEmitSaveAndFreeze(event: SaveAndFreezeEvent): Boolean {
        return _saveAndFreezeEvents.tryEmit(event)
    }
}
