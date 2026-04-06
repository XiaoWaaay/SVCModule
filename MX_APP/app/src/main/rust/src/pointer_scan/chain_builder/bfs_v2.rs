//! BFS V2 指针链扫描器
//!
//! 基于 PointerScan-rust 的反向 BFS 算法实现：
//! - 使用 MapQueue (tmpfile + mmap) 避免内存爆炸
//! - PointerDir 隐式树结构 (start/end 索引)
//! - 多级 BFS 迭代，二分查找优化
//! - 直接将结果写入文件，避免内存问题
//!
//! 核心算法：
//! 1. 从目标地址开始，反向搜索指向它的指针
//! 2. 每层迭代：在全局指针库中查找 value 指向上一层 address 的指针
//! 3. 静态模块检测：识别到达 .so 模块的指针作为链的起点
//! 4. 建立层间索引：通过 PointerDir.start/end 关联父子关系

use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::PathBuf;
use std::time::Instant;

use anyhow::{anyhow, Result};
use log::{debug, info, log_enabled, warn, Level};

use crate::pointer_scan::mapqueue_v2::MapQueue;
use crate::pointer_scan::types::{
    MemRange, PointerData, PointerDir, PointerRange,
    PointerScanConfig, VmAreaData, VmStaticData,
};

/// 每层最大候选数，防止内存爆炸
const MAX_CANDIDATES_PER_LAYER: usize = 5_000_000;

/// 扫描结果
pub struct ScanResult {
    /// 找到的指针链数量
    pub total_count: usize,
    /// 输出文件路径
    pub output_file: PathBuf,
}

/// BFS V2 扫描器
pub struct BfsV2Scanner<'a> {
    /// 全局指针数据（按 address 排序）
    global_pointers: &'a [PointerData],
    /// 静态模块列表
    static_modules: &'a [VmStaticData],
    /// 扫描配置
    config: &'a PointerScanConfig,
}

impl<'a> BfsV2Scanner<'a> {
    /// 创建扫描器
    pub fn new(
        global_pointers: &'a [PointerData],
        static_modules: &'a [VmStaticData],
        config: &'a PointerScanConfig,
    ) -> Self {
        Self {
            global_pointers,
            static_modules,
            config,
        }
    }

    /// 执行 BFS 指针链扫描，结果直接写入文件
    ///
    /// # Arguments
    /// * `output_path` - 输出文件路径
    /// * `max_chains` - 最大输出链数
    /// * `progress_callback` - 进度回调 (当前层级, 总层级, 已找到链数)
    /// * `check_cancelled` - 检查是否取消
    ///
    /// # Returns
    /// ScanResult 包含总数和文件路径
    pub fn scan_to_file<F, C>(
        &self,
        output_path: PathBuf,
        max_chains: usize,
        progress_callback: F,
        check_cancelled: C,
    ) -> Result<ScanResult>
    where
        F: Fn(u32, i32, i64) + Sync,
        C: Fn() -> bool + Sync,
    {
        let timer = Instant::now();
        let target = self.config.target_address;
        let depth = self.config.max_depth as usize;
        let offset = self.config.max_offset as u64;

        info!(
            "BFS V2 扫描开始: 目标=0x{:X}, 深度={}, 偏移=0x{:X}, 指针库大小={}",
            target, depth, offset, self.global_pointers.len()
        );

        // 初始化数据结构
        // dirs: 每层的指针目录（使用 MapQueue 避免内存爆炸）
        let mut dirs: Vec<MapQueue<PointerDir>> = (0..=depth)
            .map(|_| MapQueue::new())
            .collect();

        // ranges: 找到的静态模块指针范围
        let mut ranges: Vec<PointerRange> = Vec::new();
        let mut first_range_idx = 0;

        // 阶段 1: 多级指针链扫描（BFS 展开）
        for level in 0..=depth {
            if check_cancelled() {
                return Err(anyhow!("扫描被取消"));
            }

            if log_enabled!(Level::Debug) {
                debug!("当前层级: {}", level);
            }

            if level > 0 {
                // 搜索上一层指针的引用
                let curr = self.search_pointer(&dirs[level - 1], offset)?;

                if log_enabled!(Level::Debug) {
                    debug!("层级 {}: 搜索到 {} 个指针", level, curr.len());
                }

                if curr.is_empty() {
                    break;
                }

                // 过滤指针范围：静态区域加入 ranges，其他加入 dirs
                self.filter_pointer_ranges(&mut dirs, &mut ranges, curr, level as i32)?;

                // 创建层间索引
                let (left, right) = dirs.split_at_mut(level);
                let prev = &left[level - 1];
                let curr = &mut right[0];
                Self::create_assoc_dir_index(prev, curr, offset)?;

                // 限制每层候选数量
                if dirs[level].len() > MAX_CANDIDATES_PER_LAYER {
                    warn!(
                        "[候选裁剪] 在层级 {} 将候选从 {} 剪枝到 {}",
                        level, dirs[level].len(), MAX_CANDIDATES_PER_LAYER
                    );
                    // 截断到最大限制
                    while dirs[level].len() > MAX_CANDIDATES_PER_LAYER {
                        dirs[level].pop();
                    }
                }
            } else {
                // Level 0: 转换目标地址为指针数据
                let curr: Vec<PointerData> = vec![PointerData::new(target, 0)];

                // 过滤指针范围
                self.filter_pointer_ranges(&mut dirs, &mut ranges, curr, 0)?;
                first_range_idx = ranges.len();
            }

            // 报告进度
            progress_callback(level as u32, depth as i32, ranges.len() as i64);
        }

        // 阶段 2: 补充静态模块索引
        for idx in first_range_idx..ranges.len() {
            let level = ranges[idx].level;
            if level > 0 {
                self.create_assoc_range_index(
                    &dirs[level as usize - 1],
                    &mut ranges[idx].results,
                    offset,
                )?;
            }
        }

        if ranges.is_empty() {
            info!("BFS V2 扫描完成: 未找到指针链");
            // 创建空文件
            File::create(&output_path)?;
            return Ok(ScanResult {
                total_count: 0,
                output_file: output_path,
            });
        }

        info!(
            "搜索和关联完成, 耗时: {:.3}s",
            timer.elapsed().as_secs_f64()
        );

        // 阶段 3: 直接写入文件（避免悬垂指针问题）
        let file = File::create(&output_path)?;
        let mut writer = BufWriter::with_capacity(1024 * 1024, file);

        // 写入文件头
        writeln!(writer, "# Pointer Scan Results")?;
        writeln!(writer, "# Target: 0x{:X}", target)?;
        writeln!(writer, "# Depth: {}", depth)?;
        writeln!(writer, "# Offset: 0x{:X}", offset)?;
        writeln!(writer, "# Generated by Mamu Pointer Scanner")?;
        writeln!(writer, "#")?;
        writeln!(writer, "# Format: module_name[index]+base_offset->offset1->offset2->...")?;
        writeln!(writer, "")?;

        let mut total_count = 0usize;
        let mut written_count = 0usize;

        // 遍历每个静态模块的结果
        for range in &ranges {
            let module_name = &range.vma.name;
            let module_index = range.vma.count;
            let level = range.level as usize;

            for root_dir in range.results.iter() {
                if written_count >= max_chains {
                    // 继续统计但不再写入
                    total_count += self.count_chains_from_dir(root_dir, &dirs, level);
                    continue;
                }

                // 递归写入所有链
                let chains_written = self.write_chains_recursive(
                    &mut writer,
                    &dirs,
                    root_dir,
                    level,
                    module_name,
                    module_index as u32,
                    root_dir.address - range.vma.start,
                    max_chains - written_count,
                )?;

                written_count += chains_written;
                total_count += chains_written;

                // 如果还有更多链但超过限制，计数但不写入
                if written_count >= max_chains {
                    let remaining = self.count_chains_from_dir(root_dir, &dirs, level);
                    if remaining > chains_written {
                        total_count += remaining - chains_written;
                    }
                }
            }

            if log_enabled!(Level::Debug) {
                debug!(
                    "模块 {}[{}] 层级 {}: 已写入链",
                    module_name, module_index, level
                );
            }
        }

        writer.flush()?;

        info!(
            "BFS V2 扫描完成: 找到 {} 条指针链, 写入 {} 条, 总耗时: {:.3}s",
            total_count,
            written_count,
            timer.elapsed().as_secs_f64()
        );

        // 最终进度报告
        progress_callback(depth as u32, depth as i32, total_count as i64);

        Ok(ScanResult {
            total_count,
            output_file: output_path,
        })
    }

    /// 递归写入指针链到文件
    fn write_chains_recursive<W: Write>(
        &self,
        writer: &mut W,
        dirs: &[MapQueue<PointerDir>],
        dir: &PointerDir,
        level: usize,
        module_name: &str,
        module_index: u32,
        base_offset: u64,
        max_chains: usize,
    ) -> Result<usize> {
        if max_chains == 0 {
            return Ok(0);
        }

        if level == 0 {
            // 到达最底层，写入链
            writeln!(writer, "{}[{}]+0x{:X}", module_name, module_index, base_offset)?;
            return Ok(1);
        }

        // 获取上一层的数据
        if level > dirs.len() {
            return Ok(0);
        }

        let prev_dirs = &dirs[level - 1];
        let prev_slice = prev_dirs.as_slice();
        let mut written = 0usize;

        // 遍历子节点
        for i in dir.start..dir.end {
            if written >= max_chains {
                break;
            }

            let idx = i as usize;
            if idx >= prev_slice.len() {
                break;
            }

            let child = &prev_slice[idx];
            let child_offset = child.address.wrapping_sub(dir.value) as i64;

            // 构建当前路径的偏移字符串
            let offset_str = if child_offset >= 0 {
                format!("+0x{:X}", child_offset)
            } else {
                format!("-0x{:X}", child_offset.abs())
            };

            // 递归处理子节点，收集完整路径
            let sub_chains = self.collect_and_write_chains(
                writer,
                dirs,
                child,
                level - 1,
                module_name,
                module_index,
                base_offset,
                vec![offset_str],
                max_chains - written,
            )?;

            written += sub_chains;
        }

        Ok(written)
    }

    /// 收集并写入完整的指针链
    fn collect_and_write_chains<W: Write>(
        &self,
        writer: &mut W,
        dirs: &[MapQueue<PointerDir>],
        dir: &PointerDir,
        level: usize,
        module_name: &str,
        module_index: u32,
        base_offset: u64,
        offsets: Vec<String>,
        max_chains: usize,
    ) -> Result<usize> {
        if max_chains == 0 {
            return Ok(0);
        }

        if level == 0 {
            // 到达最底层，写入完整链
            let offset_chain = offsets.join("->");
            writeln!(writer, "{}[{}]+0x{:X}->{}", module_name, module_index, base_offset, offset_chain)?;
            return Ok(1);
        }

        // 获取上一层的数据
        if level > dirs.len() {
            return Ok(0);
        }

        let prev_dirs = &dirs[level - 1];
        let prev_slice = prev_dirs.as_slice();
        let mut written = 0usize;

        // 遍历子节点
        for i in dir.start..dir.end {
            if written >= max_chains {
                break;
            }

            let idx = i as usize;
            if idx >= prev_slice.len() {
                break;
            }

            let child = &prev_slice[idx];
            let child_offset = child.address.wrapping_sub(dir.value) as i64;

            // 构建偏移字符串
            let offset_str = if child_offset >= 0 {
                format!("+0x{:X}", child_offset)
            } else {
                format!("-0x{:X}", child_offset.abs())
            };

            // 添加到路径
            let mut new_offsets = offsets.clone();
            new_offsets.push(offset_str);

            // 递归处理
            let sub_chains = self.collect_and_write_chains(
                writer,
                dirs,
                child,
                level - 1,
                module_name,
                module_index,
                base_offset,
                new_offsets,
                max_chains - written,
            )?;

            written += sub_chains;
        }

        Ok(written)
    }

    /// 统计从某个节点开始的链数量（不写入）
    fn count_chains_from_dir(
        &self,
        dir: &PointerDir,
        dirs: &[MapQueue<PointerDir>],
        level: usize,
    ) -> usize {
        if level == 0 {
            return 1;
        }

        if level > dirs.len() {
            return 0;
        }

        let prev_dirs = &dirs[level - 1];
        let prev_slice = prev_dirs.as_slice();
        let mut count = 0usize;

        for i in dir.start..dir.end {
            let idx = i as usize;
            if idx >= prev_slice.len() {
                break;
            }

            let child = &prev_slice[idx];
            count += self.count_chains_from_dir(child, dirs, level - 1);
        }

        count
    }

    /// 在全局指针数据中搜索指向上一层的指针
    fn search_pointer(
        &self,
        prev_dirs: &MapQueue<PointerDir>,
        offset: u64,
    ) -> Result<Vec<PointerData>> {
        let mut results = Vec::new();

        if prev_dirs.is_empty() {
            return Ok(results);
        }

        let prev_slice = prev_dirs.as_slice();
        let prev_len = prev_slice.len();

        // 遍历全局指针数据，找到 value 指向 prev_dirs 中 address 的指针
        for p in self.global_pointers {
            let value = p.value;

            // 二分查找：找到第一个 address >= value 的位置
            let lower = prev_slice.partition_point(|d| d.address < value);

            // 检查是否越界
            if lower >= prev_len {
                continue;
            }

            let target_addr = prev_slice[lower].address;

            // 原项目逻辑: target_addr >= value 且 (target_addr - value) <= offset
            if target_addr >= value && (target_addr - value) <= offset {
                results.push(*p);
            }
        }

        // 按 address 排序
        results.sort_unstable_by_key(|p| p.address);

        Ok(results)
    }

    /// 过滤指针范围：静态区域的加入 ranges，其他加入 dirs
    fn filter_pointer_ranges(
        &self,
        dirs: &mut Vec<MapQueue<PointerDir>>,
        ranges: &mut Vec<PointerRange>,
        curr: Vec<PointerData>,
        level: i32,
    ) -> Result<()> {
        let mut matched_addrs: Vec<u64> = Vec::new();

        for module in self.static_modules {
            // 找出在该模块范围内的指针（按 address 检查）
            let module_pointers: Vec<&PointerData> = curr
                .iter()
                .filter(|p| p.address >= module.base_address && p.address < module.end_address)
                .collect();

            if module_pointers.is_empty() {
                continue;
            }

            // 创建结果 MapQueue
            let mut results = MapQueue::with_capacity(module_pointers.len())?;
            for p in &module_pointers {
                results.push(PointerDir::from_data(p))?;
                matched_addrs.push(p.address);
            }

            if log_enabled!(Level::Debug) {
                debug!(
                    "{}[{}]: {} 个指针",
                    module.name, module.index, module_pointers.len()
                );
            }

            ranges.push(PointerRange::new(
                level,
                VmAreaData::from_static(module),
                results,
            ));
        }

        // 未匹配的加入 dirs（按 address 排序）
        matched_addrs.sort_unstable();
        for p in curr {
            if matched_addrs.binary_search(&p.address).is_err() {
                dirs[level as usize].push(PointerDir::from_data(&p))?;
            }
        }

        Ok(())
    }

    /// 创建层间索引关系
    fn create_assoc_dir_index(
        prev: &MapQueue<PointerDir>,
        curr: &mut MapQueue<PointerDir>,
        offset: u64,
    ) -> Result<()> {
        let prev_slice = prev.as_slice();

        for dir in curr.as_mut_slice() {
            let value = dir.value;

            // start: 第一个 address >= value 的位置
            let start = prev_slice.partition_point(|p| p.address < value);
            // end: 第一个 address > value + offset 的位置
            let end = prev_slice.partition_point(|p| p.address <= value + offset);

            dir.start = start as u32;
            dir.end = end as u32;
        }

        Ok(())
    }

    /// 为 range 结果创建索引
    fn create_assoc_range_index(
        &self,
        prev: &MapQueue<PointerDir>,
        results: &mut MapQueue<PointerDir>,
        offset: u64,
    ) -> Result<()> {
        let prev_slice = prev.as_slice();

        for dir in results.as_mut_slice() {
            let value = dir.value;
            let start = prev_slice.partition_point(|p| p.address < value);
            let end = prev_slice.partition_point(|p| p.address <= value + offset);
            dir.start = start as u32;
            dir.end = end as u32;
        }

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_bfs_v2_scanner_creation() {
        let pointers = vec![
            PointerData::new(0x1000, 0x2000),
            PointerData::new(0x2000, 0x3000),
        ];
        let modules = vec![VmStaticData::new(
            "libtest.so".to_string(),
            0x1000,
            0x2000,
            true,
        )];
        let config = PointerScanConfig::new(0x3000);

        let scanner = BfsV2Scanner::new(&pointers, &modules, &config);
        assert_eq!(scanner.global_pointers.len(), 2);
    }
}
