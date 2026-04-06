package moe.fuqiuluo.mamu.utils

/**
 * Root配置管理器
 * 用于存储和获取Custom的root检查命令
 */
object RootConfigManager {
    private const val MMKV_ID = "root_config"
    private const val KEY_CUSTOM_ROOT_COMMAND = "custom_root_command"
    const val DEFAULT_ROOT_COMMAND = "su"

    private val mmkv by lazy {
        PreviewSafeMMKV.mmkvWithID(MMKV_ID)
    }

    /**
     * 获取Customroot检查命令
     * @return Custom命令，如果未设置则返回默认命令 "su"
     */
    fun getCustomRootCommand(): String {
        return mmkv.decodeString(KEY_CUSTOM_ROOT_COMMAND, DEFAULT_ROOT_COMMAND) ?: DEFAULT_ROOT_COMMAND
    }

    /**
     * 设置Customroot检查命令
     * @param command Custom命令
     */
    fun setCustomRootCommand(command: String) {
        mmkv.encode(KEY_CUSTOM_ROOT_COMMAND, command)
    }

    /**
     * Reset为默认root检查命令
     */
    fun resetToDefault() {
        mmkv.encode(KEY_CUSTOM_ROOT_COMMAND, DEFAULT_ROOT_COMMAND)
    }
}