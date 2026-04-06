use crate::search::{SearchResultItem, ValueType};
use crate::search::result_manager::SearchResultManager;
use log::{debug, info};
use memmap2::MmapMut;
use std::fs::{File, OpenOptions};
use std::path::PathBuf;

#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct ExactSearchResultItem {
    pub address: u64,
    pub typ: ValueType,
}

impl ExactSearchResultItem {
    pub fn new(address: u64, typ: ValueType) -> Self {
        ExactSearchResultItem { address, typ }
    }
}

impl From<(u64, ValueType)> for ExactSearchResultItem {
    fn from(tuple: (u64, ValueType)) -> Self {
        Self::new(tuple.0, tuple.1)
    }
}

pub struct ExactSearchResultManager {
    memory_buffer: Vec<ExactSearchResultItem>,
    memory_buffer_capacity: usize,
    cache_dir: PathBuf,
    disk_file_path: Option<PathBuf>,
    disk_file: Option<File>,
    mmap: Option<MmapMut>,
    disk_count: usize,
    total_count: usize,
}

impl ExactSearchResultManager {
    pub fn new(memory_buffer_size: usize, cache_dir: PathBuf) -> Self {
        let capacity = if memory_buffer_size == 0 {
            0
        } else {
            memory_buffer_size / size_of::<ExactSearchResultItem>()
        };

        if memory_buffer_size == 0 {
            info!(
                "Initializing CompactSearchResultManager: memory_buffer_capacity=0 (direct disk write mode), cache_dir={:?}",
                cache_dir
            );
        } else {
            info!(
                "Initializing CompactSearchResultManager: memory_buffer_capacity={} items ({} MB), cache_dir={:?}",
                capacity,
                memory_buffer_size / 1024 / 1024,
                cache_dir
            );
        }

        ExactSearchResultManager {
            memory_buffer: Vec::with_capacity(capacity),
            memory_buffer_capacity: capacity,
            cache_dir,
            disk_file_path: None,
            disk_file: None,
            mmap: None,
            disk_count: 0,
            total_count: 0,
        }
    }

    pub fn clear(&mut self) -> anyhow::Result<()> {
        self.memory_buffer.clear();
        self.total_count = 0;
        self.disk_count = 0;

        debug!("Search results cleared (disk file and resources preserved for reuse)");
        Ok(())
    }

    pub fn destroy(&mut self) -> anyhow::Result<()> {
        self.memory_buffer.clear();
        self.total_count = 0;
        self.disk_count = 0;

        if let Some(ref path) = self.disk_file_path {
            drop(self.mmap.take());
            drop(self.disk_file.take());
            if path.exists() {
                std::fs::remove_file(path)?;
                debug!("Removed disk file: {:?}", path);
            }
        }

        self.disk_file_path = None;
        info!("CompactSearchResultManager destroyed");
        Ok(())
    }

    pub fn add_result(&mut self, item: ExactSearchResultItem) -> anyhow::Result<()> {
        if self.memory_buffer_capacity == 0 {
            self.write_to_disk(&item)?;
        } else if self.memory_buffer.len() < self.memory_buffer_capacity {
            self.memory_buffer.push(item);
        } else {
            self.write_to_disk(&item)?;
        }

        self.total_count += 1;
        Ok(())
    }

    fn write_to_disk(&mut self, item: &ExactSearchResultItem) -> anyhow::Result<()> {
        if self.disk_file.is_none() {
            self.init_disk_file()?;
        }

        if let Some(ref mut mmap) = self.mmap {
            let offset = self.disk_count * size_of::<ExactSearchResultItem>();
            let mmap_size = mmap.len();

            if offset + size_of::<ExactSearchResultItem>() > mmap_size {
                drop(self.mmap.take());
                let new_size = mmap_size + 128 * 1024 * 1024;
                if let Some(ref file) = self.disk_file {
                    file.set_len(new_size as u64)?;
                }
                self.mmap = Some(unsafe { MmapMut::map_mut(self.disk_file.as_ref().unwrap())? });
            }

            let mmap = self.mmap.as_mut().unwrap();
            unsafe {
                let ptr = mmap.as_mut_ptr().add(offset) as *mut ExactSearchResultItem;
                ptr.write(*item);
            }

            self.disk_count += 1;
        }

        Ok(())
    }

    fn init_disk_file(&mut self) -> anyhow::Result<()> {
        let file_path = self.cache_dir.join("mamu_search_results.bin");

        debug!("Creating disk file: {:?}", file_path);

        let initial_size = 128 * 1024 * 1024;
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(true)
            .open(&file_path)?;

        file.set_len(initial_size as u64)?;

        let mmap = unsafe { MmapMut::map_mut(&file)? };

        self.disk_file_path = Some(file_path);
        self.disk_file = Some(file);
        self.mmap = Some(mmap);

        info!("Disk file initialized with size {} MB", initial_size / 1024 / 1024);
        Ok(())
    }
    
    pub fn clear_disk(&mut self) -> anyhow::Result<()> {
        drop(self.mmap.take());
        drop(self.disk_file.take());

        if let Some(ref path) = self.disk_file_path {
            if path.exists() {
                std::fs::remove_file(path)?;
                debug!("Removed disk file: {:?}", path);
            }
        }

        self.disk_file_path = None;
        self.disk_count = 0;

        info!("Disk resources cleared");
        Ok(())
    }

    pub fn get_results(&self, start: usize, size: usize) -> anyhow::Result<Vec<ExactSearchResultItem>> {
        let end = std::cmp::min(start + size, self.total_count);
        if start >= self.total_count {
            return Ok(Vec::new());
        }

        let result_count = end - start;
        let mut results = Vec::with_capacity(result_count);
        let memory_len = self.memory_buffer.len();

        // 计算内存部分的范围（start 和 end 都限制在 memory_len 内）
        if start < memory_len {
            let memory_start = start;
            let memory_end = end.min(memory_len);
            results.extend_from_slice(&self.memory_buffer[memory_start..memory_end]);
        }

        // 计算磁盘部分的范围
        if end > memory_len {
            let disk_start = start.saturating_sub(memory_len);
            let disk_end = end - memory_len;
            
            if disk_end <= self.disk_count {
                if let Some(ref mmap) = self.mmap {
                    let disk_count = disk_end - disk_start;
                    let src_offset = disk_start * size_of::<ExactSearchResultItem>();
                    
                    // 预留空间
                    results.reserve(disk_count);
                    
                    unsafe {
                        let src = mmap.as_ptr().add(src_offset) as *const ExactSearchResultItem;
                        let dst_start = results.len();
                        results.set_len(dst_start + disk_count);
                        std::ptr::copy_nonoverlapping(src, results.as_mut_ptr().add(dst_start), disk_count);
                    }
                }
            }
        }

        Ok(results)
    }

    pub fn total_count(&self) -> usize {
        self.total_count
    }

    pub fn memory_count(&self) -> usize {
        self.memory_buffer.len()
    }

    pub fn disk_count(&self) -> usize {
        self.disk_count
    }

    pub fn remove_result(&mut self, index: usize) -> anyhow::Result<()> {
        if index >= self.total_count {
            return Err(anyhow::anyhow!("Index out of bounds: {} >= {}", index, self.total_count));
        }

        if index < self.memory_buffer.len() {
            // 删除内存中的数据
            self.memory_buffer.remove(index);
        } else {
            // 删除磁盘中的数据，需要移动后面的数据
            let disk_index = index - self.memory_buffer.len();
            self.remove_disk_item(disk_index)?;
        }

        self.total_count -= 1;
        debug!("Removed result at index {}, total count: {}", index, self.total_count);
        Ok(())
    }

    fn remove_disk_item(&mut self, disk_index: usize) -> anyhow::Result<()> {
        if disk_index >= self.disk_count {
            return Err(anyhow::anyhow!("Disk index out of bounds"));
        }

        if let Some(ref mut mmap) = self.mmap {
            let item_size = size_of::<ExactSearchResultItem>();
            let src_offset = (disk_index + 1) * item_size;
            let dst_offset = disk_index * item_size;
            let move_count = self.disk_count - disk_index - 1;

            if move_count > 0 {
                unsafe {
                    let src = mmap.as_ptr().add(src_offset);
                    let dst = mmap.as_mut_ptr().add(dst_offset);
                    std::ptr::copy(src, dst, move_count * item_size);
                }
            }

            self.disk_count -= 1;
        }

        Ok(())
    }

    pub fn remove_results_batch(&mut self, mut indices: Vec<usize>) -> anyhow::Result<()> {
        if indices.is_empty() {
            return Ok(());
        }

        // 去重并从小到大排序
        indices.sort_unstable();
        indices.dedup();

        // 过滤掉无效索引
        indices.retain(|&idx| idx < self.total_count);
        if indices.is_empty() {
            return Ok(());
        }

        let delete_count = indices.len();
        let memory_len = self.memory_buffer.len();

        // 分离内存索引和磁盘索引
        let (memory_indices, disk_indices): (Vec<usize>, Vec<usize>) = indices
            .into_iter()
            .partition(|&idx| idx < memory_len);

        // 处理内存部分：一次性移动
        if !memory_indices.is_empty() {
            self.remove_memory_batch(&memory_indices);
        }

        // 处理磁盘部分：一次性移动
        if !disk_indices.is_empty() {
            // 将全局索引转换为磁盘索引（减去原始内存长度）
            let adjusted_disk_indices: Vec<usize> = disk_indices
                .iter()
                .map(|&idx| idx - memory_len)
                .collect();
            self.remove_disk_batch(&adjusted_disk_indices)?;
        }

        self.total_count -= delete_count;
        debug!(
            "Batch removed {} results (memory: {}, disk: {}), total: {}",
            delete_count,
            memory_indices.len(),
            disk_indices.len(),
            self.total_count
        );
        Ok(())
    }

    /// 批量删除内存中的项（双指针方案）
    /// sorted_indices 必须是已排序的有效索引
    fn remove_memory_batch(&mut self, sorted_indices: &[usize]) {
        if sorted_indices.is_empty() || self.memory_buffer.is_empty() {
            return;
        }

        let first_del = sorted_indices[0];
        let mem_len = self.memory_buffer.len();

        // 边界检查：第一个删除索引必须在有效范围内
        if first_del >= mem_len {
            return;
        }

        // 使用双指针，一次遍历完成所有移动
        let mut write_pos = first_del;
        let mut delete_iter = sorted_indices.iter().peekable();

        for read_pos in first_del..mem_len {
            // 跳过要删除的位置
            if let Some(&&del_idx) = delete_iter.peek() {
                if read_pos == del_idx {
                    delete_iter.next();
                    continue;
                }
            }

            // 移动保留的项
            if write_pos != read_pos {
                self.memory_buffer[write_pos] = self.memory_buffer[read_pos];
            }
            write_pos += 1;
        }

        // 截断到新长度
        self.memory_buffer.truncate(write_pos);
    }

    /// 批量删除磁盘中的项（双指针方案）
    /// sorted_disk_indices 必须是已排序的有效磁盘索引
    fn remove_disk_batch(&mut self, sorted_disk_indices: &[usize]) -> anyhow::Result<()> {
        if sorted_disk_indices.is_empty() || self.disk_count == 0 {
            return Ok(());
        }

        let Some(ref mut mmap) = self.mmap else {
            return Ok(());
        };

        let first_del = sorted_disk_indices[0];

        // 边界检查：第一个删除索引必须在有效范围内
        if first_del >= self.disk_count {
            return Ok(());
        }

        let item_size = size_of::<ExactSearchResultItem>();
        let mut write_pos = first_del;
        let mut delete_iter = sorted_disk_indices.iter().peekable();

        // 计算保留区间并移动
        for read_pos in first_del..self.disk_count {
            // 跳过要删除的位置
            if let Some(&&del_idx) = delete_iter.peek() {
                // 如果删除索引越界，跳过后续检查
                if del_idx >= self.disk_count {
                    // 清空迭代器，后续不再检查
                    while delete_iter.next().is_some() {}
                } else if read_pos == del_idx {
                    delete_iter.next();
                    continue;
                }
            }

            // 移动保留的项
            if write_pos != read_pos {
                unsafe {
                    let src = mmap.as_ptr().add(read_pos * item_size);
                    let dst = mmap.as_mut_ptr().add(write_pos * item_size);
                    std::ptr::copy_nonoverlapping(src, dst, item_size);
                }
            }
            write_pos += 1;
        }

        self.disk_count = write_pos;
        Ok(())
    }

    /// Keep only the specified results, remove all others
    /// Optimized: when keep_count < remove_count, rebuild instead of batch delete
    pub fn keep_only_results(&mut self, mut keep_indices: Vec<usize>) -> anyhow::Result<()> {
        if keep_indices.is_empty() {
            // 如果要保留的列表为空，直接清空所有结果
            self.memory_buffer.clear();
            self.disk_count = 0;
            self.total_count = 0;
            debug!("Kept 0 results, cleared all");
            return Ok(());
        }

        let keep_count = keep_indices.len();
        let remove_count = self.total_count.saturating_sub(keep_count);

        if remove_count == 0 {
            // 如果没有要删除的，说明所有索引都要保留
            debug!("Keep all {} results, nothing to remove", self.total_count);
            return Ok(());
        }

        // 优化策略：当保留数量 <= 删除数量时，采用重建策略
        // 重建策略：读取要保留的项，清空结果集，重新添加
        // 时间复杂度：O(keep_count) vs O(remove_count * move_cost)
        if keep_count <= remove_count {
            debug!(
                "Using rebuild strategy: keep {} items, would remove {} items",
                keep_count, remove_count
            );

            // 按索引排序，确保读取顺序
            keep_indices.sort_unstable();

            // 读取要保留的项
            let mut kept_items: Vec<ExactSearchResultItem> = Vec::with_capacity(keep_count);
            for &idx in &keep_indices {
                if idx >= self.total_count {
                    continue; // 跳过无效索引
                }
                if idx < self.memory_buffer.len() {
                    kept_items.push(self.memory_buffer[idx]);
                } else {
                    let disk_index = idx - self.memory_buffer.len();
                    if let Some(ref mmap) = self.mmap {
                        let offset = disk_index * size_of::<ExactSearchResultItem>();
                        unsafe {
                            let ptr = mmap.as_ptr().add(offset) as *const ExactSearchResultItem;
                            kept_items.push(*ptr);
                        }
                    }
                }
            }

            // 清空当前结果集
            self.memory_buffer.clear();
            self.disk_count = 0;
            self.total_count = 0;

            // 重新添加保留的项（全部放入内存，因为数量较少）
            for item in kept_items {
                self.add_result(item)?;
            }

            debug!(
                "Rebuild complete: kept {} results, removed {} results",
                self.total_count, remove_count
            );
        } else {
            // 当删除数量较少时，使用原来的批量删除策略
            debug!(
                "Using batch delete strategy: keep {} items, remove {} items",
                keep_count, remove_count
            );

            use std::collections::HashSet;
            let keep_set: HashSet<usize> = keep_indices.into_iter().collect();

            // 计算要删除的索引
            let remove_indices: Vec<usize> = (0..self.total_count)
                .filter(|i| !keep_set.contains(i))
                .collect();

            self.remove_results_batch(remove_indices)?;

            debug!(
                "Batch delete complete: kept {} results, removed {} results",
                self.total_count, remove_count
            );
        }

        Ok(())
    }

    /// Get all results (used for refine search)
    pub fn get_all_results(&self) -> anyhow::Result<Vec<ExactSearchResultItem>> {
        self.get_results(0, self.total_count)
    }
}

impl Drop for ExactSearchResultManager {
    fn drop(&mut self) {
        let _ = self.destroy();
    }
}
