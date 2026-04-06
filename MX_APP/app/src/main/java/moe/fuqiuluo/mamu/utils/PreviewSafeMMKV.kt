package moe.fuqiuluo.mamu.utils

import com.tencent.mmkv.MMKV

/**
 * Preview 安全的 MMKV 包装类
 * 在 Preview 环境下返回默认值，避免 MMKV 未初始化的问题
 */
class PreviewSafeMMKV private constructor(private val mmkv: MMKV?) {

    fun encode(key: String, value: String): Boolean {
        return mmkv?.encode(key, value) ?: false
    }

    fun encode(key: String, value: Boolean): Boolean {
        return mmkv?.encode(key, value) ?: false
    }

    fun encode(key: String, value: Int): Boolean {
        return mmkv?.encode(key, value) ?: false
    }

    fun encode(key: String, value: Long): Boolean {
        return mmkv?.encode(key, value) ?: false
    }

    fun encode(key: String, value: Float): Boolean {
        return mmkv?.encode(key, value) ?: false
    }

    fun decodeString(key: String, defaultValue: String?): String? {
        return mmkv?.decodeString(key, defaultValue) ?: defaultValue
    }

    fun decodeBool(key: String, defaultValue: Boolean): Boolean {
        return mmkv?.decodeBool(key, defaultValue) ?: defaultValue
    }

    fun decodeInt(key: String, defaultValue: Int): Int {
        return mmkv?.decodeInt(key, defaultValue) ?: defaultValue
    }

    fun decodeLong(key: String, defaultValue: Long): Long {
        return mmkv?.decodeLong(key, defaultValue) ?: defaultValue
    }

    fun decodeFloat(key: String, defaultValue: Float): Float {
        return mmkv?.decodeFloat(key, defaultValue) ?: defaultValue
    }

    fun remove(key: String) {
        mmkv?.remove(key)
    }

    fun clearAll() {
        mmkv?.clearAll()
    }

    fun containsKey(key: String): Boolean {
        return mmkv?.containsKey(key) ?: false
    }

    companion object {
        /**
         * 获取 Preview 安全的 MMKV 实例
         */
        fun mmkvWithID(id: String): PreviewSafeMMKV {
            val mmkv = try {
                MMKV.mmkvWithID(id)
            } catch (e: Exception) {
                // Preview 环境下 MMKV 未初始化
                null
            }
            return PreviewSafeMMKV(mmkv)
        }

        /**
         * 获取默认的 Preview 安全 MMKV 实例
         */
        fun defaultMMKV(): PreviewSafeMMKV {
            val mmkv = try {
                MMKV.defaultMMKV()
            } catch (e: Exception) {
                null
            }
            return PreviewSafeMMKV(mmkv)
        }
    }
}