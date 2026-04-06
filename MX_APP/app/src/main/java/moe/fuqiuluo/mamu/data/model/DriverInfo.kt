package moe.fuqiuluo.mamu.data.model

/**
 * 驱动信息数据类
 * @property name 驱动名称 (e.g., "android14-6.1")
 * @property displayName 显示名称
 * @property installed 是否已安装
 */
data class DriverInfo(
    val name: String,
    val displayName: String,
    val installed: Boolean = false
)

/**
 * 驱动安装结果
 * @property success 是否成功
 * @property message 结果Message或Error日志
 */
data class DriverInstallResult(
    val success: Boolean,
    val message: String
)
