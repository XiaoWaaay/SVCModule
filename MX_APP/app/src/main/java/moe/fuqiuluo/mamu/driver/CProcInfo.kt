package moe.fuqiuluo.mamu.driver

data class CProcInfo(
    val pid: Int,
    val tgid: Int,
    val name: String,
    val uid: Int,
    val ppid: Int,
    val prio: Int,
    val rss: Long
)