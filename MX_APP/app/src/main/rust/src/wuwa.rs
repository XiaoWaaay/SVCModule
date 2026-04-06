//! WuWa kernel driver SDK for ARM64 Android 6.1+
//!
//! Provides userspace bindings to the WuWa kernel module via hijacked socket protocol family.
//!
//! # Discovery
//!
//! The driver registers as a custom socket protocol family. Discovery algorithm:
//! 1. Probe uncommon AF_* families with SOCK_SEQPACKET
//! 2. Driver responds with -ENOKEY
//! 3. Create SOCK_RAW socket on identified family
//! 4. Issue ioctl commands on the socket fd
//!
//! # Usage
//!
//! ```no_run
//! // Method 1: Direct connection
//! let driver = WuWaDriver::new()?;
//! let pid = driver.find_process("target_app")?;
//! let base = driver.get_module_base(pid, "lib.so", 0x4)?;
//! let val: u32 = driver.read(pid, base + 0x1000)?;
//!
//! // Method 2: Using install_driver and from_fd
//! let fd = driver.install_driver(pid)?;
//! let proc_driver = WuWaDriver::from_fd(fd);
//! // proc_driver is bound to the specific process
//! ```
//!
//! # Safety
//!
//! This SDK provides direct physical memory access and kernel-level process manipulation.
//! Requires root or CAP_NET_RAW. For defensive security research only.

use anyhow::anyhow;
use log::{Level, debug, error, info, log_enabled};
use nix::errno::Errno;
use nix::libc::{_IOR, _IOWR, Ioctl, c_int, free, getsockopt, ioctl, malloc, pid_t, size_t, sockaddr_in, socklen_t};
use nix::sys::mman::{MapFlags, ProtFlags, mmap, munmap};
use nix::sys::socket::{AddressFamily, SockFlag, SockType, socket};
use nix::{NixPath, libc};
use std::ffi::c_void;
use std::mem::{MaybeUninit, size_of};
use std::os::fd::{AsFd, AsRawFd, FromRawFd, OwnedFd};
use std::ptr::NonNull;

// IOCTL command definitions (magic number 'W')
const WUWA_IOCTL_ADDR_TRANSLATE: Ioctl = _IOWR::<WuwaAddrTranslateCmd>(b'W' as u32, 1);
const WUWA_IOCTL_DEBUG_INFO: Ioctl = _IOR::<WuwaDebugInfoCmd>(b'W' as u32, 2);
const WUWA_IOCTL_AT_S1E0R: Ioctl = _IOWR::<WuwaAtS1e0rCmd>(b'W' as u32, 3);
const WUWA_IOCTL_PAGE_INFO: Ioctl = _IOWR::<WuwaPageInfoCmd>(b'W' as u32, 4);
const WUWA_IOCTL_DMA_BUF_CREATE: Ioctl = _IOWR::<WuwaDmaBufCreateCmd>(b'W' as u32, 5);
const WUWA_IOCTL_PTE_MAPPING: Ioctl = _IOWR::<WuwaPteMappingCmd>(b'W' as u32, 6);
const WUWA_IOCTL_PAGE_TABLE_WALK: Ioctl = _IOWR::<WuwaPageTableWalkCmd>(b'W' as u32, 7);
const WUWA_IOCTL_COPY_PROCESS: Ioctl = _IOWR::<WuwaCopyProcessCmd>(b'W' as u32, 8);
const WUWA_IOCTL_READ_PHYSICAL_MEMORY: Ioctl = _IOWR::<WuwaReadPhysicalMemoryCmd>(b'W' as u32, 9);
const WUWA_IOCTL_GET_MODULE_BASE: Ioctl = _IOWR::<WuwaGetModuleBaseCmd>(b'W' as u32, 10);
const WUWA_IOCTL_FIND_PROCESS: Ioctl = _IOWR::<WuwaFindProcCmd>(b'W' as u32, 11);
const WUWA_IOCTL_WRITE_PHYSICAL_MEMORY: Ioctl = _IOWR::<WuwaWritePhysicalMemoryCmd>(b'W' as u32, 12);
const WUWA_IOCTL_IS_PROCESS_ALIVE: Ioctl = _IOWR::<WuwaIsProcAliveCmd>(b'W' as u32, 13);
const WUWA_IOCTL_HIDE_PROCESS: Ioctl = _IOWR::<WuwaHideProcCmd>(b'W' as u32, 14);
const WUWA_IOCTL_GIVE_ROOT: Ioctl = _IOWR::<WuwaGiveRootCmd>(b'W' as u32, 15);
const WUWA_IOCTL_READ_MEMORY_IOREMAP: Ioctl = _IOWR::<WuwaReadPhysicalMemoryIoremapCmd>(b'W' as u32, 16);
const WUWA_IOCTL_WRITE_MEMORY_IOREMAP: Ioctl = _IOWR::<WuwaWritePhysicalMemoryIoremapCmd>(b'W' as u32, 17);
const WUWA_IOCTL_BIND_PROC: Ioctl = _IOWR::<WuwaBindProcCmd>(b'W' as u32, 18);
const WUWA_IOCTL_LIST_PROCESSES: Ioctl = _IOWR::<WuwaListProcessesCmd>(b'W' as u32, 19);
const WUWA_IOCTL_GET_PROC_INFO: Ioctl = _IOWR::<WuwaGetProcInfoCmd>(b'W' as u32, 20);
const WUWA_IOCTL_INSTALL_DRIVER: Ioctl = _IOWR::<WuwaInstallDriverCmd>(b'W' as u32, 21);
const WUWA_IOCTL_QUERY_MEM_REGIONS: Ioctl = _IOWR::<WuwaQueryMemRegionsCmd>(b'W' as u32, 22);
const WUWA_IOCTL_READ_MEMORY: Ioctl = _IOWR::<WuwaReadMemoryCmd>(b'W' as u32, 23);
const WUWA_IOCTL_WRITE_MEMORY: Ioctl = _IOWR::<WuwaWriteMemoryCmd>(b'W' as u32, 24);

// Memory permission flags for memory regions
pub const MEM_READABLE: u32 = 0b00000000000000000000000000000001;
pub const MEM_WRITABLE: u32 = 0b00000000000000000000000000000010;
pub const MEM_EXECUTABLE: u32 = 0b00000000000000000000000000000100;
pub const MEM_SHARED: u32 = 0b00000000000000000000000000001000;

// Command structures matching kernel definitions

#[repr(C)]
pub struct WuwaAddrTranslateCmd {
    pub phy_addr: u64,
    pub pid: pid_t,
    pub va: usize,
}

#[repr(C)]
pub struct WuwaDebugInfoCmd {
    pub ttbr0_el1: u64,
    pub task_struct: u64,
    pub mm_struct: u64,
    pub pgd_addr: u64,
    pub pgd_phys_addr: u64,
    pub mm_asid: u64,
    pub mm_right: u32,
}

#[repr(C)]
pub struct WuwaAtS1e0rCmd {
    pub phy_addr: u64,
    pub pid: pid_t,
    pub va: usize,
}

#[repr(C)]
pub union PageUnion {
    pub mapcount: i32,
    pub page_type: u32,
}

#[repr(C)]
pub struct KernelPage {
    pub flags: u64,
    pub union_field: PageUnion,
    pub refcount: i32,
    pub phy_addr: u64,
}

#[repr(C)]
pub struct WuwaPageInfoCmd {
    pub pid: pid_t,
    pub va: usize,
    pub page: KernelPage,
}

#[repr(C)]
pub struct WuwaDmaBufCreateCmd {
    pub pid: pid_t,
    pub va: usize,
    pub size: size_t,
    pub fd: c_int,
}

#[repr(C)]
pub struct WuwaPteMappingCmd {
    pub pid: pid_t,
    pub start_addr: usize,
    pub num_pages: size_t,
    pub hide: c_int,
}

#[repr(C)]
pub struct WuwaPageTableWalkCmd {
    pub pid: pid_t,
    pub total_pte_count: u64,   // Total number of PTEs
    pub present_pte_count: u64, // Number of present (mapped) PTEs
    pub pmd_huge_count: u64,    // Number of PMD huge pages (2MB pages)
    pub pud_huge_count: u64,    // Number of PUD huge pages (1GB pages)
}

#[repr(C)]
pub struct WuwaCopyProcessCmd {
    pub pid: pid_t,
    pub fn_ptr: *mut c_void,
    pub child_stack: *mut c_void,
    pub child_stack_size: size_t,
    pub flags: u64,
    pub arg: *mut c_void,
    pub child_tid: *mut c_int,
}

#[repr(C)]
pub struct WuwaReadPhysicalMemoryCmd {
    pub pid: pid_t,
    pub src_va: usize,
    pub dst_va: usize,
    pub size: size_t,
    pub phy_addr: usize,
    pub page_status: *mut libc::c_ulong, // Optional: bitset for page read status tracking
}

#[repr(C)]
pub struct WuwaWritePhysicalMemoryCmd {
    pub pid: pid_t,
    pub src_va: usize,
    pub dst_va: usize,
    pub size: size_t,
    pub phy_addr: usize,
}

#[repr(C)]
pub struct WuwaReadMemoryCmd {
    pub pid: pid_t,
    pub src_va: usize,
    pub dst_va: usize,
    pub size: size_t,
    pub nbytes: size_t,
}

#[repr(C)]
pub struct WuwaWriteMemoryCmd {
    pub pid: pid_t,
    pub src_va: usize,
    pub dst_va: usize,
    pub size: size_t,
    pub nbytes: size_t,
}

#[repr(C)]
pub struct WuwaGetModuleBaseCmd {
    pub pid: pid_t,
    pub name: [u8; 256],
    pub base: usize,
    pub vm_flag: c_int,
}

#[repr(C)]
pub struct WuwaFindProcCmd {
    pub pid: pid_t,
    pub name: [u8; 256],
}

#[repr(C)]
pub struct WuwaIsProcAliveCmd {
    pub pid: pid_t,
    pub alive: i32,
}

#[repr(C)]
pub struct WuwaHideProcCmd {
    pub pid: pid_t,
    pub hide: i32,
}

#[repr(C)]
pub struct WuwaGiveRootCmd {
    pub result: i32,
}

#[repr(C)]
pub struct WuwaReadPhysicalMemoryIoremapCmd {
    pub pid: pid_t,
    pub src_va: usize,
    pub dst_va: usize,
    pub size: size_t,
    pub phy_addr: usize,
    pub prot: c_int,
}

#[repr(C)]
pub struct WuwaWritePhysicalMemoryIoremapCmd {
    pub pid: pid_t,
    pub src_va: usize,
    pub dst_va: usize,
    pub size: size_t,
    pub phy_addr: usize,
    pub prot: c_int,
}

#[repr(C)]
pub struct WuwaBindProcCmd {
    pub pid: pid_t,
    pub fd: c_int,
}

#[repr(C)]
pub struct WuwaListProcessesCmd {
    pub bitmap: *mut u8,
    pub bitmap_size: size_t,
    pub process_count: size_t,
}

#[derive(Debug, Clone)]
#[repr(C)]
pub struct WuwaGetProcInfoCmd {
    pub pid: pid_t,
    pub tgid: pid_t,
    pub name: [u8; 256],
    pub uid: libc::uid_t,
    pub ppid: pid_t,
    pub prio: c_int,
    pub rss: size_t,
}

#[repr(C)]
pub struct WuwaInstallDriverCmd {
    pub pid: pid_t,
    pub fd: c_int,
}

/// Single memory region entry
///
/// This struct is packed and matches the kernel layout exactly
#[repr(C, packed)]
pub struct WuwaMemRegionEntry {
    pub start: u64,       // Start address of the region
    pub end: u64,         // End address of the region
    pub type_: u32,       // Type flags: combination of MEM_* (permissions)
    pub _reserved: u32,   // Reserved for future use, must be 0
    pub name: [u8; 4096], // Region name (PATH_MAX), mangled format
}

/// Command structure for querying memory regions
#[repr(C)]
pub struct WuwaQueryMemRegionsCmd {
    pub pid: pid_t,          // Input: Target process ID
    pub start_va: u64,       // Input: Optional start address filter (0 = no filter)
    pub end_va: u64,         // Input: Optional end address filter (0 = no filter)
    pub fd: c_int,           // Output: File descriptor for mmap'ing results
    pub buffer_size: size_t, // Output: Size of the buffer in bytes
    pub entry_count: size_t, // Output: Number of entries in the buffer
}

/// Process information with memory usage statistics
///
/// Combines basic process information with page table statistics to provide
/// a complete view of process memory usage.
#[derive(Debug, Clone)]
#[repr(C)]
pub struct ProcessInfoWithMemory {
    pub info: WuwaGetProcInfoCmd,
    pub memory_size: u64,
    pub present_pte_count: u64,
    pub pmd_huge_count: u64,
    pub pud_huge_count: u64,
}

impl ProcessInfoWithMemory {
    /// Calculate total memory size from page statistics
    ///
    /// Note: Huge page sizes depend on base page size:
    /// - 4KB base:  PMD=2MB,   PUD=1GB
    /// - 16KB base: PMD=32MB,  PUD may not be supported
    /// - 64KB base: PMD=512MB, PUD may not be supported
    ///
    /// This function assumes standard ARM64 configuration with 4KB base pages.
    pub fn calculate_memory_size(present_pte: u64, pmd_huge: u64, pud_huge: u64) -> u64 {
        let page_size = unsafe { libc::sysconf(libc::_SC_PAGESIZE) } as u64;

        let (pmd_size, pud_size) = match page_size {
            4096 => (2 * 1024 * 1024, 1024 * 1024 * 1024), // 2MB, 1GB
            16384 => (32 * 1024 * 1024, 0),                // 32MB, not supported
            65536 => (512 * 1024 * 1024, 0),               // 512MB, not supported
            _ => (2 * 1024 * 1024, 1024 * 1024 * 1024),    // Default to 4KB standard
        };

        (present_pte * page_size) + (pmd_huge * pmd_size) + (pud_huge * pud_size)
    }
}

/// Memory type constants for ioremap operations
#[repr(i32)]
#[derive(Copy, Clone, Debug)]
pub enum WuwaMemoryType {
    Normal = 0,       // Normal cached memory
    NormalTagged = 1, // Normal with MTE tags
    NormalNc = 2,     // Non-cacheable
    NormalWt = 3,     // Write-through
    DeviceNGnRnE = 4, // Device memory, no gather/reorder/early-ack
    DeviceNGnRE = 5,  // Device memory, no gather/reorder
    DeviceGRE = 6,    // Device memory, gather/reorder/early-ack
    NormalINCOWB = 7, // Inner non-cacheable, outer write-back
}

// BindProc command structures
#[repr(C)]
pub struct BpReadMemoryCmd {
    pub src_va: usize, // Virtual address to read from
    pub dst_va: usize, // Virtual address to write to (userspace buffer)
    pub size: size_t,
    pub page_status: *mut libc::c_ulong, // Optional: bitset for page read status tracking (null = fail on first error)
}

#[repr(C)]
pub struct BpWriteMemoryCmd {
    pub src_va: usize, // Virtual address to read from (userspace buffer)
    pub dst_va: usize, // Virtual address to write to
    pub size: size_t,
}

// BindProc ioctl commands
const WUWA_BP_IOCTL_SET_MEMORY_PROT: Ioctl = _IOWR::<c_int>(b'B' as u32, 1);
const WUWA_BP_IOCTL_READ_MEMORY: Ioctl = _IOWR::<BpReadMemoryCmd>(b'B' as u32, 2);
const WUWA_BP_IOCTL_WRITE_MEMORY: Ioctl = _IOWR::<BpWriteMemoryCmd>(b'B' as u32, 3);

/// Page status bitmap for tracking read success/failure
///
/// Helper struct for managing page status bitmaps returned by read_physical_memory.
/// Each bit represents one page: 1 = successfully read, 0 = failed to read.
pub struct PageStatusBitmap {
    bitmap: Vec<libc::c_ulong>,
}

impl PageStatusBitmap {
    /// Initialize bitmap for given size
    ///
    /// # Arguments
    /// * `size` - Total size in bytes being read
    /// * `start_va` - Starting virtual address (may be unaligned)
    pub fn new(size: usize, start_va: usize) -> Self {
        let page_size = unsafe { libc::sysconf(libc::_SC_PAGESIZE) } as usize;
        let num_pages = ((start_va & (page_size - 1)) + size + page_size - 1) / page_size;
        let num_longs =
            (num_pages + (std::mem::size_of::<libc::c_ulong>() * 8 - 1)) / (std::mem::size_of::<libc::c_ulong>() * 8);

        Self {
            bitmap: vec![0; num_longs],
        }
    }

    /// Mark all pages as successfully read
    pub fn mark_all_success(&mut self) {
        for long in self.bitmap.iter_mut() {
            *long = !0;
        }
    }

    /// Mark a specific page as successfully read
    ///
    /// # Arguments
    /// * `page_index` - Page index (0-based)
    pub fn mark_success(&mut self, page_index: usize) {
        let long_idx = page_index / (std::mem::size_of::<libc::c_ulong>() * 8);
        let bit_idx = page_index % (std::mem::size_of::<libc::c_ulong>() * 8);

        if long_idx < self.bitmap.len() {
            self.bitmap[long_idx] |= 1u64 << bit_idx;
        }
    }

    /// Get mutable pointer to bitmap data for passing to kernel
    pub fn as_mut_ptr(&mut self) -> *mut libc::c_ulong {
        self.bitmap.as_mut_ptr()
    }

    /// Check if a specific page was successfully read
    ///
    /// # Arguments
    /// * `page_index` - Page index (0-based)
    pub fn is_page_success(&self, page_index: usize) -> bool {
        let long_idx = page_index / (std::mem::size_of::<libc::c_ulong>() * 8);
        let bit_idx = page_index % (std::mem::size_of::<libc::c_ulong>() * 8);

        if long_idx >= self.bitmap.len() {
            return false;
        }

        (self.bitmap[long_idx] & (1u64 << bit_idx)) != 0
    }

    /// Get total number of pages
    pub fn num_pages(&self) -> usize {
        self.bitmap.len() * std::mem::size_of::<libc::c_ulong>() * 8
    }

    /// Get number of successfully read pages
    pub fn success_count(&self) -> usize {
        self.bitmap.iter().map(|&bits| bits.count_ones() as usize).sum()
    }

    /// Get number of failed pages
    pub fn failure_count(&self) -> usize {
        self.num_pages() - self.success_count()
    }

    /// Get list of failed page indices
    pub fn failed_pages(&self) -> Vec<usize> {
        let mut result = Vec::new();
        for i in 0..self.num_pages() {
            if !self.is_page_success(i) {
                result.push(i);
            }
        }
        result
    }

    /// Get ranges of consecutive successful pages
    /// Returns Vec<(start_page_index, end_page_index)> where end is exclusive
    pub fn get_success_page_ranges(&self) -> Vec<(usize, usize)> {
        let mut ranges = Vec::new();
        let num_pages = self.num_pages();
        let mut range_start: Option<usize> = None;

        for i in 0..num_pages {
            if self.is_page_success(i) {
                if range_start.is_none() {
                    range_start = Some(i);
                }
            } else if let Some(start) = range_start {
                ranges.push((start, i));
                range_start = None;
            }
        }

        if let Some(start) = range_start {
            ranges.push((start, num_pages));
        }

        ranges
    }
}

/// Bound process handle for efficient memory access
///
/// Wraps a file descriptor returned by bind_process(). Provides:
/// - Efficient reads via cached ioremap pages
/// - Configurable memory type (cached/device/etc)
/// - RAII fd management
pub struct BindProc {
    fd: OwnedFd,
}

impl BindProc {
    /// Create from raw file descriptor
    pub fn from_fd(fd: c_int) -> Result<Self, anyhow::Error> {
        if fd < 0 {
            return Err(anyhow!("Invalid file descriptor"));
        }
        Ok(Self {
            fd: unsafe { OwnedFd::from_raw_fd(fd) },
        })
    }

    /// Read from target process virtual address
    /// Uses kernel-side ioremap with page caching for efficiency
    ///
    /// # Arguments
    /// * `va` - Virtual address in target process
    /// * `buf` - Destination buffer
    /// * `size` - Number of bytes to read (max 64KB)
    pub fn read_memory(
        &self,
        va: usize,
        buf: &mut [u8],
        page_status: Option<&mut PageStatusBitmap>,
    ) -> Result<(), anyhow::Error> {
        let mut cmd = BpReadMemoryCmd {
            src_va: va,
            dst_va: buf.as_mut_ptr() as usize,
            size: buf.len(),
            page_status: match page_status {
                Some(bitmap) => bitmap.as_mut_ptr(),
                None => std::ptr::null_mut(),
            },
        };

        unsafe {
            let result = ioctl(
                self.fd.as_raw_fd(),
                WUWA_BP_IOCTL_READ_MEMORY,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("BindProc read failed: va=0x{:x} size={}", va, buf.len()));
            }
        }

        Ok(())
    }

    /// Write to target process virtual address
    ///
    /// # Arguments
    /// * `va` - Virtual address in target process
    /// * `buf` - Source buffer
    /// * `size` - Number of bytes to write (max 64KB)
    pub fn write_memory(&self, va: usize, buf: &[u8]) -> Result<(), anyhow::Error> {
        let mut cmd = BpWriteMemoryCmd {
            src_va: buf.as_ptr() as usize,
            dst_va: va,
            size: buf.len(),
        };

        unsafe {
            let result = ioctl(
                self.fd.as_raw_fd(),
                WUWA_BP_IOCTL_WRITE_MEMORY,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("BindProc write failed: va=0x{:x} size={}", va, buf.len()));
            }
        }

        Ok(())
    }

    /// Type-safe read from target process
    pub fn read<T: Sized>(
        &self,
        va: usize,
        page_status_bitmap: Option<&mut PageStatusBitmap>,
    ) -> Result<T, anyhow::Error> {
        let mut buffer: MaybeUninit<T> = MaybeUninit::uninit();
        let buffer_ptr = buffer.as_mut_ptr() as usize;
        let size = size_of::<T>();

        let mut cmd = BpReadMemoryCmd {
            src_va: va,
            dst_va: buffer_ptr,
            size,
            page_status: match page_status_bitmap {
                Some(bitmap) => bitmap.as_mut_ptr(),
                None => std::ptr::null_mut(),
            },
        };

        unsafe {
            let result = ioctl(
                self.fd.as_raw_fd(),
                WUWA_BP_IOCTL_READ_MEMORY,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("BindProc read failed: va=0x{:x} size={}", va, size));
            }
            Ok(buffer.assume_init())
        }
    }

    /// Type-safe write to target process
    pub fn write<T: Sized>(&self, va: usize, value: &T) -> Result<(), anyhow::Error> {
        let value_ptr = value as *const T as usize;
        let size = size_of::<T>();

        let mut cmd = BpWriteMemoryCmd {
            src_va: value_ptr,
            dst_va: va,
            size,
        };

        unsafe {
            let result = ioctl(
                self.fd.as_raw_fd(),
                WUWA_BP_IOCTL_WRITE_MEMORY,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("BindProc write failed: va=0x{:x} size={}", va, size));
            }
        }

        Ok(())
    }

    /// Set memory type for future ioremap operations
    ///
    /// # Arguments
    /// * `mem_type` - One of WuwaMemoryType constants
    pub fn set_memory_type(&self, mem_type: WuwaMemoryType) -> Result<(), anyhow::Error> {
        let mut prot = mem_type as c_int;

        unsafe {
            let result = ioctl(
                self.fd.as_raw_fd(),
                WUWA_BP_IOCTL_SET_MEMORY_PROT,
                &mut prot as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("Set memory type failed"));
            }
        }

        Ok(())
    }

    /// Get underlying file descriptor (for advanced use)
    pub fn raw_fd(&self) -> c_int {
        self.fd.as_raw_fd()
    }
}

/// Memory region query result
#[derive(Debug, Clone)]
pub struct MemRegionsResult {
    pub fd: c_int,           // File descriptor for accessing regions
    pub buffer_size: size_t, // Total buffer size in bytes
    pub entry_count: size_t, // Number of region entries
}

/// WuWa driver connection handle
pub struct WuWaDriver {
    sock: OwnedFd,
}

impl WuWaDriver {
    /// Discover driver by probing address families
    fn driver_id() -> Result<OwnedFd, anyhow::Error> {
        let address_families = [
            AddressFamily::Decnet,
            AddressFamily::NetBeui,
            AddressFamily::Security,
            AddressFamily::Key,
            AddressFamily::Netlink,
            AddressFamily::Packet,
            AddressFamily::Ash,
            AddressFamily::Econet,
            AddressFamily::AtmSvc,
            AddressFamily::Rds,
            AddressFamily::Sna,
            AddressFamily::Irda,
            AddressFamily::Pppox,
            AddressFamily::Wanpipe,
            AddressFamily::Llc,
            AddressFamily::Can,
            AddressFamily::Tipc,
            AddressFamily::Bluetooth,
            AddressFamily::Iucv,
            AddressFamily::RxRpc,
            AddressFamily::Isdn,
            AddressFamily::Phonet,
            AddressFamily::Ieee802154,
            AddressFamily::Caif,
            AddressFamily::Alg,
            AddressFamily::Vsock,
        ];

        for af in address_families.iter() {
            match socket(*af, SockType::SeqPacket, SockFlag::empty(), None) {
                Ok(_) => continue,
                Err(Errno::ENOKEY) => match socket(*af, SockType::Raw, SockFlag::empty(), None) {
                    Ok(fd) => {
                        if log_enabled!(Level::Debug) {
                            debug!("WuWa driver found on {:?}", af);
                        }
                        return Ok(fd);
                    },
                    Err(_) => continue,
                },
                Err(_) => continue,
            }
        }
        Err(anyhow!("WuWa driver not found"))
    }

    /// Connect to WuWa driver. Requires root or CAP_NET_RAW.
    pub fn new() -> Result<Self, anyhow::Error> {
        let sock = Self::driver_id()?;
        Ok(Self { sock })
    }

    /// Create WuWaDriver from existing file descriptor
    ///
    /// Takes ownership of the provided fd. Useful for creating driver instances
    /// from file descriptors returned by install_driver() or other sources.
    ///
    /// # Arguments
    /// * `fd` - File descriptor (ownership transferred to WuWaDriver)
    ///
    /// # Returns
    /// WuWaDriver instance that will close fd on destruction
    pub fn from_fd(fd: c_int) -> Self {
        Self {
            sock: unsafe { OwnedFd::from_raw_fd(fd) },
        }
    }

    /// Software page table walk: VA -> PA translation
    pub fn addr_translate(&self, pid: pid_t, va: usize) -> Result<u64, anyhow::Error> {
        let mut cmd = WuwaAddrTranslateCmd { phy_addr: 0, pid, va };

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_ADDR_TRANSLATE,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("VA->PA translation failed"));
            }
        }

        Ok(cmd.phy_addr)
    }

    /// Get process debug info (TTBR0, task_struct, mm_struct, pgd)
    pub fn get_debug_info(&self, pid: pid_t) -> Result<WuwaDebugInfoCmd, anyhow::Error> {
        let mut cmd = WuwaDebugInfoCmd {
            ttbr0_el1: 0,
            task_struct: 0,
            mm_struct: 0,
            pgd_addr: 0,
            pgd_phys_addr: 0,
            mm_asid: 0,
            mm_right: 0,
        };

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_DEBUG_INFO,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("Failed to get debug info"));
            }
        }

        Ok(cmd)
    }

    /// Hardware AT S1E0R instruction: VA -> PA (faster than software walk)
    pub fn at_s1e0r(&self, pid: pid_t, va: usize) -> Result<u64, anyhow::Error> {
        let mut cmd = WuwaAtS1e0rCmd { phy_addr: 0, pid, va };

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_AT_S1E0R,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("AT S1E0R failed"));
            }
        }

        Ok(cmd.phy_addr)
    }

    /// Query page flags, refcount, mapcount at VA
    pub fn get_page_info(&self, pid: pid_t, va: usize) -> Result<KernelPage, anyhow::Error> {
        let mut cmd = WuwaPageInfoCmd {
            pid,
            va,
            page: KernelPage {
                flags: 0,
                union_field: PageUnion { mapcount: 0 },
                refcount: 0,
                phy_addr: 0,
            },
        };

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_PAGE_INFO,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("Failed to get page info"));
            }
        }

        Ok(cmd.page)
    }

    /// Export process memory region as dma-buf fd for zero-copy sharing
    pub fn create_dma_buf(&self, pid: pid_t, va: usize, size: size_t) -> Result<c_int, anyhow::Error> {
        let mut cmd = WuwaDmaBufCreateCmd { pid, va, size, fd: -1 };

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_DMA_BUF_CREATE,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("DMA-BUF creation failed"));
            }
        }

        Ok(cmd.fd)
    }

    /// Direct PTE manipulation (hide/unhide pages)
    pub fn pte_mapping(
        &self,
        pid: pid_t,
        start_addr: usize,
        num_pages: size_t,
        hide: bool,
    ) -> Result<(), anyhow::Error> {
        let mut cmd = WuwaPteMappingCmd {
            pid,
            start_addr,
            num_pages,
            hide: if hide { 1 } else { 0 },
        };

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_PTE_MAPPING,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("PTE mapping failed"));
            }
        }

        Ok(())
    }

    /// Walk page tables and collect statistics
    ///
    /// Traverses the page tables for the target process and collects statistics
    /// about page table entries including total PTEs, present PTEs, and huge pages.
    ///
    /// # Arguments
    /// * `pid` - Target process ID
    ///
    /// # Returns
    /// WuwaPageTableWalkCmd with statistics on success
    pub fn page_table_walk(&self, pid: pid_t) -> Result<WuwaPageTableWalkCmd, anyhow::Error> {
        let mut cmd = WuwaPageTableWalkCmd {
            pid,
            total_pte_count: 0,
            present_pte_count: 0,
            pmd_huge_count: 0,
            pud_huge_count: 0,
        };

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_PAGE_TABLE_WALK,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("Page table walk failed"));
            }
        }

        Ok(cmd)
    }

    /// Read physical memory via phys_to_virt (max 50MB per call)
    pub fn read_physical_memory(
        &self,
        pid: pid_t,
        src_va: usize,
        dst_va: usize,
        size: size_t,
    ) -> Result<usize, anyhow::Error> {
        let mut cmd = WuwaReadPhysicalMemoryCmd {
            pid,
            src_va,
            dst_va,
            size,
            phy_addr: 0,
            page_status: std::ptr::null_mut(),
        };

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_READ_PHYSICAL_MEMORY,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("Physical memory read failed: va=0x{:x} size={}", src_va, size));
            }
        }

        Ok(cmd.phy_addr)
    }

    /// Read physical memory with page status tracking
    ///
    /// Reads memory from target process and tracks which pages were successfully read.
    /// When a page translation fails (page not present), the read continues to the next
    /// page instead of failing immediately. The bitmap indicates which pages succeeded.
    ///
    /// # Arguments
    /// * `pid` - Target process ID
    /// * `src_va` - Source virtual address in target process
    /// * `dst_va` - Destination buffer address
    /// * `size` - Number of bytes to read (max 50MB)
    /// * `status` - PageStatusBitmap to receive per-page success/failure status
    ///
    /// # Returns
    /// Physical address of first page on success
    ///
    /// # Example
    /// ```no_run
    /// let mut buffer = vec![0u8; 1024 * 1024];  // 1MB
    /// let mut status = PageStatusBitmap::new(buffer.len(), src_va);
    ///
    /// let pa = driver.read_physical_memory_with_status(
    ///     pid, src_va, buffer.as_mut_ptr() as usize, buffer.len(), &mut status
    /// )?;
    ///
    /// println!("Read {}/{} pages successfully",
    ///          status.success_count(), status.num_pages());
    ///
    /// for failed in status.failed_pages() {
    ///     println!("  Page {} failed", failed);
    /// }
    /// ```
    pub fn read_physical_memory_with_status(
        &self,
        pid: pid_t,
        src_va: usize,
        dst_va: usize,
        size: size_t,
        status: &mut PageStatusBitmap,
    ) -> Result<usize, anyhow::Error> {
        let mut cmd = WuwaReadPhysicalMemoryCmd {
            pid,
            src_va,
            dst_va,
            size,
            phy_addr: 0,
            page_status: status.as_mut_ptr(),
        };

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_READ_PHYSICAL_MEMORY,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                let errno = Errno::last();
                return Err(anyhow!(
                    "read_physical_memory_with_status failed: va=0x{:x} size={}, result={}, errno={} ({})",
                    src_va,
                    size,
                    result,
                    errno,
                    errno
                ));
            }
        }

        Ok(cmd.phy_addr)
    }

    /// Write physical memory via phys_to_virt (max 50MB per call)
    pub fn write_physical_memory(
        &self,
        pid: pid_t,
        src_va: usize,
        dst_va: usize,
        size: size_t,
    ) -> Result<usize, anyhow::Error> {
        let mut cmd = WuwaWritePhysicalMemoryCmd {
            pid,
            src_va,
            dst_va,
            size,
            phy_addr: 0,
        };

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_WRITE_PHYSICAL_MEMORY,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("Physical memory write failed: va=0x{:x} size={}", src_va, size));
            }
        }

        Ok(cmd.phy_addr)
    }

    /// Read memory via get_user_pages_remote (triggers page faults, handles swapped pages)
    pub fn read_memory(&self, pid: pid_t, src_va: usize, dst_va: usize, size: size_t) -> Result<size_t, anyhow::Error> {
        let mut cmd = WuwaReadMemoryCmd {
            pid,
            src_va,
            dst_va,
            size,
            nbytes: 0,
        };

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_READ_MEMORY,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("Memory read failed: va=0x{:x} size={}", src_va, size));
            }
        }

        Ok(cmd.nbytes)
    }

    /// Write memory via get_user_pages_remote (triggers page faults, handles swapped pages)
    pub fn write_memory(
        &self,
        pid: pid_t,
        src_va: usize,
        dst_va: usize,
        size: size_t,
    ) -> Result<size_t, anyhow::Error> {
        let mut cmd = WuwaWriteMemoryCmd {
            pid,
            src_va,
            dst_va,
            size,
            nbytes: 0,
        };

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_WRITE_MEMORY,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("Memory write failed: va=0x{:x} size={}", src_va, size));
            }
        }

        Ok(cmd.nbytes)
    }

    /// Type-safe read of arbitrary struct from target process
    pub fn read<T: Sized>(&self, pid: pid_t, src_va: usize) -> Result<T, anyhow::Error> {
        let mut buffer: MaybeUninit<T> = MaybeUninit::uninit();
        let buffer_ptr = buffer.as_mut_ptr() as usize;
        let size = size_of::<T>();

        self.read_physical_memory(pid, src_va, buffer_ptr, size)?;

        unsafe { Ok(buffer.assume_init()) }
    }

    /// Type-safe write of arbitrary struct to target process
    pub fn write<T: Sized>(&self, pid: pid_t, dst_va: usize, value: &T) -> Result<(), anyhow::Error> {
        let value_ptr = value as *const T as usize;
        let size = size_of::<T>();

        self.write_physical_memory(pid, value_ptr, dst_va, size)?;

        Ok(())
    }

    /// Read Unreal Engine FString from target process
    pub fn read_fstring(&self, pid: pid_t, addr: usize) -> Result<String, anyhow::Error> {
        let len = self.read::<u32>(pid, addr + 8)? as usize;
        if len == 0 {
            return Ok("".to_string());
        }
        let ue_name = self.read::<usize>(pid, addr)?;
        let mut tmp = vec![];
        unsafe {
            self.read_to_utf8(pid, ue_name as *const u16, &mut tmp, len - 1)?;
        }
        String::from_utf8(tmp).map_err(|e| anyhow!("FString decode failed: {:?}", e))
    }

    /// Read FString with length limit
    pub fn read_fstring_limit(&self, pid: pid_t, addr: usize, max_len: usize) -> Result<String, anyhow::Error> {
        let len = self.read::<u32>(pid, addr + 8)? as usize;
        if len == 0 {
            return Ok("".to_string());
        }

        if len > max_len {
            return Err(anyhow!("FString length {} exceeds limit {}", len, max_len));
        }

        let ue_name = self.read::<usize>(pid, addr)?;
        let mut tmp = vec![];
        unsafe {
            self.read_to_utf8(pid, ue_name as *const u16, &mut tmp, len - 1)?;
        }
        String::from_utf8(tmp).map_err(|e| anyhow!("FString decode failed: {:?}", e))
    }

    /// Convert UTF-16 in target process to UTF-8 in local buffer
    pub unsafe fn read_to_utf8(
        &self,
        pid: pid_t,
        ptr: *const u16,
        buf: &mut Vec<u8>,
        length: usize,
    ) -> Result<(), anyhow::Error> {
        let mut temp_utf16 = ptr;
        let end = ptr.add(length);

        while temp_utf16 < end {
            let utf16_char = self.read::<u16>(pid, temp_utf16 as usize)?;

            if utf16_char <= 0x007F {
                buf.push(utf16_char as u8);
            } else if utf16_char <= 0x07FF {
                buf.push(((utf16_char >> 6) | 0xC0) as u8);
                buf.push(((utf16_char & 0x3F) | 0x80) as u8);
            } else {
                buf.push(((utf16_char >> 12) | 0xE0) as u8);
                buf.push(((utf16_char >> 6 & 0x3F) | 0x80) as u8);
                buf.push(((utf16_char & 0x3F) | 0x80) as u8);
            }

            temp_utf16 = temp_utf16.add(1);
        }
        Ok(())
    }

    /// Get module/library base address in target process
    ///
    /// # Arguments
    /// * `vm_flag` - VM flags to filter (e.g., 0x4 for VM_EXEC)
    pub fn get_module_base(&self, pid: pid_t, name: &str, vm_flag: c_int) -> Result<usize, anyhow::Error> {
        let mut cmd = WuwaGetModuleBaseCmd {
            pid,
            name: [0; 256],
            base: 0,
            vm_flag,
        };

        let name_bytes = name.as_bytes();
        let copy_len = std::cmp::min(name_bytes.len(), 255);
        cmd.name[..copy_len].copy_from_slice(&name_bytes[..copy_len]);

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_GET_MODULE_BASE,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("Module base query failed"));
            }
        }

        Ok(cmd.base)
    }

    /// Find process by name, returns PID
    pub fn find_process(&self, name: &str) -> Result<pid_t, anyhow::Error> {
        let mut cmd = WuwaFindProcCmd { pid: 0, name: [0; 256] };

        let name_bytes = name.as_bytes();
        let copy_len = std::cmp::min(name_bytes.len(), 255);
        cmd.name[..copy_len].copy_from_slice(&name_bytes[..copy_len]);

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_FIND_PROCESS,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("Process search failed"));
            }
        }

        if cmd.pid == 0 {
            return Err(anyhow!("Process not found"));
        }

        Ok(cmd.pid)
    }

    /// Check if process is alive
    pub fn is_process_alive(&self, pid: pid_t) -> Result<bool, anyhow::Error> {
        let mut cmd = WuwaIsProcAliveCmd { pid, alive: 0 };

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_IS_PROCESS_ALIVE,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("Process liveness check failed"));
            }
        }

        Ok(cmd.alive != 0)
    }

    /// Hide/unhide process from system visibility
    pub fn hide_process(&self, pid: pid_t, hide: bool) -> Result<(), anyhow::Error> {
        let mut cmd = WuwaHideProcCmd {
            pid,
            hide: if hide { 1 } else { 0 },
        };

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_HIDE_PROCESS,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("Process hide/unhide failed"));
            }
        }

        Ok(())
    }

    /// Escalate current process to root (uid=0, gid=0)
    pub fn give_root(&self) -> Result<(), anyhow::Error> {
        let mut cmd = WuwaGiveRootCmd { result: 0 };

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_GIVE_ROOT,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("Root escalation failed"));
            }
        }

        if cmd.result < 0 {
            return Err(anyhow!("Root escalation rejected: error {}", cmd.result));
        }

        Ok(())
    }

    /// Read physical memory via ioremap with memory attribute control
    ///
    /// # Arguments
    /// * `prot` - Memory type (use MT_* constants from kernel)
    pub fn read_physical_memory_ioremap(
        &self,
        pid: pid_t,
        src_va: usize,
        dst_va: usize,
        size: size_t,
        prot: c_int,
    ) -> Result<usize, anyhow::Error> {
        let mut cmd = WuwaReadPhysicalMemoryIoremapCmd {
            pid,
            src_va,
            dst_va,
            size,
            phy_addr: 0,
            prot,
        };

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_READ_MEMORY_IOREMAP,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("ioremap read failed: va=0x{:x} size={}", src_va, size));
            }
        }

        Ok(cmd.phy_addr)
    }

    /// Write physical memory via ioremap with memory attribute control
    ///
    /// # Arguments
    /// * `prot` - Memory type (use MT_* constants from kernel)
    pub fn write_physical_memory_ioremap(
        &self,
        pid: pid_t,
        src_va: usize,
        dst_va: usize,
        size: size_t,
        prot: c_int,
    ) -> Result<usize, anyhow::Error> {
        let mut cmd = WuwaWritePhysicalMemoryIoremapCmd {
            pid,
            src_va,
            dst_va,
            size,
            phy_addr: 0,
            prot,
        };

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_WRITE_MEMORY_IOREMAP,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("ioremap write failed: va=0x{:x} size={}", src_va, size));
            }
        }

        Ok(cmd.phy_addr)
    }

    /// Bind process for efficient memory access
    ///
    /// Returns a BindProc handle that uses kernel-side ioremap with page caching.
    /// More efficient than repeated read_physical_memory() calls for sequential access.
    ///
    /// # Arguments
    /// * `pid` - Target process ID
    ///
    /// # Returns
    /// BindProc object on success
    pub fn bind_process(&self, pid: pid_t) -> Result<BindProc, anyhow::Error> {
        let mut cmd = WuwaBindProcCmd { pid, fd: -1 };

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_BIND_PROC,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("Process bind failed"));
            }
        }

        BindProc::from_fd(cmd.fd)
    }

    /// Copy process with custom function pointer and stack
    pub fn copy_process(
        &self,
        pid: pid_t,
        fn_ptr: *mut c_void,
        child_stack: *mut c_void,
        child_stack_size: size_t,
        flags: u64,
        arg: *mut c_void,
    ) -> Result<c_int, anyhow::Error> {
        let mut cmd = WuwaCopyProcessCmd {
            pid,
            fn_ptr,
            child_stack,
            child_stack_size,
            flags,
            arg,
            child_tid: std::ptr::null_mut(),
        };

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_COPY_PROCESS,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("Process copy failed"));
            }
        }

        Ok(0)
    }

    /// List all processes in the system using bitmap
    ///
    /// Returns a vector of PIDs for all running processes. Uses an efficient
    /// bitmap representation (8KB) to retrieve process list from kernel.
    ///
    /// # Returns
    /// Vector of PIDs on success, empty vector on failure
    pub fn list_processes(&self) -> Vec<pid_t> {
        const BITMAP_SIZE: usize = 8192; // Support PID 0-65535
        let mut bitmap = vec![0u8; BITMAP_SIZE];

        let mut cmd = WuwaListProcessesCmd {
            bitmap: bitmap.as_mut_ptr(),
            bitmap_size: BITMAP_SIZE,
            process_count: 0,
        };

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_LIST_PROCESSES,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Vec::new();
            }
        }

        // Parse bitmap and extract PIDs
        let mut pids = Vec::with_capacity(cmd.process_count);

        for pid in 0..(BITMAP_SIZE * 8) {
            let byte_idx = pid / 8;
            let bit_idx = pid % 8;

            if bitmap[byte_idx] & (1 << bit_idx) != 0 {
                pids.push(pid as pid_t);
            }
        }

        pids
    }

    /// Get detailed process information by PID
    ///
    /// Retrieves process information including name, TGID, UID, PPID, priority, and RssAnon.
    ///
    /// # Arguments
    /// * `pid` - Process ID to query
    ///
    /// # Returns
    /// WuwaGetProcInfoCmd struct on success
    pub fn get_process_info(&self, pid: pid_t) -> Result<WuwaGetProcInfoCmd, anyhow::Error> {
        let mut cmd = WuwaGetProcInfoCmd {
            pid,
            tgid: 0,
            name: [0; 256],
            uid: 0,
            ppid: 0,
            prio: 0,
            rss: 0,
        };

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_GET_PROC_INFO,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("Get process info failed"));
            }
        }

        Ok(cmd)
    }

    /// Install driver for a process
    ///
    /// Creates a driver instance for the specified process. The returned file
    /// descriptor can be used with from_fd() to create a new WuWaDriver instance.
    ///
    /// # Example
    /// ```no_run
    /// let fd = driver.install_driver(pid)?;
    /// let new_driver = WuWaDriver::from_fd(fd);
    /// // Use new_driver for operations on the target process
    /// ```
    ///
    /// # Arguments
    /// * `pid` - Process ID to install driver for
    ///
    /// # Returns
    /// File descriptor on success
    pub fn install_driver(&self, pid: pid_t) -> Result<c_int, anyhow::Error> {
        let mut cmd = WuwaInstallDriverCmd { pid, fd: -1 };

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_INSTALL_DRIVER,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("Install driver failed"));
            }
        }

        if cmd.fd < 0 {
            return Err(anyhow!("Install driver returned invalid fd"));
        }

        Ok(cmd.fd)
    }

    /// List all processes with detailed information
    ///
    /// This is an improved version that retrieves both PIDs and detailed information
    /// in a single call. Automatically filters out processes with empty names.
    /// Results are sorted by priority (lower priority value = higher priority = appears first).
    ///
    /// # Returns
    /// Vector of process information structs sorted by priority, empty vector on failure
    pub fn list_processes_with_info(&self) -> Vec<WuwaGetProcInfoCmd> {
        let pids = self.list_processes();
        if pids.is_empty() {
            return Vec::new();
        }

        let mut result = Vec::with_capacity(pids.len());

        // Fetch detailed info for each PID
        for pid in pids {
            if let Ok(info) = self.get_process_info(pid) {
                // Skip processes with empty names
                if info.name[0] != 0 {
                    result.push(info);
                }
            }
        }

        // Sort by priority (lower value = higher priority)
        result.sort_by(|a, b| a.prio.cmp(&b.prio));

        result
    }

    /// Query memory regions of a target process
    ///
    /// Returns file descriptor, buffer size, and entry count for accessing memory regions.
    /// All regions in the specified address range are returned.
    ///
    /// # Usage
    ///
    /// ```no_run
    /// let result = driver.query_mem_regions(pid, 0, 0)?;
    /// println!("Found {} regions, buffer size: {} bytes",
    ///          result.entry_count, result.buffer_size);
    ///
    /// // Map the regions into memory
    /// let regions = unsafe {
    ///     mmap(
    ///         std::ptr::null_mut(),
    ///         result.buffer_size,
    ///         libc::PROT_READ,
    ///         libc::MAP_PRIVATE,
    ///         result.fd,
    ///         0
    ///     ) as *const WuwaMemRegionEntry
    /// };
    ///
    /// if regions == libc::MAP_FAILED as *const _ {
    ///     return Err(anyhow!("mmap failed"));
    /// }
    ///
    /// // Iterate through regions
    /// for i in 0..result.entry_count {
    ///     let region = unsafe { &*regions.add(i) };
    ///     println!("Region {}: 0x{:016x}-0x{:016x} [{}{}{}{}] {}",
    ///              i, region.start, region.end,
    ///              if region.type_ & MEM_READABLE != 0 { 'r' } else { '-' },
    ///              if region.type_ & MEM_WRITABLE != 0 { 'w' } else { '-' },
    ///              if region.type_ & MEM_EXECUTABLE != 0 { 'x' } else { '-' },
    ///              if region.type_ & MEM_SHARED != 0 { 's' } else { 'p' },
    ///              std::str::from_utf8(&region.name).unwrap_or("(invalid)"));
    /// }
    ///
    /// unsafe {
    ///     libc::munmap(regions as *mut _, result.buffer_size);
    ///     libc::close(result.fd);
    /// }
    /// ```
    ///
    /// # Arguments
    /// * `pid` - Target process ID
    /// * `start_va` - Optional start address filter (0 = no filter)
    /// * `end_va` - Optional end address filter (0 = no filter)
    ///
    /// # Returns
    /// MemRegionsResult on success containing fd, buffer_size, and entry_count
    pub fn query_mem_regions(&self, pid: pid_t, start_va: u64, end_va: u64) -> Result<MemRegionsResult, anyhow::Error> {
        let mut cmd = WuwaQueryMemRegionsCmd {
            pid,
            start_va,
            end_va,
            fd: -1,
            buffer_size: 0,
            entry_count: 0,
        };

        unsafe {
            let result = ioctl(
                self.sock.as_raw_fd(),
                WUWA_IOCTL_QUERY_MEM_REGIONS,
                &mut cmd as *mut _ as *mut c_void,
            );
            if result < 0 {
                return Err(anyhow!("Query memory regions failed"));
            }
        }

        if cmd.fd < 0 {
            return Err(anyhow!("Query memory regions returned invalid fd"));
        }

        Ok(MemRegionsResult {
            fd: cmd.fd,
            buffer_size: cmd.buffer_size,
            entry_count: cmd.entry_count,
        })
    }
}
