package moe.fuqiuluo.mamu.ui.theme

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.fuqiuluo.mamu.utils.PreviewSafeMMKV

enum class DarkMode {
    FOLLOW_SYSTEM,
    LIGHT,
    DARK
}

object ThemeManager {
    private const val MMKV_ID = "theme_config"
    private const val KEY_THEME = "app_theme"
    private const val KEY_DYNAMIC_COLOR = "use_dynamic_color"
    private const val KEY_DARK_MODE = "dark_mode"

    private val mmkv by lazy {
        PreviewSafeMMKV.mmkvWithID(MMKV_ID)
    }

    private val _currentTheme = MutableStateFlow(loadTheme())
    val currentTheme: StateFlow<AppTheme> = _currentTheme.asStateFlow()

    private val _useDynamicColor = MutableStateFlow(loadDynamicColorPreference())
    val useDynamicColor: StateFlow<Boolean> = _useDynamicColor.asStateFlow()

    private val _darkMode = MutableStateFlow(loadDarkMode())
    val darkMode: StateFlow<DarkMode> = _darkMode.asStateFlow()

    fun setTheme(theme: AppTheme) {
        mmkv.encode(KEY_THEME, theme.name)
        _currentTheme.value = theme
    }

    fun setUseDynamicColor(useDynamic: Boolean) {
        mmkv.encode(KEY_DYNAMIC_COLOR, useDynamic)
        _useDynamicColor.value = useDynamic
    }

    fun setDarkMode(mode: DarkMode) {
        mmkv.encode(KEY_DARK_MODE, mode.name)
        _darkMode.value = mode
    }

    private fun loadTheme(): AppTheme {
        val themeName = mmkv.decodeString(KEY_THEME, null)
        Log.d("ThemeManager", "Loaded theme from MMKV: $themeName")
        return AppTheme.fromName(themeName)
    }

    private fun loadDynamicColorPreference(): Boolean {
        return mmkv.decodeBool(KEY_DYNAMIC_COLOR, false)
    }

    private fun loadDarkMode(): DarkMode {
        val modeName = mmkv.decodeString(KEY_DARK_MODE, null)
        return try {
            modeName?.let { DarkMode.valueOf(it) } ?: DarkMode.FOLLOW_SYSTEM
        } catch (e: IllegalArgumentException) {
            DarkMode.FOLLOW_SYSTEM
        }
    }
}