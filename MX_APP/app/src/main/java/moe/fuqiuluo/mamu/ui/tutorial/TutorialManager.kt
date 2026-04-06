package moe.fuqiuluo.mamu.ui.tutorial

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.fuqiuluo.mamu.utils.PreviewSafeMMKV

/**
 * 新手教程管理器 - 管理教程Completed状态
 */
object TutorialManager {
    private const val MMKV_ID = "tutorial_config"
    private const val KEY_TUTORIAL_COMPLETED = "tutorial_completed"
    private const val KEY_TUTORIAL_VERSION = "tutorial_version"

    // 当前教程版本，如果教程内容更新，增加此版本号可以让用户再次看To教程
    private const val CURRENT_TUTORIAL_VERSION = 1

    private val mmkv by lazy {
        PreviewSafeMMKV.mmkvWithID(MMKV_ID)
    }

    private val _shouldShowTutorial = MutableStateFlow(checkShouldShowTutorial())
    val shouldShowTutorial: StateFlow<Boolean> = _shouldShowTutorial.asStateFlow()

    /**
     * 标记教程已Completed
     */
    fun completeTutorial() {
        mmkv.encode(KEY_TUTORIAL_COMPLETED, true)
        mmkv.encode(KEY_TUTORIAL_VERSION, CURRENT_TUTORIAL_VERSION)
        _shouldShowTutorial.value = false
    }

    /**
     * Reset教程状态（用于测试或重新显示教程）
     */
    fun resetTutorial() {
        mmkv.encode(KEY_TUTORIAL_COMPLETED, false)
        mmkv.encode(KEY_TUTORIAL_VERSION, 0)
        _shouldShowTutorial.value = true
    }

    /**
     * 检查是否需要显示教程
     */
    private fun checkShouldShowTutorial(): Boolean {
        val completed = mmkv.decodeBool(KEY_TUTORIAL_COMPLETED, false)
        val version = mmkv.decodeInt(KEY_TUTORIAL_VERSION, 0)

        // 如果教程未Completed，或者教程版本过旧，则显示教程
        return !completed || version < CURRENT_TUTORIAL_VERSION
    }

    /**
     * 跳过本次教程显示（不标记为Completed，下次启动仍会显示）
     */
    fun dismissTutorial() {
        _shouldShowTutorial.value = false
    }
}