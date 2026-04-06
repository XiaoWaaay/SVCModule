package moe.fuqiuluo.mamu.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec
import android.widget.LinearLayout
import kotlin.math.min
import moe.fuqiuluo.mamu.R
import androidx.core.content.withStyledAttributes

class FixedLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var maxHeightPx: Int = Int.MAX_VALUE
    private var maxHeightPercent: Float = -1f

    private var maxWidthPx: Int = Int.MAX_VALUE
    private var maxWidthPercent: Float = -1f

    init {
        attrs?.let {
            context.withStyledAttributes(it, R.styleable.FixedLinearLayout) {
                maxHeightPx =
                    getDimensionPixelSize(R.styleable.FixedLinearLayout_maxHeight, Int.MAX_VALUE)
                maxHeightPercent = getFloat(R.styleable.FixedLinearLayout_maxHeightPercent, -1f)
                maxWidthPx =
                    getDimensionPixelSize(R.styleable.FixedLinearLayout_maxWidth, Int.MAX_VALUE)
                maxWidthPercent = getFloat(R.styleable.FixedLinearLayout_maxWidthPercent, -1f)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cappedWidthSpec = capSpec(
            isWidth = true,
            originalSpec = widthMeasureSpec,
            maxPx = maxWidthPx,
            maxPercent = maxWidthPercent
        )
        val cappedHeightSpec = capSpec(
            isWidth = false,
            originalSpec = heightMeasureSpec,
            maxPx = maxHeightPx,
            maxPercent = maxHeightPercent
        )
        super.onMeasure(cappedWidthSpec, cappedHeightSpec)
    }

    private fun capSpec(
        isWidth: Boolean,
        originalSpec: Int,
        maxPx: Int,
        maxPercent: Float
    ): Int {
        val mode = MeasureSpec.getMode(originalSpec)
        val size = MeasureSpec.getSize(originalSpec)

        // 没有设置上限，直接返回
        if (maxPx == Int.MAX_VALUE && (maxPercent !in 0f..1f)) {
            return originalSpec
        }

        val dm = resources.displayMetrics
        var cap = maxPx
        if (maxPercent in 0f..1f) {
            val percentPx =
                ((if (isWidth) dm.widthPixels else dm.heightPixels) * maxPercent).toInt()
            cap = min(cap, percentPx)
        }

        // 如果算出来的上限还是“无限大”，也直接返回
        if (cap == Int.MAX_VALUE) return originalSpec

        val newSize = min(size, cap)

        return when (mode) {
            MeasureSpec.UNSPECIFIED -> {
                // 父不限制，直接沿用
                originalSpec
            }

            MeasureSpec.AT_MOST -> {
                // 把“最多 size”收紧为“最多 newSize”
                MeasureSpec.makeMeasureSpec(newSize, MeasureSpec.AT_MOST)
            }

            MeasureSpec.EXACTLY -> {
                // 父要求精确 size
                // - 如果父给的 size 本来就 <= cap，则尊重父（不改），避免破坏父布局契约
                // - 如果父给的 size > cap，则改成 “AT_MOST cap”，允许内容 <= cap，避免强行铺满导致留白
                if (size <= cap) {
                    originalSpec
                } else {
                    MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
                }
            }

            else -> originalSpec
        }
    }
}