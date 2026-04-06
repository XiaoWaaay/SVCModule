package moe.fuqiuluo.mamu.ui.viewmodel

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.fuqiuluo.mamu.data.local.LogRepository
import moe.fuqiuluo.mamu.data.model.LogEntry
import moe.fuqiuluo.mamu.data.model.LogFilterConfig
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 环形缓冲区 - O(1) 写入，无Memory分配
 */
class RingBuffer<T>(private val capacity: Int) {
    private val buffer = arrayOfNulls<Any>(capacity)
    private var head = 0  // 写入位置
    private var size = 0
    
    @Synchronized
    fun add(item: T) {
        buffer[head] = item
        head = (head + 1) % capacity
        if (size < capacity) size++
    }
    
    @Synchronized
    fun addAll(items: Collection<T>) {
        for (item in items) add(item)
    }
    
    @Synchronized
    fun snapshot(): List<T> {
        if (size == 0) return emptyList()
        val result = ArrayList<T>(size)
        val start = if (size < capacity) 0 else head
        for (i in 0 until size) {
            @Suppress("UNCHECKED_CAST")
            result.add(buffer[(start + i) % capacity] as T)
        }
        return result
    }
    
    @Synchronized
    fun clear() {
        buffer.fill(null)
        head = 0
        size = 0
    }
    
    @Synchronized
    fun size() = size
}

/**
 * 日志 UI 状态（轻量，不含日志数据）
 */
data class LogUiState(
    val isCapturing: Boolean = false,
    val filterExpression: String = "package:mine",
    val filterConfig: LogFilterConfig = LogFilterConfig(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val autoScroll: Boolean = true,
    val wordWrap: Boolean = false,
    val logCount: Int = 0,        // 仅计数，触发重组
    val logVersion: Long = 0      // 版本号，触发 UI 刷新
)

class LogViewModel(
    private val logRepository: LogRepository = LogRepository()
) : ViewModel() {

    companion object {
        private const val MAX_LOG_COUNT = 10000
        private const val BATCH_INTERVAL_MS = 50L
    }

    private val _uiState = MutableStateFlow(LogUiState())
    val uiState: StateFlow<LogUiState> = _uiState.asStateFlow()

    // 环形缓冲区存储原始日志
    private val logBuffer = RingBuffer<LogEntry>(MAX_LOG_COUNT)
    
    // 过滤后的日志快照（供 UI 读取）
    private val _filteredLogs = mutableStateListOf<LogEntry>()
    val filteredLogs: SnapshotStateList<LogEntry> = _filteredLogs
    
    // 待处理日志队列（无锁并发队列）
    private val pendingLogs = ConcurrentLinkedQueue<LogEntry>()
    
    // 当前解析后的过滤器（用于客户端过滤）
    @Volatile
    private var currentFilter: ClientLogFilter = ClientLogFilter.parse("package:mine", logRepository.myPid)
    
    private var captureJob: Job? = null
    private var batchJob: Job? = null

    val myPid: Int = logRepository.myPid

    init {
        startBatchProcessor()
    }

    /**
     * 批量处理器 - 定期刷新待处理日志To UI
     */
    private fun startBatchProcessor() {
        batchJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(BATCH_INTERVAL_MS)
                flushPendingLogs()
            }
        }
    }

    /**
     * 刷新待处理日志
     */
    private fun flushPendingLogs() {
        val batch = mutableListOf<LogEntry>()
        while (true) {
            val entry = pendingLogs.poll() ?: break
            batch.add(entry)
        }
        
        if (batch.isEmpty()) return
        
        // 写入环形缓冲区
        logBuffer.addAll(batch)
        
        // 使用当前过滤器过滤
        val filter = currentFilter
        val filtered = batch.filter { filter.matches(it) }
        
        if (filtered.isNotEmpty()) {
            // 限制 UI 列表大小
            val overflow = _filteredLogs.size + filtered.size - MAX_LOG_COUNT
            if (overflow > 0) {
                _filteredLogs.removeRange(0, overflow.coerceAtMost(_filteredLogs.size))
            }
            _filteredLogs.addAll(filtered)
        }
        
        // 更新计数触发状态变化
        _uiState.update { it.copy(logCount = logBuffer.size(), logVersion = it.logVersion + 1) }
    }

    /**
     * 开始捕获日志
     */
    fun startCapture() {
        if (_uiState.value.isCapturing) return

        captureJob?.cancel()
        captureJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isCapturing = true, error = null) }

            val expression = _uiState.value.filterExpression
            
            try {
                logRepository.captureLogcatWithExpression(expression)
                    .catch { e ->
                        _uiState.update { it.copy(error = e.message, isCapturing = false) }
                    }
                    .collect { entry ->
                        // 无锁入队
                        pendingLogs.offer(entry)
                    }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isCapturing = false) }
            }
        }
    }

    /**
     * 停止捕获日志
     */
    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        logRepository.stopCapture()
        _uiState.update { it.copy(isCapturing = false) }
    }

    /**
     * 清除日志（UI + logcat 缓冲区）
     */
    fun clearLogs() {
        logBuffer.clear()
        _filteredLogs.clear()
        _uiState.update { it.copy(logCount = 0, logVersion = it.logVersion + 1) }
        viewModelScope.launch(Dispatchers.IO) {
            logRepository.clearLogcat()
        }
    }

    /**
     * 设置过滤表达式 - 实时重新过滤已有日志
     */
    fun setFilterExpression(expression: String) {
        _uiState.update { it.copy(filterExpression = expression) }
        
        // 解析新的过滤器
        val newFilter = ClientLogFilter.parse(expression, myPid)
        currentFilter = newFilter
        
        // 重新过滤已有日志
        refilterLogs(newFilter)
    }
    
    /**
     * 重新过滤所有已有日志
     */
    private fun refilterLogs(filter: ClientLogFilter) {
        viewModelScope.launch(Dispatchers.Default) {
            val allLogs = logBuffer.snapshot()
            val filtered = allLogs.filter { filter.matches(it) }.takeLast(MAX_LOG_COUNT)
            
            // 使用 Snapshot 保证原子性，UI 不会看To中间状态
            Snapshot.withMutableSnapshot {
                _filteredLogs.clear()
                _filteredLogs.addAll(filtered)
            }
            
            _uiState.update { it.copy(logVersion = it.logVersion + 1) }
        }
    }

    /**
     * 切换自动滚动
     */
    fun toggleAutoScroll() {
        _uiState.update { it.copy(autoScroll = !it.autoScroll) }
    }

    /**
     * 切换自动换行
     */
    fun toggleWordWrap() {
        _uiState.update { it.copy(wordWrap = !it.wordWrap) }
    }

    override fun onCleared() {
        super.onCleared()
        stopCapture()
        batchJob?.cancel()
    }
}

/**
 * 客户端日志过滤器 - 解析表达式并在客户端过滤日志
 * 支持: tag:xxx, level:v/d/i/w/e/f, pid:xxx, package:mine, 以及纯文本Search
 */
data class ClientLogFilter(
    val pidFilter: Int? = null,
    val tagFilter: String? = null,
    val levelFilter: moe.fuqiuluo.mamu.data.model.LogLevel? = null,
    val textFilter: String? = null
) {
    companion object {
        fun parse(expression: String, myPid: Int): ClientLogFilter {
            if (expression.isBlank()) return ClientLogFilter()
            
            val expr = expression.trim()
            
            return when {
                expr.equals("package:mine", ignoreCase = true) -> {
                    ClientLogFilter(pidFilter = myPid)
                }
                expr.startsWith("pid:", ignoreCase = true) -> {
                    val pid = expr.substringAfter(":").trim().toIntOrNull()
                    ClientLogFilter(pidFilter = pid)
                }
                expr.startsWith("tag:", ignoreCase = true) -> {
                    val tag = expr.substringAfter(":").trim()
                    ClientLogFilter(tagFilter = tag)
                }
                expr.startsWith("level:", ignoreCase = true) -> {
                    val levelStr = expr.substringAfter(":").trim().uppercase()
                    val level = when (levelStr) {
                        "V", "VERBOSE" -> moe.fuqiuluo.mamu.data.model.LogLevel.VERBOSE
                        "D", "DEBUG" -> moe.fuqiuluo.mamu.data.model.LogLevel.DEBUG
                        "I", "INFO" -> moe.fuqiuluo.mamu.data.model.LogLevel.INFO
                        "W", "WARN", "WARNING" -> moe.fuqiuluo.mamu.data.model.LogLevel.WARNING
                        "E", "ERROR" -> moe.fuqiuluo.mamu.data.model.LogLevel.ERROR
                        "F", "FATAL" -> moe.fuqiuluo.mamu.data.model.LogLevel.FATAL
                        else -> null
                    }
                    ClientLogFilter(levelFilter = level)
                }
                expr.startsWith("package:", ignoreCase = true) -> {
                    // 其他包名暂不支持客户端过滤，返回空过滤器（显示全部）
                    ClientLogFilter()
                }
                else -> {
                    // 纯文本Search：匹配 tag 或 message
                    ClientLogFilter(textFilter = expr)
                }
            }
        }
    }
    
    fun matches(entry: LogEntry): Boolean {
        // PID 过滤
        if (pidFilter != null && entry.pid != pidFilter) return false
        
        // Tag 过滤（包含匹配）
        if (tagFilter != null && !entry.tag.contains(tagFilter, ignoreCase = true)) return false
        
        // Level 过滤（>=）
        if (levelFilter != null && entry.level.priority < levelFilter.priority) return false
        
        // 文本Search（tag 或 message 包含）
        if (textFilter != null) {
            val matchesTag = entry.tag.contains(textFilter, ignoreCase = true)
            val matchesMsg = entry.message.contains(textFilter, ignoreCase = true)
            if (!matchesTag && !matchesMsg) return false
        }
        
        return true
    }
}
