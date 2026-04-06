@file:Suppress("KotlinJniMissingFunction")

package moe.fuqiuluo.mamu.driver

import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import moe.fuqiuluo.mamu.utils.ValueTypeUtils

/**
 * Memory值冻结管理器
 * 
 * 使用 Rust + tokio 实现高精度定时写入，将冻结的Address值持续写入目标进程Memory。
 */
object FreezeManager {
    
    init {
        System.loadLibrary("mamu_core")
    }
    
    /**
     * 启动冻结循环
     * 应在绑定进程后调用
     */
    fun start() {
        nativeStart()
    }
    
    /**
     * 停止冻结循环
     * 应在解绑进程前调用
     */
    @Deprecated("非必要情况下禁止调用")
    fun stop() {
        nativeStop()
    }
    
    /**
     * 添加冻结Address
     * 
     * @param address 要冻结的MemoryAddress
     * @param value 要写入的值（字节数组）
     * @param valueType 值类型 ID
     * @return 是否添加成功
     */
    fun addFrozen(address: Long, value: ByteArray, valueType: Int): Boolean {
        return nativeAddFrozen(address, value, valueType)
    }
    
    /**
     * 添加冻结Address（使用字符串值）
     * 
     * @param address 要冻结的MemoryAddress
     * @param valueStr 要写入的值（字符串形式）
     * @param valueType 值类型
     * @return 是否添加成功
     */
    fun addFrozen(address: Long, valueStr: String, valueType: DisplayValueType): Boolean {
        return try {
            val bytes = ValueTypeUtils.parseExprToBytes(valueStr, valueType)
            addFrozen(address, bytes, valueType.nativeId)
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 移除冻结Address
     * 
     * @param address 要解除冻结的MemoryAddress
     * @return 是否移除成功（Address不存在时返回 false）
     */
    fun removeFrozen(address: Long): Boolean {
        return nativeRemoveFrozen(address)
    }
    
    /**
     * 清空所有冻结
     */
    fun clearAll() {
        nativeClearAll()
    }
    
    /**
     * 设置冻结间隔
     * 
     * @param microseconds 间隔时间（微秒）
     */
    fun setInterval(microseconds: Long) {
        nativeSetInterval(microseconds)
    }
    
    /**
     * 获取当前冻结的Address数量
     */
    fun getFrozenCount(): Int {
        return nativeGetFrozenCount()
    }
    
    /**
     * 检查Address是否被冻结
     * 
     * @param address 要检查的Address
     * @return 是否被冻结
     */
    fun isFrozen(address: Long): Boolean {
        return nativeIsFrozen(address)
    }
    
    // Native methods
    private external fun nativeStart()
    private external fun nativeStop()
    private external fun nativeAddFrozen(address: Long, value: ByteArray, valueType: Int): Boolean
    private external fun nativeRemoveFrozen(address: Long): Boolean
    private external fun nativeClearAll()
    private external fun nativeSetInterval(microseconds: Long)
    private external fun nativeGetFrozenCount(): Int
    private external fun nativeIsFrozen(address: Long): Boolean
}
