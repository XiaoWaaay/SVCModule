package moe.fuqiuluo.mamu.widget

import androidx.annotation.DrawableRes

data class ToolbarAction(
    val id: Int,
    @DrawableRes val icon: Int,
    val label: String,
    val onClick: () -> Unit
)
