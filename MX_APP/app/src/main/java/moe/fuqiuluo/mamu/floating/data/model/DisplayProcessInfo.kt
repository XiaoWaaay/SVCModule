package moe.fuqiuluo.mamu.floating.data.model

import android.graphics.drawable.Drawable

data class DisplayProcessInfo(
    val icon: Drawable,
    val name: String?,
    val packageName: String?,
    val pid: Int,
    val uid: Int,
    val cmdline: String,
    val rss: Long,
    val prio: Int
) {
    val validName: String
        get() = name ?: cmdline

    val validPackageName: String
        get() = packageName ?: cmdline.split(":").first()
}