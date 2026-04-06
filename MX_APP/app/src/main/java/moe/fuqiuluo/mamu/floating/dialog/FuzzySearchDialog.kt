package moe.fuqiuluo.mamu.floating.dialog

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.data.settings.selectedMemoryRanges
import moe.fuqiuluo.mamu.driver.FuzzyCondition
import moe.fuqiuluo.mamu.driver.SearchEngine
import moe.fuqiuluo.mamu.driver.SearchMode
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import moe.fuqiuluo.mamu.floating.event.FloatingEventBus
import moe.fuqiuluo.mamu.floating.event.UIActionEvent
import moe.fuqiuluo.mamu.floating.ext.divideToSimpleMemoryRange
import moe.fuqiuluo.mamu.floating.ext.formatElapsedTime
import moe.fuqiuluo.mamu.widget.FixedLinearLayout
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import moe.fuqiuluo.mamu.widget.simpleSingleChoiceDialog

private const val TAG = "FuzzySearchDialog"

/**
 * 模糊Search对话框
 */
class FuzzySearchDialog(
    context: Context,
    private val notification: NotificationOverlay,
    private val onSearchCompleted: ((ranges: List<DisplayMemRegionEntry>, totalFound: Long) -> Unit)? = null,
    private val onRefineCompleted: ((totalFound: Long) -> Unit)? = null
) : BaseDialog(context) {
    private val searchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var contentView: View
    private lateinit var searchRanges: List<DisplayMemRegionEntry>

    // View引用
    private lateinit var rootContainer: FixedLinearLayout
    private lateinit var tvSubtitle: MaterialTextView
    private lateinit var tvCurrentResults: MaterialTextView
    private lateinit var btnCancel: MaterialButton

    // 当前选中的数据类型
    private var currentValueType: DisplayValueType = DisplayValueType.DWORD

    // 当前模式：true=Initial Scan, false=细化Search
    private var isInitialMode = true

    // 当前布局资源
    private var currentLayoutResource: Int = R.layout.dialog_fuzzy_search_initial

    // 进度相关
    private var progressDialog: SearchProgressDialog? = null
    var isSearching = false
    private var searchStartTime = 0L

    // 标记SearchCompleted且有结果，等待Restore显示
    private var shouldShowAfterFullscreen = false

    @SuppressLint("SetTextI18n")
    override fun setupDialog() {
        // 初始化SearchRange（需要在布局inflate之前）
        val mmkv = MMKV.defaultMMKV()
        val selectedRanges = mmkv.selectedMemoryRanges
        searchRanges = WuwaDriver.queryMemRegionsWithRetry().also {
            Log.d(TAG, "找To ${it.size} 个maps项")
        }.divideToSimpleMemoryRange().filter {
            selectedRanges.contains(it.range)
        }

        // 检查当前应该使用哪个布局
        val hasResults = SearchEngine.getTotalResultCount() > 0
        val isFuzzyMode = SearchEngine.getCurrentSearchMode() == SearchMode.FUZZY
        isInitialMode = !(hasResults && isFuzzyMode)
        currentLayoutResource = if (isInitialMode) {
            R.layout.dialog_fuzzy_search_initial
        } else {
            R.layout.dialog_fuzzy_search_refine
        }

        // 根据当前模式inflate对应的布局
        val inflater = LayoutInflater.from(dialog.context)
        contentView = inflater.inflate(currentLayoutResource, null)
        dialog.setContentView(contentView)

        // 初始化View引用
        initViews()

        // 设置透明度
        val opacity = mmkv.getDialogOpacity()
        rootContainer.background?.alpha = (opacity * 255).toInt()

        setupUI()
    }

    /**
     * 初始化View引用
     */
    private fun initViews() {
        rootContainer = contentView.findViewById(R.id.root_container)
        tvSubtitle = contentView.findViewById(R.id.tv_subtitle)
        tvCurrentResults = contentView.findViewById(R.id.tv_current_results)
        btnCancel = contentView.findViewById(R.id.btn_cancel)
    }

    /**
     * 切换To指定布局
     */
    private fun switchToLayout(layoutRes: Int) {
        // 保存当前状态
        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.getDialogOpacity()
        val valueType = currentValueType

        // 重新inflate布局（根据资源ID动态加载不同的布局文件）
        val inflater = LayoutInflater.from(dialog.context)
        contentView = inflater.inflate(layoutRes, null)
        dialog.setContentView(contentView)

        // 重新初始化View引用
        initViews()

        // Restore状态
        rootContainer.background?.alpha = (opacity * 255).toInt()
        currentValueType = valueType

        // 重新设置UI和事件监听器
        setupUI()
        updateCurrentResults()
    }

    private fun setupUI() {
        // 数据类型选择（初始模式专属）
        contentView.findViewById<MaterialTextView>(R.id.btn_select_type)?.apply {
            text = currentValueType.displayName
            setOnClickListener {
                showValueTypeSelectionDialog()
            }
        }

        // 底部按钮
        btnCancel.setOnClickListener {
            dismiss()
        }

        // Search按钮（初始模式专属）
        contentView.findViewById<MaterialButton>(R.id.btn_search)?.setOnClickListener {
            if (!WuwaDriver.isProcessBound) {
                notification.showError("Please select a process first")
                return@setOnClickListener
            }
            startFuzzyInitialSearch()
        }

        // 新Search按钮（细化模式专属）
        contentView.findViewById<MaterialButton>(R.id.btn_new_search)?.setOnClickListener {
            resetToInitialMode()
        }

        // Advanced optionsExpand/Collapse（细化模式专属）
        contentView.findViewById<LinearLayout>(R.id.btn_expand_advanced)?.setOnClickListener {
            toggleAdvancedOptions()
        }

        // 细化模式按钮
        setupRefineButtons()

        // 更新当前结果统计
        updateCurrentResults()
    }

    /**
     * 设置细化模式所有按钮
     */
    private fun setupRefineButtons() {
        // 基础条件
        contentView.findViewById<MaterialButton>(R.id.btn_unchanged)
            ?.setOnClickListener { startRefineSearch(FuzzyCondition.UNCHANGED) }
        contentView.findViewById<MaterialButton>(R.id.btn_changed)
            ?.setOnClickListener { startRefineSearch(FuzzyCondition.CHANGED) }
        contentView.findViewById<MaterialButton>(R.id.btn_increased)
            ?.setOnClickListener { startRefineSearch(FuzzyCondition.INCREASED) }
        contentView.findViewById<MaterialButton>(R.id.btn_decreased)
            ?.setOnClickListener { startRefineSearch(FuzzyCondition.DECREASED) }

        // 增加指定值
        contentView.findViewById<MaterialButton>(R.id.btn_increased_by_1)?.setOnClickListener {
            startRefineSearch(FuzzyCondition.INCREASED_BY, 1)
        }
        contentView.findViewById<MaterialButton>(R.id.btn_increased_by_10)?.setOnClickListener {
            startRefineSearch(FuzzyCondition.INCREASED_BY, 10)
        }
        contentView.findViewById<MaterialButton>(R.id.btn_increased_by_100)?.setOnClickListener {
            startRefineSearch(FuzzyCondition.INCREASED_BY, 100)
        }
        contentView.findViewById<MaterialButton>(R.id.btn_increased_by_custom)?.setOnClickListener {
            showCustomValueDialog(FuzzyCondition.INCREASED_BY)
        }

        // 减少指定值
        contentView.findViewById<MaterialButton>(R.id.btn_decreased_by_1)?.setOnClickListener {
            startRefineSearch(FuzzyCondition.DECREASED_BY, 1)
        }
        contentView.findViewById<MaterialButton>(R.id.btn_decreased_by_10)?.setOnClickListener {
            startRefineSearch(FuzzyCondition.DECREASED_BY, 10)
        }
        contentView.findViewById<MaterialButton>(R.id.btn_decreased_by_100)?.setOnClickListener {
            startRefineSearch(FuzzyCondition.DECREASED_BY, 100)
        }
        contentView.findViewById<MaterialButton>(R.id.btn_decreased_by_custom)?.setOnClickListener {
            showCustomValueDialog(FuzzyCondition.DECREASED_BY)
        }

        // 增加百分比
        contentView.findViewById<MaterialButton>(R.id.btn_increased_by_10_percent)
            ?.setOnClickListener {
                startRefineSearch(FuzzyCondition.INCREASED_BY_PERCENT, 10)
            }
        contentView.findViewById<MaterialButton>(R.id.btn_increased_by_50_percent)
            ?.setOnClickListener {
                startRefineSearch(FuzzyCondition.INCREASED_BY_PERCENT, 50)
            }
        contentView.findViewById<MaterialButton>(R.id.btn_increased_by_100_percent)
            ?.setOnClickListener {
                startRefineSearch(FuzzyCondition.INCREASED_BY_PERCENT, 100)
            }
        contentView.findViewById<MaterialButton>(R.id.btn_increased_by_percent_custom)
            ?.setOnClickListener {
                showCustomPercentDialog(FuzzyCondition.INCREASED_BY_PERCENT)
            }

        // 减少百分比
        contentView.findViewById<MaterialButton>(R.id.btn_decreased_by_10_percent)
            ?.setOnClickListener {
                startRefineSearch(FuzzyCondition.DECREASED_BY_PERCENT, 10)
            }
        contentView.findViewById<MaterialButton>(R.id.btn_decreased_by_50_percent)
            ?.setOnClickListener {
                startRefineSearch(FuzzyCondition.DECREASED_BY_PERCENT, 50)
            }
        contentView.findViewById<MaterialButton>(R.id.btn_decreased_by_100_percent)
            ?.setOnClickListener {
                startRefineSearch(FuzzyCondition.DECREASED_BY_PERCENT, 100)
            }
        contentView.findViewById<MaterialButton>(R.id.btn_decreased_by_percent_custom)
            ?.setOnClickListener {
                showCustomPercentDialog(FuzzyCondition.DECREASED_BY_PERCENT)
            }
    }

    /**
     * 切换Advanced options显示/隐藏
     */
    private fun toggleAdvancedOptions() {
        val layoutAdvancedOptions =
            contentView.findViewById<LinearLayout>(R.id.layout_advanced_options) ?: return
        val ivExpandIcon = contentView.findViewById<ImageView>(R.id.iv_expand_icon) ?: return

        val isExpanded = layoutAdvancedOptions.visibility == View.VISIBLE

        if (isExpanded) {
            // Collapse
            layoutAdvancedOptions.visibility = View.GONE
            animateExpandIcon(ivExpandIcon, 0f)
        } else {
            // Expand
            layoutAdvancedOptions.visibility = View.VISIBLE
            animateExpandIcon(ivExpandIcon, 180f)
        }
    }

    /**
     * 旋转Expand图标动画
     */
    private fun animateExpandIcon(ivExpandIcon: ImageView, toRotation: Float) {
        val rotate = RotateAnimation(
            ivExpandIcon.rotation,
            toRotation,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        )
        rotate.duration = 200
        rotate.fillAfter = true
        ivExpandIcon.startAnimation(rotate)
        ivExpandIcon.rotation = toRotation
    }

    /**
     * 显示数据类型选择对话框
     */
    private fun showValueTypeSelectionDialog() {
        val allValueTypes = DisplayValueType.entries.filter { !it.isDisabled }.toTypedArray()
        val valueTypeNames = allValueTypes.map { it.displayName }.toTypedArray()
        val valueTypeColors = allValueTypes.map { it.textColor }.toTypedArray()

        val currentIndex = allValueTypes.indexOf(currentValueType)

        context.simpleSingleChoiceDialog(
            title = context.getString(R.string.fuzzy_search_select_type),
            options = valueTypeNames,
            textColors = valueTypeColors,
            selected = currentIndex,
            showTitle = true,
            showRadioButton = false,
            onSingleChoice = { which ->
                currentValueType = allValueTypes[which]
                contentView.findViewById<MaterialTextView>(R.id.btn_select_type)?.text =
                    currentValueType.displayName
            }
        )
    }

    /**
     * 显示Custom数值输入对话框
     */
    private fun showCustomValueDialog(condition: FuzzyCondition) {
        val input = EditText(context).apply {
            inputType =
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Enter a value"
        }

        AlertDialog.Builder(context)
            .setTitle("Enter a value")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val value = input.text.toString().toLongOrNull()
                if (value == null) {
                    notification.showError("The entered value has an invalid format")
                    return@setPositiveButton
                }
                startRefineSearch(condition, value)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * 显示Custom百分比输入对话框
     */
    private fun showCustomPercentDialog(condition: FuzzyCondition) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Enter a percentage (0-100)"
        }

        AlertDialog.Builder(context)
            .setTitle("Enter percentage")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val percent = input.text.toString().toLongOrNull()
                if (percent == null || percent < 0 || percent > 100) {
                    notification.showError("The percentage must be between 0 and 100")
                    return@setPositiveButton
                }
                startRefineSearch(condition, percent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * 更新模式UI
     */
    @SuppressLint("SetTextI18n")
    private fun updateModeUI() {
        val targetLayout = if (isInitialMode) {
            R.layout.dialog_fuzzy_search_initial
        } else {
            R.layout.dialog_fuzzy_search_refine
        }

        if (currentLayoutResource != targetLayout) {
            currentLayoutResource = targetLayout
            switchToLayout(targetLayout)
        }
    }

    /**
     * 更新当前结果统计
     */
    @SuppressLint("SetTextI18n")
    private fun updateCurrentResults() {
        val totalCount = SearchEngine.getTotalResultCount()
        if (totalCount > 0) {
            tvCurrentResults.visibility = View.VISIBLE
            tvCurrentResults.text = context.getString(
                R.string.fuzzy_search_current_results,
                totalCount
            )
        } else {
            tvCurrentResults.visibility = View.GONE
        }
    }

    /**
     * 开始模糊Initial Scan
     */
    private fun startFuzzyInitialSearch() {
        if (!WuwaDriver.isProcessBound) {
            notification.showError("No process is selected")
            return
        }

        if (searchRanges.isEmpty()) {
            notification.showError("No memory range is selected")
            return
        }

        searchScope.launch {
            val nativeRegions = mutableListOf<Long>()
            searchRanges.forEach { region ->
                nativeRegions.add(region.start)
                nativeRegions.add(region.end)
            }

            val success = SearchEngine.startFuzzySearchAsync(
                type = currentValueType,
                ranges = MMKV.defaultMMKV().selectedMemoryRanges,
                keepResult = false
            )

            withContext(Dispatchers.Main) {
                if (success) {
                    super.dismiss()  // Close主对话框，只保留进度对话框
                    isSearching = true
                    searchStartTime = System.currentTimeMillis()
                    showProgressDialog(false)
                    startProgressMonitoring(false)
                } else {
                    notification.showError("Failed to start fuzzy search")
                }
            }
        }
    }

    /**
     * 开始细化Search
     */
    private fun startRefineSearch(condition: FuzzyCondition, param1: Long = 0, param2: Long = 0) {
        if (!WuwaDriver.isProcessBound) {
            notification.showError("No process is selected")
            return
        }

        val currentCount = SearchEngine.getTotalResultCount()
        if (currentCount == 0L) {
            notification.showError(context.getString(R.string.fuzzy_search_no_results))
            return
        }

        searchScope.launch {
            val success = SearchEngine.startFuzzyRefineAsync(
                condition = condition,
                param1 = param1,
                param2 = param2
            )

            withContext(Dispatchers.Main) {
                if (success) {
                    super.dismiss()  // Close主对话框，只保留进度对话框
                    isSearching = true
                    searchStartTime = System.currentTimeMillis()
                    showProgressDialog(true)
                    startProgressMonitoring(true)
                } else {
                    notification.showError("Failed to start refine search")
                }
            }
        }
    }

    /**
     * ResetTo初始模式
     */
    private fun resetToInitialMode() {
        isInitialMode = true
        // 清空Search结果
        SearchEngine.clearSearchResults()
        updateModeUI()
        updateCurrentResults()
    }

    /**
     * 检查并更新对话框模式
     * 如果当前有模糊Search结果，则进入细化模式；否则进入初始模式
     */
    private fun checkAndUpdateMode() {
        val hasResults = SearchEngine.getTotalResultCount() > 0
        val isFuzzyMode = SearchEngine.getCurrentSearchMode() == SearchMode.FUZZY

        // 如果有模糊Search结果，则进入细化模式
        isInitialMode = !(hasResults && isFuzzyMode)

        updateModeUI()
        updateCurrentResults()
    }

    /**
     * 显示进度对话框
     */
    private fun showProgressDialog(isRefineSearch: Boolean) {
        progressDialog = SearchProgressDialog(
            context = context,
            isRefineSearch = isRefineSearch,
            onCancelClick = {
                cancelSearch()
            },
            onHideClick = {
                // 发送隐藏悬浮窗事件
                FloatingEventBus.tryEmitUIAction(UIActionEvent.HideFloatingWindow)
            }
        ).apply {
            show()
        }
    }

    /**
     * 启动进度监控
     */
    private fun startProgressMonitoring(isRefineSearch: Boolean) {
        searchScope.launch(Dispatchers.Main) {
            while (isActive && isSearching) {
                val status = SearchEngine.getStatus()
                val data = SearchProgressData(
                    currentProgress = SearchEngine.getProgress(),
                    regionsOrAddrsSearched = SearchEngine.getRegionsDone(),
                    totalFound = SearchEngine.getFoundCount(),
                    heartbeat = SearchEngine.getHeartbeat()
                )

                progressDialog?.updateProgress(data)

                when (status) {
                    SearchEngine.Status.COMPLETED -> {
                        val elapsed = System.currentTimeMillis() - searchStartTime
                        onSearchFinished(isRefineSearch, data.totalFound, elapsed)
                        break
                    }

                    SearchEngine.Status.CANCELLED -> {
                        onSearchCancelled()
                        break
                    }

                    SearchEngine.Status.ERROR -> {
                        onSearchError(SearchEngine.getErrorCode())
                        break
                    }

                    else -> {
                        // 继续监控
                    }
                }

                delay(100)
            }
        }
    }

    /**
     * SearchCompleted
     */
    private fun onSearchFinished(isRefineSearch: Boolean, totalFound: Long, elapsed: Long) {
        isSearching = false
        progressDialog?.dismiss()
        progressDialog = null

        val message = context.getString(
            R.string.success_search_complete,
            totalFound,
            formatElapsedTime(elapsed)
        )
        notification.showSuccess(message)

        // 更新UI
        if (!isRefineSearch) {
            // Initial ScanCompleted，切换To细化模式
            isInitialMode = false
            updateModeUI()
        }
        updateCurrentResults()

        // 如果有结果，标记为等待Restore显示
        if (totalFound > 0) {
            Log.d(TAG, "再次Restore全屏将自动显示模糊Searchdialog")
            shouldShowAfterFullscreen = true
        }

        // 回调
        if (isRefineSearch) {
            onRefineCompleted?.invoke(totalFound)
        } else {
            onSearchCompleted?.invoke(searchRanges, totalFound)
        }
    }

    /**
     * Search被Cancel
     */
    private fun onSearchCancelled() {
        isSearching = false
        progressDialog?.dismiss()
        progressDialog = null
        notification.showWarning(context.getString(R.string.search_cancelled))
    }

    /**
     * SearchError
     */
    private fun onSearchError(errorCode: Int) {
        isSearching = false
        progressDialog?.dismiss()
        progressDialog = null
        notification.showError("Search failed: $errorCode")
    }

    /**
     * CancelSearch
     */
    private fun cancelSearch() {
        if (isSearching) {
            // 通过共享Memory请求Cancel（零延迟）
            SearchEngine.requestCancelViaBuffer()
            // 也通过 CancellationToken 请求Cancel
            SearchEngine.requestCancel()
        }
    }

    /**
     * 隐藏进度对话框（但保持Search继续）
     * 用于退出全屏时隐藏进度 UI
     */
    fun hideProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    /**
     * 如果正在Search，重新显示进度对话框
     * 用于重新进入全屏时Restore进度 UI
     */
    fun showProgressDialogIfSearching() {
        if (isSearching && progressDialog == null) {
            showProgressDialog(isRefineSearch = !isInitialMode)
        }
    }

    /**
     * 如果Search已Completed且有结果，重新显示主对话框
     * 用于重新进入全屏时Restore对话框，让用户继续细化
     */
    fun showDialogIfSearchCompleted() {
        if (shouldShowAfterFullscreen) {
            shouldShowAfterFullscreen = false
            show()
        }
    }

    fun release() {
        progressDialog?.dismiss()
        progressDialog = null
        searchScope.cancel()
    }

    override fun dismiss() {
        // 清除Restore显示标志
        shouldShowAfterFullscreen = false
        Log.d(TAG, "这里销毁dialog，不会再次显示了! $this")

        if (!isSearching) {
            release()
        }
        super.dismiss()
    }
}
