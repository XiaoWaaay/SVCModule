//! Driver manager implementation

use crate::core::memory_mode::MemoryAccessMode;
use crate::wuwa::{BindProc, PageStatusBitmap, WuWaDriver, WuwaMemoryType};
use log::error;

pub struct DriverManager {
    driver: Option<WuWaDriver>,
    bound_process: Option<BindProc>,
    bound_pid: i32,
    access_mode: MemoryAccessMode,
}

impl DriverManager {
    pub fn new() -> Self {
        Self {
            driver: None,
            bound_process: None,
            bound_pid: 0,
            access_mode: MemoryAccessMode::None,
        }
    }

    pub fn set_driver(&mut self, driver: WuWaDriver) {
        self.driver = Some(driver);
    }

    pub fn get_driver(&self) -> Option<&WuWaDriver> {
        self.driver.as_ref()
    }

    pub fn is_driver_loaded(&self) -> bool {
        self.driver.is_some()
    }

    /// 设置内存访问模式
    pub fn set_access_mode(&mut self, mode: MemoryAccessMode) -> anyhow::Result<()> {
        self.access_mode = mode;
        if self.is_process_bound() {
            if let Some(bind_proc) = &self.bound_process {
                match self.get_access_mode() {
                    MemoryAccessMode::None => {}, // do nothing
                    MemoryAccessMode::NonCacheable => {
                        bind_proc.set_memory_type(WuwaMemoryType::DeviceNGnRnE)?;
                    },
                    MemoryAccessMode::WriteThrough => {
                        bind_proc.set_memory_type(WuwaMemoryType::NormalWt)?;
                    },
                    MemoryAccessMode::Normal => {
                        bind_proc.set_memory_type(WuwaMemoryType::Normal)?;
                    },
                    MemoryAccessMode::PageFault => {}, // do nothing
                };
            }
        }

        Ok(())
    }

    pub fn get_access_mode(&self) -> MemoryAccessMode {
        self.access_mode
    }

    /// 绑定进程以进行内存访问
    pub fn bind_process(&mut self, bind_proc: BindProc, pid: i32) -> anyhow::Result<()> {
        match self.get_access_mode() {
            MemoryAccessMode::None => {}, // do nothing
            MemoryAccessMode::NonCacheable => {
                bind_proc.set_memory_type(WuwaMemoryType::DeviceNGnRnE)?;
            },
            MemoryAccessMode::WriteThrough => {
                bind_proc.set_memory_type(WuwaMemoryType::NormalWt)?;
            },
            MemoryAccessMode::Normal => {
                bind_proc.set_memory_type(WuwaMemoryType::Normal)?;
            },
            MemoryAccessMode::PageFault => {}, // do nothing
        };
        // 缺页模式和物理模式不需要设置内存类型，这个时候不走bindproc去读写内存
        self.bound_process = Some(bind_proc);
        self.bound_pid = pid;
        Ok(())
    }

    /// 解绑当前绑定的进程
    pub fn unbind_process(&mut self) {
        self.bound_process = None;
        self.bound_pid = 0;
    }

    pub fn is_process_bound(&self) -> bool {
        self.bound_process.is_some() && self.bound_pid != 0
    }

    pub fn get_bound_pid(&self) -> i32 {
        self.bound_pid
    }

    pub fn get_bound_process(&self) -> Option<&BindProc> {
        self.bound_process.as_ref()
    }

    /// 统一的内存读取方法，使用当前配置的 access_mode
    ///
    /// # Arguments
    /// * `addr` - 要读取的虚拟地址
    /// * `buf` - 读取缓冲区
    /// * `page_status` - 可选的页状态位图，用于跟踪每页的读取成功状态
    ///
    /// # Returns
    /// * `Ok(())` 如果读取成功（对于部分读取检查 page_status）
    /// * `Err` 如果操作失败
    pub fn read_memory_unified(
        &self,
        addr: u64,
        buf: &mut [u8],
        page_status: Option<&mut PageStatusBitmap>,
    ) -> anyhow::Result<()> {
        // Strip ARM MTE tags (bits 56-63) — they don't participate in page table mapping
        let addr = addr & 0x0000_FFFF_FFFF_FFFF;
        match self.access_mode {
            MemoryAccessMode::None => {
                // 物理内存读取（绕过 access_mode）
                let driver = self
                    .get_driver()
                    .ok_or_else(|| anyhow::anyhow!("Driver not initialized"))?;
                let pid = self.get_bound_pid();

                if let Some(status) = page_status {
                    driver.read_physical_memory_with_status(
                        pid,
                        addr as usize,
                        buf.as_mut_ptr() as usize,
                        buf.len(),
                        status,
                    )?;
                } else {
                    // 没有页状态跟踪，创建临时的
                    let mut temp_status = PageStatusBitmap::new(buf.len(), addr as usize);
                    driver.read_physical_memory_with_status(
                        pid,
                        addr as usize,
                        buf.as_mut_ptr() as usize,
                        buf.len(),
                        &mut temp_status,
                    )?;
                }
                Ok(())
            },
            MemoryAccessMode::PageFault => {
                // 缺页模式：通过 driver 正常读取（不跟踪页状态）
                let driver = self
                    .get_driver()
                    .ok_or_else(|| anyhow::anyhow!("Driver not initialized"))?;
                let pid = self.get_bound_pid();
                driver.read_memory(pid, addr as usize, buf.as_mut_ptr() as usize, buf.len())?;

                // 标记所有页为成功，因为这个方法不跟踪每页状态
                if let Some(status) = page_status {
                    status.mark_all_success();
                }
                Ok(())
            },
            MemoryAccessMode::NonCacheable | MemoryAccessMode::WriteThrough | MemoryAccessMode::Normal => {
                // 使用 bind_proc 和配置的 access_mode
                let bind_proc = self
                    .get_bound_process()
                    .ok_or_else(|| anyhow::anyhow!("Process not bound"))?;
                bind_proc.read_memory(addr as usize, buf, page_status)
            },
        }
    }

    /// 统一的内存写入方法，使用当前配置的 access_mode
    ///
    /// # Arguments
    /// * `addr` - 要写入的虚拟地址
    /// * `buf` - 写入数据缓冲区
    ///
    /// # Returns
    /// * `Ok(())` 如果写入成功
    /// * `Err` 如果操作失败
    pub fn write_memory_unified(
        &self,
        addr: u64,
        buf: &[u8],
    ) -> anyhow::Result<()> {
        // Strip ARM MTE tags (bits 56-63) — they don't participate in page table mapping
        let addr = addr & 0x0000_FFFF_FFFF_FFFF;
        match self.access_mode {
            MemoryAccessMode::None => {
                // 物理内存写入（绕过 access_mode）
                let driver = self
                    .get_driver()
                    .ok_or_else(|| anyhow::anyhow!("Driver not initialized"))?;
                let pid = self.get_bound_pid();
                driver.write_physical_memory(
                    pid,
                    buf.as_ptr() as usize,
                    addr as usize,
                    buf.len(),
                )?;
                Ok(())
            },
            MemoryAccessMode::PageFault => {
                // 缺页模式：通过 driver 正常写入
                let driver = self
                    .get_driver()
                    .ok_or_else(|| anyhow::anyhow!("Driver not initialized"))?;
                let pid = self.get_bound_pid();
                driver.write_memory(
                    pid,
                    buf.as_ptr() as usize,
                    addr as usize,
                    buf.len(),
                )?;
                Ok(())
            },
            MemoryAccessMode::NonCacheable | MemoryAccessMode::WriteThrough | MemoryAccessMode::Normal => {
                // 使用 bind_proc 和配置的 access_mode
                let bind_proc = self
                    .get_bound_process()
                    .ok_or_else(|| anyhow::anyhow!("Process not bound"))?;
                bind_proc.write_memory(addr as usize, buf)
            },
        }
    }
}
