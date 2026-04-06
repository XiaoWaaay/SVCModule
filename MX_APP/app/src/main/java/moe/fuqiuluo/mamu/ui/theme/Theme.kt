package moe.fuqiuluo.mamu.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext

/**
 * 根据AppTheme创建深色ColorScheme
 */
private fun createDarkColorScheme(theme: AppTheme): ColorScheme {
    return darkColorScheme(
        primary = theme.primaryDark,
        secondary = theme.secondaryDark,
        tertiary = theme.tertiaryDark,
        background = BlackBackground,
        surface = DarkSurface,
        surfaceVariant = DarkerSurface,
    )
}

/**
 * 根据AppTheme创建浅色ColorScheme
 */
private fun createLightColorScheme(theme: AppTheme): ColorScheme {
    return lightColorScheme(
        primary = theme.primaryLight,
        secondary = theme.secondaryLight,
        tertiary = theme.tertiaryLight
    )
}

@Composable
fun MXTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemInDarkTheme = isSystemInDarkTheme()

    val currentTheme by ThemeManager.currentTheme.collectAsState()
    val useDynamicColor by ThemeManager.useDynamicColor.collectAsState()
    val darkMode by ThemeManager.darkMode.collectAsState()

    val darkTheme = when (darkMode) {
        DarkMode.FOLLOW_SYSTEM -> systemInDarkTheme
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
    }

    val colorScheme = when {
        // 使用动态颜色（Android 12+）
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) {
                // 动态颜色 + 纯黑背景
                dynamicDarkColorScheme(context).copy(
                    background = BlackBackground,
                    surface = DarkSurface,
                    surfaceVariant = DarkerSurface
                )
            } else {
                dynamicLightColorScheme(context)
            }
        }
        // 使用Custom主题颜色
        darkTheme -> createDarkColorScheme(currentTheme)
        else -> createLightColorScheme(currentTheme)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}