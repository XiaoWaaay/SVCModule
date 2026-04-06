package moe.fuqiuluo.mamu.ui.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import moe.fuqiuluo.mamu.R

/**
 * 应用主题枚举
 */
enum class AppTheme(
    @param:StringRes val displayNameRes: Int,
    @param:StringRes val descriptionRes: Int,
    val primaryLight: Color,
    val secondaryLight: Color,
    val tertiaryLight: Color,
    val primaryDark: Color,
    val secondaryDark: Color,
    val tertiaryDark: Color
) {
    MONOCHROME(
        displayNameRes = R.string.theme_monochrome,
        descriptionRes = R.string.theme_monochrome_desc,
        primaryLight = MonochromeColors.primary40,
        secondaryLight = MonochromeColors.secondary40,
        tertiaryLight = MonochromeColors.tertiary40,
        primaryDark = MonochromeColors.primary80,
        secondaryDark = MonochromeColors.secondary80,
        tertiaryDark = MonochromeColors.tertiary80
    ),

    INDIGO(
        displayNameRes = R.string.theme_indigo,
        descriptionRes = R.string.theme_indigo_desc,
        primaryLight = IndigoColors.primary40,
        secondaryLight = IndigoColors.secondary40,
        tertiaryLight = IndigoColors.tertiary40,
        primaryDark = IndigoColors.primary80,
        secondaryDark = IndigoColors.secondary80,
        tertiaryDark = IndigoColors.tertiary80
    );

    companion object {
        fun fromName(name: String?): AppTheme {
            if (name == null) {
                return MONOCHROME
            }
            return entries.find { it.name == name } ?: MONOCHROME
        }
    }
}