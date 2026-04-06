//! Freeze Manager - 内存值冻结管理器
//!
//! 使用 tokio 实现高精度定时写入，将冻结的地址值持续写入目标进程内存。

use crate::core::globals::DRIVER_MANAGER;
use dashmap::DashMap;
use log::{debug, error, warn};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::Notify;
use tokio::task::JoinHandle;

/// 冻结条目
#[derive(Clone)]
pub struct FrozenEntry {
    /// 要写入的值（字节数组）
    pub value: Vec<u8>,
    /// 值类型 ID（用于调试/日志）
    pub value_type: i32,
}

/// 冻结管理器
pub struct FreezeManager {
    /// 冻结地址映射表：地址 -> 冻结条目
    frozen_entries: Arc<DashMap<u64, FrozenEntry>>,
    /// 冻结间隔（微秒）
    interval_us: Arc<AtomicU64>,
    /// 是否正在运行
    running: Arc<AtomicBool>,
    /// 用于通知任务停止
    stop_notify: Arc<Notify>,
    /// 后台任务句柄
    task_handle: Option<JoinHandle<()>>,
}

impl FreezeManager {
    pub fn new() -> Self {
        Self {
            frozen_entries: Arc::new(DashMap::new()),
            interval_us: Arc::new(AtomicU64::new(33000)), // 默认 33ms
            running: Arc::new(AtomicBool::new(false)),
            stop_notify: Arc::new(Notify::new()),
            task_handle: None,
        }
    }

    /// 启动冻结循环
    pub fn start(&mut self) {
        if self.running.load(Ordering::SeqCst) {
            return;
        }

        self.running.store(true, Ordering::SeqCst);

        let entries = Arc::clone(&self.frozen_entries);
        let interval_us = Arc::clone(&self.interval_us);
        let running = Arc::clone(&self.running);
        let stop_notify = Arc::clone(&self.stop_notify);

        let handle = tokio::spawn(async move {
            debug!("FreezeManager: 冻结循环已启动");

            loop {
                // 检查是否应该停止
                if !running.load(Ordering::SeqCst) {
                    break;
                }

                // 获取当前间隔
                let interval = Duration::from_micros(interval_us.load(Ordering::Relaxed));

                // 执行冻结写入
                if !entries.is_empty() {
                    Self::write_frozen_values(&entries);
                }

                // 等待间隔或停止信号
                tokio::select! {
                    _ = tokio::time::sleep(interval) => {},
                    _ = stop_notify.notified() => {
                        if !running.load(Ordering::SeqCst) {
                            break;
                        }
                    }
                }
            }

            debug!("FreezeManager: 冻结循环已停止");
        });

        self.task_handle = Some(handle);
    }

    /// 停止冻结循环
    pub fn stop(&mut self) {
        if !self.running.load(Ordering::SeqCst) {
            return;
        }

        self.running.store(false, Ordering::SeqCst);
        self.stop_notify.notify_one();

        // 等待任务结束
        if let Some(handle) = self.task_handle.take() {
            // 使用 block_on 等待，但设置超时避免死锁
            let _ = tokio::task::block_in_place(|| {
                tokio::runtime::Handle::current().block_on(async { tokio::time::timeout(Duration::from_secs(1), handle).await })
            });
        }
    }

    /// 写入所有冻结值
    fn write_frozen_values(entries: &DashMap<u64, FrozenEntry>) {
        let manager = match DRIVER_MANAGER.read() {
            Ok(m) => m,
            Err(e) => {
                error!("FreezeManager: 无法获取 DRIVER_MANAGER 读锁: {}", e);
                return;
            },
        };

        if !manager.is_process_bound() {
            return;
        }

        for entry in entries.iter() {
            let addr = *entry.key();
            let frozen = entry.value();

            if let Err(e) = manager.write_memory_unified(addr, &frozen.value) {
                warn!("FreezeManager: 写入地址 0x{:X} 失败: {}", addr, e);
            }
        }
    }

    /// 添加冻结地址
    pub fn add_frozen(&self, address: u64, value: Vec<u8>, value_type: i32) {
        debug!("FreezeManager: 添加冻结 addr=0x{:X}, type={}, len={}", address, value_type, value.len());
        self.frozen_entries.insert(address, FrozenEntry { value, value_type });
    }

    /// 移除冻结地址
    pub fn remove_frozen(&self, address: u64) -> bool {
        debug!("FreezeManager: 移除冻结 addr=0x{:X}", address);
        self.frozen_entries.remove(&address).is_some()
    }

    /// 清空所有冻结
    pub fn clear_all(&self) {
        debug!("FreezeManager: 清空所有冻结");
        self.frozen_entries.clear();
    }

    /// 设置冻结间隔（微秒）
    pub fn set_interval(&self, microseconds: u64) {
        debug!("FreezeManager: 设置间隔 {} μs", microseconds);
        self.interval_us.store(microseconds, Ordering::Relaxed);
    }

    /// 获取冻结数量
    pub fn get_frozen_count(&self) -> usize {
        self.frozen_entries.len()
    }

    /// 检查地址是否被冻结
    pub fn is_frozen(&self, address: u64) -> bool {
        self.frozen_entries.contains_key(&address)
    }

    /// 获取所有冻结的地址
    pub fn get_frozen_addresses(&self) -> Vec<u64> {
        self.frozen_entries.iter().map(|e| *e.key()).collect()
    }
}

impl Drop for FreezeManager {
    fn drop(&mut self) {
        self.stop();
    }
}
