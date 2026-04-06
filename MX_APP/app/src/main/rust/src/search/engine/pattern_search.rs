//! 特征码搜索引擎
//!
//! 在内存中搜索匹配特征码的地址

use crate::core::DRIVER_MANAGER;
use crate::search::{PAGE_SIZE, PAGE_MASK};
use crate::wuwa::PageStatusBitmap;
use anyhow::{anyhow, Result};
use log::{debug, error, log_enabled, warn, Level};
use memchr::memchr_iter;
use rayon::prelude::*;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicUsize, Ordering};
use std::sync::Arc;

/// 每个 rayon 任务扫描的粒度
const PAR_SCAN_GRAIN: usize = 64 * 1024;

/// 在缓冲区中搜索特征码
/// 
/// # 参数
/// * `buffer` - 内存缓冲区
/// * `buffer_addr` - 缓冲区对应的目标进程地址
/// * `region_start` - 搜索区域起始地址
/// * `region_end` - 搜索区域结束地址
/// * `pattern` - 特征码 (value, mask) 数组
/// * `page_status` - 页面状态位图
/// * `results` - 搜索结果
#[inline]
pub fn search_pattern_in_buffer(
    buffer: &[u8],
    buffer_addr: u64,
    region_start: u64,
    region_end: u64,
    pattern: &[(u8, u8)],
    page_status: &PageStatusBitmap,
    results: &mut Vec<u64>,
) {
    let pattern_len = pattern.len();
    if buffer.len() < pattern_len || pattern_len == 0 {
        return;
    }

    let buffer_end = buffer_addr + buffer.len() as u64;
    let search_start = buffer_addr.max(region_start);
    let search_end = buffer_end.min(region_end);

    if search_start >= search_end {
        return;
    }

    let scan_start_pos = (search_start - buffer_addr) as usize;
    let scan_end_pos = (search_end - buffer_addr) as usize;

    // 确保有足够空间匹配完整 pattern
    if scan_end_pos < pattern_len {
        return;
    }
    let effective_end = scan_end_pos - pattern_len + 1;
    if scan_start_pos >= effective_end {
        return;
    }

    // 找第一个非通配字节作为锚点加速搜索
    let anchor = pattern.iter()
        .enumerate()
        .find(|(_, (_, mask))| *mask == 0xFF);

    // 按大粒度切分
    let ranges: Vec<(usize, usize)> = (scan_start_pos..effective_end)
        .step_by(PAR_SCAN_GRAIN)
        .map(|s| {
            let e = (s + PAR_SCAN_GRAIN).min(effective_end);
            (s, e)
        })
        .collect();

    let hits: Vec<u64> = ranges
        .into_par_iter()
        .flat_map(|(rs, re)| {
            let mut local = Vec::new();

            if let Some((anchor_idx, (anchor_byte, _))) = anchor {
                // 使用 memchr 加速
                // 按页遍历
                let start_page_idx = rs / *PAGE_SIZE;
                let end_page_idx = (re + *PAGE_SIZE - 1) / *PAGE_SIZE;

                for page_idx in start_page_idx..end_page_idx {
                    if !page_status.is_page_success(page_idx) {
                        continue;
                    }

                    let page_start = (page_idx * *PAGE_SIZE).max(rs);
                    let page_end = ((page_idx + 1) * *PAGE_SIZE).min(re);

                    if page_start >= page_end {
                        continue;
                    }

                    // 在当前页搜索锚点字节
                    let search_slice = &buffer[page_start..page_end.min(buffer.len())];
                    
                    for offset in memchr_iter(*anchor_byte, search_slice) {
                        let actual_pos = page_start + offset;
                        
                        // 检查锚点位置是否允许完整匹配
                        if actual_pos < anchor_idx {
                            continue;
                        }
                        let start_pos = actual_pos - anchor_idx;
                        
                        if start_pos + pattern_len > buffer.len() {
                            break;
                        }

                        let addr = buffer_addr + start_pos as u64;
                        if addr < search_start || addr >= search_end {
                            continue;
                        }

                        // 完整匹配验证
                        if match_pattern_at(&buffer[start_pos..], pattern) {
                            local.push(addr);
                        }
                    }
                }
            } else {
                // 全通配符，逐字节扫描
                for page_idx in (rs / *PAGE_SIZE)..((re + *PAGE_SIZE - 1) / *PAGE_SIZE) {
                    if !page_status.is_page_success(page_idx) {
                        continue;
                    }

                    let page_start = (page_idx * *PAGE_SIZE).max(rs);
                    let page_end = ((page_idx + 1) * *PAGE_SIZE).min(re);

                    for pos in page_start..page_end {
                        if pos + pattern_len > buffer.len() {
                            break;
                        }

                        let addr = buffer_addr + pos as u64;
                        if addr < search_start || addr >= search_end {
                            continue;
                        }

                        if match_pattern_at(&buffer[pos..], pattern) {
                            local.push(addr);
                        }
                    }
                }
            }

            local
        })
        .collect();

    results.extend(hits);
}

/// 在指定位置匹配特征码
#[inline]
fn match_pattern_at(data: &[u8], pattern: &[(u8, u8)]) -> bool {
    if data.len() < pattern.len() {
        return false;
    }
    pattern.iter().enumerate().all(|(i, &(value, mask))| {
        (data[i] & mask) == (value & mask)
    })
}

/// 搜索单个内存区域
pub fn search_region_pattern(
    pattern: &[(u8, u8)],
    start: u64,
    end: u64,
    chunk_size: usize,
) -> Result<Vec<u64>> {
    let driver_manager = DRIVER_MANAGER.read()
        .map_err(|_| anyhow!("Failed to acquire DriverManager lock"))?;

    let pattern_len = pattern.len();
    if pattern_len == 0 {
        return Err(anyhow!("Empty pattern"));
    }

    let mut results = Vec::new();
    let mut current = start & !(*PAGE_SIZE as u64 - 1);
    let mut chunk_buffer = vec![0u8; chunk_size];

    while current < end {
        let chunk_end = (current + chunk_size as u64).min(end);
        let chunk_len = (chunk_end - current) as usize;

        let mut page_status = PageStatusBitmap::new(chunk_len, current as usize);

        match driver_manager.read_memory_unified(current, &mut chunk_buffer[..chunk_len], Some(&mut page_status)) {
            Ok(_) => {
                if page_status.success_count() > 0 {
                    search_pattern_in_buffer(
                        &chunk_buffer[..chunk_len],
                        current,
                        start,
                        end,
                        pattern,
                        &page_status,
                        &mut results,
                    );
                }
            },
            Err(e) => {
                if log_enabled!(Level::Debug) {
                    warn!("Failed to read memory at 0x{:X}: {:?}", current, e);
                }
            },
        }

        current = chunk_end;
    }

    Ok(results)
}

/// 带取消支持的特征码搜索
pub fn search_region_pattern_with_cancel<F>(
    pattern: &[(u8, u8)],
    start: u64,
    end: u64,
    chunk_size: usize,
    check_cancelled: &F,
) -> Result<Vec<u64>>
where
    F: Fn() -> bool + Sync,
{
    let driver_manager = DRIVER_MANAGER.read()
        .map_err(|_| anyhow!("Failed to acquire DriverManager lock"))?;

    let pattern_len = pattern.len();
    if pattern_len == 0 {
        return Err(anyhow!("Empty pattern"));
    }

    let mut results = Vec::new();
    let mut current = start & !(*PAGE_SIZE as u64 - 1);
    let mut chunk_buffer = vec![0u8; chunk_size];

    while current < end {
        if check_cancelled() {
            break;
        }

        let chunk_end = (current + chunk_size as u64).min(end);
        let chunk_len = (chunk_end - current) as usize;

        let mut page_status = PageStatusBitmap::new(chunk_len, current as usize);

        match driver_manager.read_memory_unified(current, &mut chunk_buffer[..chunk_len], Some(&mut page_status)) {
            Ok(_) => {
                if page_status.success_count() > 0 {
                    search_pattern_in_buffer(
                        &chunk_buffer[..chunk_len],
                        current,
                        start,
                        end,
                        pattern,
                        &page_status,
                        &mut results,
                    );
                }
            },
            Err(e) => {
                if log_enabled!(Level::Debug) {
                    warn!("Failed to read memory at 0x{:X}: {:?}", current, e);
                }
            },
        }

        current = chunk_end;
    }

    Ok(results)
}
