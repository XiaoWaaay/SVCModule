//! JNI methods for FreezeManager

use jni::objects::{JByteArray, JObject};
use jni::sys::{jboolean, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use jni_macro::jni_method;
use log::error;

use crate::core::globals::{FREEZE_MANAGER, TOKIO_RUNTIME};

/// 启动冻结循环
#[jni_method(70, "moe/fuqiuluo/mamu/driver/FreezeManager", "nativeStart", "()V")]
pub fn jni_freeze_start(_env: JNIEnv, _obj: JObject) {
    let _guard = TOKIO_RUNTIME.enter();

    match FREEZE_MANAGER.write() {
        Ok(mut manager) => {
            manager.start();
        },
        Err(e) => {
            error!("FreezeManager JNI: 无法获取写锁: {}", e);
        },
    }
}

/// 停止冻结循环
#[jni_method(70, "moe/fuqiuluo/mamu/driver/FreezeManager", "nativeStop", "()V")]
pub fn jni_freeze_stop(_env: JNIEnv, _obj: JObject) {
    match FREEZE_MANAGER.write() {
        Ok(mut manager) => {
            manager.stop();
        },
        Err(e) => {
            error!("FreezeManager JNI: 无法获取写锁: {}", e);
        },
    }
}

/// 添加冻结地址
#[jni_method(70, "moe/fuqiuluo/mamu/driver/FreezeManager", "nativeAddFrozen", "(J[BI)Z")]
pub fn jni_freeze_add(mut env: JNIEnv, _obj: JObject, address: jlong, value: JByteArray, value_type: jint) -> jboolean {
    let len = match env.get_array_length(&value) {
        Ok(l) => l as usize,
        Err(e) => {
            error!("FreezeManager JNI: 获取数组长度失败: {}", e);
            return JNI_FALSE;
        },
    };

    let mut buffer = vec![0i8; len];
    if let Err(e) = env.get_byte_array_region(&value, 0, &mut buffer) {
        error!("FreezeManager JNI: 读取字节数组失败: {}", e);
        return JNI_FALSE;
    }

    // 转换为 u8
    let value_bytes: Vec<u8> = buffer.iter().map(|&b| b as u8).collect();

    match FREEZE_MANAGER.read() {
        Ok(manager) => {
            manager.add_frozen(address as u64, value_bytes, value_type);
            JNI_TRUE
        },
        Err(e) => {
            error!("FreezeManager JNI: 无法获取读锁: {}", e);
            JNI_FALSE
        },
    }
}

/// 移除冻结地址
#[jni_method(70, "moe/fuqiuluo/mamu/driver/FreezeManager", "nativeRemoveFrozen", "(J)Z")]
pub fn jni_freeze_remove(_env: JNIEnv, _obj: JObject, address: jlong) -> jboolean {
    match FREEZE_MANAGER.read() {
        Ok(manager) => {
            if manager.remove_frozen(address as u64) {
                JNI_TRUE
            } else {
                JNI_FALSE
            }
        },
        Err(e) => {
            error!("FreezeManager JNI: 无法获取读锁: {}", e);
            JNI_FALSE
        },
    }
}

/// 清空所有冻结
#[jni_method(70, "moe/fuqiuluo/mamu/driver/FreezeManager", "nativeClearAll", "()V")]
pub fn jni_freeze_clear_all(_env: JNIEnv, _obj: JObject) {
    match FREEZE_MANAGER.read() {
        Ok(manager) => {
            manager.clear_all();
        },
        Err(e) => {
            error!("FreezeManager JNI: 无法获取读锁: {}", e);
        },
    }
}

/// 设置冻结间隔（微秒）
#[jni_method(70, "moe/fuqiuluo/mamu/driver/FreezeManager", "nativeSetInterval", "(J)V")]
pub fn jni_freeze_set_interval(_env: JNIEnv, _obj: JObject, microseconds: jlong) {
    match FREEZE_MANAGER.read() {
        Ok(manager) => {
            manager.set_interval(microseconds as u64);
        },
        Err(e) => {
            error!("FreezeManager JNI: 无法获取读锁: {}", e);
        },
    }
}

/// 获取冻结数量
#[jni_method(70, "moe/fuqiuluo/mamu/driver/FreezeManager", "nativeGetFrozenCount", "()I")]
pub fn jni_freeze_get_count(_env: JNIEnv, _obj: JObject) -> jint {
    match FREEZE_MANAGER.read() {
        Ok(manager) => manager.get_frozen_count() as jint,
        Err(e) => {
            error!("FreezeManager JNI: 无法获取读锁: {}", e);
            0
        },
    }
}

/// 检查地址是否被冻结
#[jni_method(70, "moe/fuqiuluo/mamu/driver/FreezeManager", "nativeIsFrozen", "(J)Z")]
pub fn jni_freeze_is_frozen(_env: JNIEnv, _obj: JObject, address: jlong) -> jboolean {
    match FREEZE_MANAGER.read() {
        Ok(manager) => {
            if manager.is_frozen(address as u64) {
                JNI_TRUE
            } else {
                JNI_FALSE
            }
        },
        Err(e) => {
            error!("FreezeManager JNI: 无法获取读锁: {}", e);
            JNI_FALSE
        },
    }
}
