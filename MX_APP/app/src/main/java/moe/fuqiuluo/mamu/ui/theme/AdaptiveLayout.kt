package moe.fuqiuluo.mamu.ui.theme

import android.content.res.Resources
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Density覆盖检测结果
 */
data class DensityOverrideInfo(
    val physicalDensity: Float,
    val currentDensity: Float,
    val isOverridden: Boolean,
    val overrideRatio: Float
)

/**
 * 屏幕适配信息
 */
data class AdaptiveLayoutInfo(
    val windowSizeClass: WindowSizeClass,
    val contentMaxWidth: Dp,
    val scaleFactor: Float,
    val densityOverride: DensityOverrideInfo,
    val screenWidthDp: Int
)

/**
 * 检测density是否被覆盖
 */
private fun detectDensityOverride(resources: Resources): DensityOverrideInfo {
    val displayMetrics = resources.displayMetrics
    val currentDensity = displayMetrics.density

    val widthPixels = displayMetrics.widthPixels
    val calculatedWidthDp = widthPixels / currentDensity

    val isOverridden = calculatedWidthDp > 380 && widthPixels < 1200

    val estimatedPhysicalDensity = if (isOverridden) {
        widthPixels / 300f
    } else {
        currentDensity
    }

    val overrideRatio = if (isOverridden) {
        currentDensity / estimatedPhysicalDensity
    } else {
        1.0f
    }

    return DensityOverrideInfo(
        physicalDensity = estimatedPhysicalDensity,
        currentDensity = currentDensity,
        isOverridden = isOverridden,
        overrideRatio = overrideRatio
    )
}

/**
 * 计算屏幕适配信息
 */
@Composable
fun rememberAdaptiveLayoutInfo(windowSizeClass: WindowSizeClass): AdaptiveLayoutInfo {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    return remember(windowSizeClass, configuration.screenWidthDp) {
        val densityOverride = detectDensityOverride(context.resources)
        val screenWidthDp = configuration.screenWidthDp

        val contentMaxWidth = when (windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Compact -> minOf(screenWidthDp.dp, 420.dp)
            WindowWidthSizeClass.Medium -> 600.dp
            WindowWidthSizeClass.Expanded -> 840.dp
            else -> 420.dp
        }

        // 基准宽度：392dp（常见手机宽度）
        // 如果屏幕更窄，按比例缩小
        val scaleFactor = when {
            screenWidthDp < 392 -> (screenWidthDp / 392f).coerceIn(0.75f, 1.0f)
            else -> 1.0f
        }

        AdaptiveLayoutInfo(
            windowSizeClass = windowSizeClass,
            contentMaxWidth = contentMaxWidth,
            scaleFactor = scaleFactor,
            densityOverride = densityOverride,
            screenWidthDp = screenWidthDp
        )
    }
}

/**
 * 响应式尺寸系统
 *
 * 根据屏幕宽度动态调整所有 dp 值
 * 基准屏幕宽度：392dp
 * 窄屏（<392dp）：按比例缩小
 */
object Dimens {

    /** 2.dp 基准 */
    fun spacingXxs(layoutInfo: AdaptiveLayoutInfo): Dp =
        (2f * layoutInfo.scaleFactor).dp

    /** 4.dp 基准 */
    fun spacingXs(layoutInfo: AdaptiveLayoutInfo): Dp =
        (4f * layoutInfo.scaleFactor).dp

    /** 8.dp 基准 */
    fun spacingSm(layoutInfo: AdaptiveLayoutInfo): Dp =
        (8f * layoutInfo.scaleFactor).dp

    /** 12.dp 基准 */
    fun spacingMd(layoutInfo: AdaptiveLayoutInfo): Dp =
        (12f * layoutInfo.scaleFactor).dp

    /** 16.dp 基准 */
    fun spacingLg(layoutInfo: AdaptiveLayoutInfo): Dp =
        (16f * layoutInfo.scaleFactor).dp

    /** 20.dp 基准 */
    fun spacingXl(layoutInfo: AdaptiveLayoutInfo): Dp =
        (20f * layoutInfo.scaleFactor).dp

    /** 24.dp 基准 */
    fun spacingXxl(layoutInfo: AdaptiveLayoutInfo): Dp =
        (24f * layoutInfo.scaleFactor).dp

    /** 32.dp 基准 */
    fun spacingXxxl(layoutInfo: AdaptiveLayoutInfo): Dp =
        (32f * layoutInfo.scaleFactor).dp

    /** 4.dp 基准 */
    fun paddingXs(layoutInfo: AdaptiveLayoutInfo): Dp =
        (4f * layoutInfo.scaleFactor).dp

    /** 8.dp 基准 */
    fun paddingSm(layoutInfo: AdaptiveLayoutInfo): Dp =
        (8f * layoutInfo.scaleFactor).dp

    /** 12.dp 基准 */
    fun paddingMd(layoutInfo: AdaptiveLayoutInfo): Dp =
        (12f * layoutInfo.scaleFactor).dp

    /** 16.dp 基准 */
    fun paddingLg(layoutInfo: AdaptiveLayoutInfo): Dp =
        (16f * layoutInfo.scaleFactor).dp

    /** 20.dp 基准 */
    fun paddingXl(layoutInfo: AdaptiveLayoutInfo): Dp =
        (20f * layoutInfo.scaleFactor).dp

    /** 24.dp 基准 */
    fun paddingXxl(layoutInfo: AdaptiveLayoutInfo): Dp =
        (24f * layoutInfo.scaleFactor).dp

    /** 32.dp 基准 */
    fun paddingXxxl(layoutInfo: AdaptiveLayoutInfo): Dp =
        (32f * layoutInfo.scaleFactor).dp

    /** 16.dp 基准 */
    fun iconXs(layoutInfo: AdaptiveLayoutInfo): Dp =
        (16f * layoutInfo.scaleFactor).dp

    /** 18.dp 基准 */
    fun iconSm(layoutInfo: AdaptiveLayoutInfo): Dp =
        (18f * layoutInfo.scaleFactor).dp

    /** 24.dp 基准 */
    fun iconMd(layoutInfo: AdaptiveLayoutInfo): Dp =
        (24f * layoutInfo.scaleFactor).dp

    /** 32.dp 基准 */
    fun iconLg(layoutInfo: AdaptiveLayoutInfo): Dp =
        (32f * layoutInfo.scaleFactor).dp

    /** 48.dp 基准 */
    fun iconXl(layoutInfo: AdaptiveLayoutInfo): Dp =
        (48f * layoutInfo.scaleFactor).dp

    /** 64.dp 基准 */
    fun iconXxl(layoutInfo: AdaptiveLayoutInfo): Dp =
        (64f * layoutInfo.scaleFactor).dp

    /** 2.dp 基准 */
    fun elevationSm(layoutInfo: AdaptiveLayoutInfo): Dp =
        (2f * layoutInfo.scaleFactor).dp

    /** 3.dp 基准 */
    fun elevationMd(layoutInfo: AdaptiveLayoutInfo): Dp =
        (3f * layoutInfo.scaleFactor).dp

    /** 2.dp 基准 */
    fun strokeSm(layoutInfo: AdaptiveLayoutInfo): Dp =
        (2f * layoutInfo.scaleFactor).dp

    /** 3.dp 基准 */
    fun strokeMd(layoutInfo: AdaptiveLayoutInfo): Dp =
        (3f * layoutInfo.scaleFactor).dp

    /** 4.dp 基准 */
    fun strokeLg(layoutInfo: AdaptiveLayoutInfo): Dp =
        (4f * layoutInfo.scaleFactor).dp

    /** 40.dp 基准 - 小按钮/头像 */
    fun componentSm(layoutInfo: AdaptiveLayoutInfo): Dp =
        (40f * layoutInfo.scaleFactor).dp

    /** 48.dp 基准 - 标准按钮高度 */
    fun componentMd(layoutInfo: AdaptiveLayoutInfo): Dp =
        (48f * layoutInfo.scaleFactor).dp

    /** 56.dp 基准 - 大按钮 */
    fun componentLg(layoutInfo: AdaptiveLayoutInfo): Dp =
        (56f * layoutInfo.scaleFactor).dp

    /** 400.dp 基准 - 对话框最大高度等 */
    fun maxHeightLg(layoutInfo: AdaptiveLayoutInfo): Dp =
        (400f * layoutInfo.scaleFactor).dp

    /** 20.dp 基准 - 步骤序号宽度 */
    fun stepNumberWidth(layoutInfo: AdaptiveLayoutInfo): Dp =
        (20f * layoutInfo.scaleFactor).dp

    /** 将任意 dp 值按比例缩放 */
    fun scaled(layoutInfo: AdaptiveLayoutInfo, baseDp: Dp): Dp =
        (baseDp.value * layoutInfo.scaleFactor).dp

    /** 将任意 Float dp 值按比例缩放 */
    fun scaled(layoutInfo: AdaptiveLayoutInfo, baseDpValue: Float): Dp =
        (baseDpValue * layoutInfo.scaleFactor).dp
}

/**
 * 响应式尺寸辅助类（兼容旧代码）
 */
@Deprecated("ResponsiveDimensions不再更新支持")
object ResponsiveDimensions {
    fun screenPadding(layoutInfo: AdaptiveLayoutInfo): Dp =
        Dimens.paddingLg(layoutInfo)

    fun itemSpacing(layoutInfo: AdaptiveLayoutInfo): Dp =
        Dimens.spacingMd(layoutInfo)

    fun iconSize(layoutInfo: AdaptiveLayoutInfo, baseSize: Dp = 24.dp): Dp =
        Dimens.scaled(layoutInfo, baseSize)

    fun buttonHeight(layoutInfo: AdaptiveLayoutInfo): Dp =
        Dimens.componentMd(layoutInfo)

    fun cardCornerRadius(layoutInfo: AdaptiveLayoutInfo): Dp =
        Dimens.spacingMd(layoutInfo)

    fun smallSpacing(layoutInfo: AdaptiveLayoutInfo): Dp =
        Dimens.spacingSm(layoutInfo)
}

/**
 * 判断是否为横屏布局
 */
fun AdaptiveLayoutInfo.isLandscape(): Boolean {
    return windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact
}
