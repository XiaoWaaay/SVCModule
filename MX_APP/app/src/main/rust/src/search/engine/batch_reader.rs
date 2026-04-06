use crate::core::DRIVER_MANAGER;
use crate::search::result_manager::FuzzySearchResultItem;
use crate::search::types::ValueType;
use crate::search::FuzzyCondition;
use anyhow::{anyhow, Result};
use log::{debug, log_enabled, Level};
use rayon::prelude::*;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;

/// 批量读取：最大合并间隙（4KB，即1页大小）
/// 当两个地址之间的间隙小于此值时，合并为同一批次
const BATCH_MAX_GAP: u64 = 4096;

/// 批量读取：单批次大小上限（64KB）
const BATCH_MAX_SIZE: usize = 64 * 1024;

/// 进度更新：每处理多少批次更新一次进度
const PROGRESS_UPDATE_BATCH_SIZE: usize = 1;

/// 读取结果项 - 使用固定大小数组避免 Vec 分配开销
/// 每个 FuzzySearchResultItem 最大值为 8 字节（Qword/Double）
#[derive(Clone, Copy)]
pub struct ReadResultItem {
    pub address: u64,
    pub value_type: ValueType,
    pub old_value: [u8; 8],    // 旧值
    pub current_value: [u8; 8], // 当前值
}

impl ReadResultItem {
    #[inline]
    pub fn new(item: &FuzzySearchResultItem, current: &[u8]) -> Self {
        let mut current_value = [0u8; 8];
        let len = current.len().min(8);
        current_value[..len].copy_from_slice(&current[..len]);
        
        Self {
            address: item.address,
            value_type: item.value_type,
            old_value: item.value,
            current_value,
        }
    }
    
    /// 获取当前值的有效字节切片
    #[inline]
    pub fn current_bytes(&self) -> &[u8] {
        &self.current_value[..self.value_type.size()]
    }
    
    /// 转换为 FuzzySearchResultItem（使用当前值）
    #[inline]
    pub fn to_fuzzy_item(&self) -> FuzzySearchResultItem {
        FuzzySearchResultItem::new(self.address, self.current_value, self.value_type)
    }
    
    /// 获取旧的 FuzzySearchResultItem（用于条件比较）
    #[inline]
    pub fn old_fuzzy_item(&self) -> FuzzySearchResultItem {
        FuzzySearchResultItem::new(self.address, self.old_value, self.value_type)
    }
    
    /// 直接在 ReadResultItem 上检查条件，避免创建临时对象
    #[inline]
    pub fn matches_condition(&self, condition: FuzzyCondition) -> bool {
        if self.value_type.is_float_type() {
            self.matches_condition_float(condition)
        } else {
            self.matches_condition_int(condition)
        }
    }
    
    #[inline]
    fn old_as_i64(&self) -> i64 {
        match self.value_type {
            ValueType::Byte => self.old_value[0] as i8 as i64,
            ValueType::Word => i16::from_le_bytes([self.old_value[0], self.old_value[1]]) as i64,
            ValueType::Dword | ValueType::Auto | ValueType::Xor => {
                i32::from_le_bytes([self.old_value[0], self.old_value[1], self.old_value[2], self.old_value[3]]) as i64
            },
            ValueType::Qword => i64::from_le_bytes(self.old_value),
            ValueType::Float => {
                f32::from_le_bytes([self.old_value[0], self.old_value[1], self.old_value[2], self.old_value[3]]) as i64
            },
            ValueType::Double => f64::from_le_bytes(self.old_value) as i64,
            ValueType::Pattern => 0, // Pattern 类型不支持模糊搜索
        }
    }
    
    #[inline]
    fn current_as_i64(&self) -> i64 {
        match self.value_type {
            ValueType::Byte => self.current_value[0] as i8 as i64,
            ValueType::Word => i16::from_le_bytes([self.current_value[0], self.current_value[1]]) as i64,
            ValueType::Dword | ValueType::Auto | ValueType::Xor => {
                i32::from_le_bytes([self.current_value[0], self.current_value[1], self.current_value[2], self.current_value[3]]) as i64
            },
            ValueType::Qword => i64::from_le_bytes(self.current_value),
            ValueType::Float => {
                f32::from_le_bytes([self.current_value[0], self.current_value[1], self.current_value[2], self.current_value[3]]) as i64
            },
            ValueType::Double => f64::from_le_bytes(self.current_value) as i64,
            ValueType::Pattern => 0, // Pattern 类型不支持模糊搜索
        }
    }
    
    #[inline]
    fn old_as_f64(&self) -> f64 {
        match self.value_type {
            ValueType::Byte => self.old_value[0] as i8 as f64,
            ValueType::Word => i16::from_le_bytes([self.old_value[0], self.old_value[1]]) as f64,
            ValueType::Dword | ValueType::Auto | ValueType::Xor => {
                i32::from_le_bytes([self.old_value[0], self.old_value[1], self.old_value[2], self.old_value[3]]) as f64
            },
            ValueType::Qword => i64::from_le_bytes(self.old_value) as f64,
            ValueType::Float => {
                f32::from_le_bytes([self.old_value[0], self.old_value[1], self.old_value[2], self.old_value[3]]) as f64
            },
            ValueType::Double => f64::from_le_bytes(self.old_value),
            ValueType::Pattern => 0.0, // Pattern 类型不支持模糊搜索
        }
    }
    
    #[inline]
    fn current_as_f64(&self) -> f64 {
        match self.value_type {
            ValueType::Byte => self.current_value[0] as i8 as f64,
            ValueType::Word => i16::from_le_bytes([self.current_value[0], self.current_value[1]]) as f64,
            ValueType::Dword | ValueType::Auto | ValueType::Xor => {
                i32::from_le_bytes([self.current_value[0], self.current_value[1], self.current_value[2], self.current_value[3]]) as f64
            },
            ValueType::Qword => i64::from_le_bytes(self.current_value) as f64,
            ValueType::Float => {
                f32::from_le_bytes([self.current_value[0], self.current_value[1], self.current_value[2], self.current_value[3]]) as f64
            },
            ValueType::Double => f64::from_le_bytes(self.current_value),
            ValueType::Pattern => 0.0, // Pattern 类型不支持模糊搜索
        }
    }
    
    #[inline]
    fn matches_condition_int(&self, condition: FuzzyCondition) -> bool {
        let old_val = self.old_as_i64();
        let new_val = self.current_as_i64();
        let diff = new_val.wrapping_sub(old_val);

        match condition {
            FuzzyCondition::Initial => true,
            FuzzyCondition::Unchanged => old_val == new_val,
            FuzzyCondition::Changed => old_val != new_val,
            FuzzyCondition::Increased => new_val > old_val,
            FuzzyCondition::Decreased => new_val < old_val,
            FuzzyCondition::IncreasedBy(amount) => diff == amount,
            FuzzyCondition::DecreasedBy(amount) => diff == -amount,
            FuzzyCondition::IncreasedByRange(min, max) => diff >= min && diff <= max,
            FuzzyCondition::DecreasedByRange(min, max) => {
                let neg_diff = -diff;
                neg_diff >= min && neg_diff <= max
            },
            FuzzyCondition::IncreasedByPercent(percent) => {
                if old_val == 0 {
                    new_val > 0
                } else {
                    let threshold = (old_val as f64 * (1.0 + percent as f64)) as i64;
                    new_val >= threshold
                }
            },
            FuzzyCondition::DecreasedByPercent(percent) => {
                if old_val == 0 {
                    new_val < 0
                } else {
                    let threshold = (old_val as f64 * (1.0 - percent as f64)) as i64;
                    new_val <= threshold
                }
            },
        }
    }

    #[inline]
    fn matches_condition_float(&self, condition: FuzzyCondition) -> bool {
        let old_val = self.old_as_f64();
        let new_val = self.current_as_f64();
        let diff = new_val - old_val;
        let epsilon = 1e-9;

        match condition {
            FuzzyCondition::Initial => true,
            FuzzyCondition::Unchanged => (old_val - new_val).abs() < epsilon,
            FuzzyCondition::Changed => (old_val - new_val).abs() >= epsilon,
            FuzzyCondition::Increased => new_val > old_val + epsilon,
            FuzzyCondition::Decreased => new_val < old_val - epsilon,
            FuzzyCondition::IncreasedBy(amount) => (diff - amount as f64).abs() < epsilon,
            FuzzyCondition::DecreasedBy(amount) => (diff + amount as f64).abs() < epsilon,
            FuzzyCondition::IncreasedByRange(min, max) => diff >= min as f64 && diff <= max as f64,
            FuzzyCondition::DecreasedByRange(min, max) => {
                let neg_diff = -diff;
                neg_diff >= min as f64 && neg_diff <= max as f64
            },
            FuzzyCondition::IncreasedByPercent(percent) => {
                if old_val.abs() < epsilon {
                    new_val > epsilon
                } else {
                    let threshold = old_val * (1.0 + percent as f64);
                    new_val >= threshold
                }
            },
            FuzzyCondition::DecreasedByPercent(percent) => {
                if old_val.abs() < epsilon {
                    new_val < -epsilon
                } else {
                    let threshold = old_val * (1.0 - percent as f64);
                    new_val <= threshold
                }
            },
        }
    }
}

/// 地址批次 - 表示一段连续或接近连续的内存区域
#[derive(Debug)]
pub struct AddressBatch {
    start_addr: u64,          // 批次起始地址
    total_size: usize,        // 需要读取的总大小（包含间隙）
    items: Vec<BatchItemRef>, // 包含的地址引用
}

impl AddressBatch {
    /// 创建新批次（包含单个地址）
    fn new(start_addr: u64, size: usize, index: usize) -> Self {
        Self {
            start_addr,
            total_size: size,
            items: vec![BatchItemRef {
                offset: 0,
                item_index: index,
                value_size: size,
            }],
        }
    }
}

/// 批次内的地址引用
#[derive(Debug, Clone, Copy)]
struct BatchItemRef {
    pub offset: usize,     // 在批次缓冲区中的偏移
    pub item_index: usize, // 在原始 items 数组中的索引
    pub value_size: usize, // 值大小
}

/// 将有序的地址列表聚类为批次
///
/// 策略：
/// - 相邻地址（间隙 < BATCH_MAX_GAP）合并为同一批次
/// - 批次大小超过 BATCH_MAX_SIZE 时强制分割
/// - 利用地址已排序的特性
///
/// # 参数
/// * `items` - 有序的地址列表
///
/// # 返回
/// 返回地址批次列表
pub fn cluster_addresses(items: &[FuzzySearchResultItem]) -> Vec<AddressBatch> {
    if items.is_empty() {
        return Vec::new();
    }

    let mut batches = Vec::new();
    let mut current_batch: Option<AddressBatch> = None;

    for (idx, item) in items.iter().enumerate() {
        let addr = item.address;
        // 先拷贝 packed 字段再调用方法
        let vt = item.value_type;
        let size = vt.size();

        match &mut current_batch {
            Some(batch) => {
                let batch_end = batch.start_addr + batch.total_size as u64;
                let gap = addr.saturating_sub(batch_end);
                let new_total_size = (addr + size as u64 - batch.start_addr) as usize;

                // 决策：是否合并到当前批次
                if gap <= BATCH_MAX_GAP && new_total_size <= BATCH_MAX_SIZE {
                    // 合并：更新批次大小并添加地址引用
                    batch.total_size = new_total_size;
                    batch.items.push(BatchItemRef {
                        offset: (addr - batch.start_addr) as usize,
                        item_index: idx,
                        value_size: size,
                    });
                } else {
                    // 完成当前批次，开始新批次
                    batches.push(current_batch.take().unwrap());
                    current_batch = Some(AddressBatch::new(addr, size, idx));
                }
            },
            None => {
                // 首个批次
                current_batch = Some(AddressBatch::new(addr, size, idx));
            },
        }
    }

    // 添加最后一个批次
    if let Some(batch) = current_batch {
        batches.push(batch);
    }

    batches
}

/// 并行批量读取内存
///
/// 使用 Rayon 并行处理各个批次，每个批次单次读取整段内存
/// 批量读取失败时自动降级为逐个读取
///
/// # 参数
/// * `batches` - 地址批次列表
/// * `items` - 原始地址列表
/// * `processed_counter` - 已处理计数器
/// * `total_found_counter` - 找到总数计数器
/// * `update_progress` - 进度更新回调
/// * `check_cancelled` - 取消检查闭包
///
/// # 返回
/// 返回成功读取的 ReadResultItem 列表（使用固定大小数组，避免 Vec 分配开销）
pub fn parallel_batch_read<P, F>(
    batches: &[AddressBatch],
    items: &[FuzzySearchResultItem],
    processed_counter: Option<&Arc<AtomicUsize>>,
    total_found_counter: Option<&Arc<AtomicUsize>>,
    update_progress: &P,
    check_cancelled: Option<&F>,
) -> Result<Vec<ReadResultItem>>
where
    P: Fn(usize, usize) + Sync,
    F: Fn() -> bool + Sync,
{
    let _total_items = items.len();
    let cancelled = Arc::new(AtomicBool::new(false));
    let cancelled_clone = Arc::clone(&cancelled);

    // 并行处理批次
    let results: Result<Vec<ReadResultItem>> = batches
        .par_iter()
        .enumerate()
        .take_any_while(|&(_idx, _batch)| {
            // 检查取消状态
            if cancelled_clone.load(Ordering::Relaxed) {
                return false;
            }
            if let Some(check_fn) = check_cancelled {
                if check_fn() {
                    cancelled_clone.store(true, Ordering::Relaxed);
                    return false;
                }
            }
            true
        })
        .try_fold(
            || Vec::new(), // 线程本地累加器
            |mut acc, (batch_idx, batch)| -> Result<Vec<ReadResultItem>> {
                let driver_manager = DRIVER_MANAGER.read().map_err(|_| anyhow!("Failed to acquire DriverManager lock"))?;

                // 分配批次缓冲区
                let mut buffer = vec![0u8; batch.total_size];

                // 单次批量读取整个段
                match driver_manager.read_memory_unified(
                    batch.start_addr,
                    &mut buffer,
                    None, // 不跟踪页状态
                ) {
                    Ok(_) => {
                        // 从批次缓冲区提取各个地址的值
                        for item_ref in &batch.items {
                            let value_bytes = &buffer[item_ref.offset..item_ref.offset + item_ref.value_size];
                            let original_item = &items[item_ref.item_index];
                            acc.push(ReadResultItem::new(original_item, value_bytes));
                        }
                    },
                    Err(e) => {
                        if log_enabled!(Level::Debug) {
                            debug!(
                                "Batch read failed at 0x{:X} (size {}), falling back to individual reads: {:?}",
                                batch.start_addr, batch.total_size, e
                            );
                        }

                        // 逐个读取批次内的地址
                        for item_ref in &batch.items {
                            let original_item = &items[item_ref.item_index];
                            let mut small_buffer = vec![0u8; item_ref.value_size];

                            if driver_manager.read_memory_unified(original_item.address, &mut small_buffer, None).is_ok() {
                                acc.push(ReadResultItem::new(original_item, &small_buffer));
                            }
                        }
                    },
                }

                drop(driver_manager); // 显式释放读锁

                if batch_idx % PROGRESS_UPDATE_BATCH_SIZE == 0 {
                    if let Some(counter) = processed_counter {
                        let processed = counter.fetch_add(batch.items.len(), Ordering::Relaxed) + batch.items.len();
                        let found = total_found_counter.map(|c| c.load(Ordering::Relaxed)).unwrap_or(0);
                        update_progress(processed, found);
                    }
                } else if let Some(counter) = processed_counter {
                    // 更新计数器
                    counter.fetch_add(batch.items.len(), Ordering::Relaxed);
                }

                Ok(acc)
            },
        )
        .try_reduce(
            || Vec::new(),
            |mut a, b| {
                a.extend(b);
                Ok(a)
            },
        );

    results
}
