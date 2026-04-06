package moe.fuqiuluo.mamu.data.model

enum class SeLinuxMode {
    ENFORCING,
    PERMISSIVE,
    DISABLED,
    UNKNOWN
}

data class SeLinuxStatus(
    val mode: SeLinuxMode,
    val modeString: String
)