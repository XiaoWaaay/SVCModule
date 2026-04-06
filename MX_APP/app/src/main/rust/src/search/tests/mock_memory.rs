//! Mock memory emulator for testing search functionality
//!
//! Provides a simple memory emulator similar to Unicorn, with:
//! - malloc: Allocate memory regions
//! - mem_write: Write data to memory
//! - mem_read: Read data from memory
//! - Configurable page fault simulation

use crate::wuwa::PageStatusBitmap;
use anyhow::{anyhow, Result};
use std::collections::BTreeMap;
use std::ops::Not;

const DEFAULT_PAGE_SIZE: usize = 4096;

/// Memory region with data and access flags
#[derive(Debug, Clone)]
struct MemoryRegion {
    start: u64,
    size: usize,
    data: Vec<u8>,
    readable: bool,
    writable: bool,
    faulty_pages: Vec<usize>, // List of page indices that should fail
}

/// Mock memory emulator for testing
pub struct MockMemory {
    regions: BTreeMap<u64, MemoryRegion>,
    page_size: usize,
}

impl MockMemory {
    /// Create a new mock memory emulator
    pub fn new() -> Self {
        Self {
            regions: BTreeMap::new(),
            page_size: DEFAULT_PAGE_SIZE,
        }
    }

    /// Allocate a memory region at the specified address
    ///
    /// # Arguments
    /// * `addr` - Starting address (will be page-aligned)
    /// * `size` - Size in bytes
    ///
    /// # Returns
    /// The actual allocated address (page-aligned)
    pub fn malloc(&mut self, addr: u64, size: usize) -> Result<u64> {
        let aligned_addr = addr & !(self.page_size as u64 - 1);
        let aligned_size = ((size + self.page_size - 1) / self.page_size) * self.page_size;

        // Check for overlapping regions
        for (start, region) in self.regions.iter() {
            let region_end = start + region.size as u64;
            let new_end = aligned_addr + aligned_size as u64;

            if aligned_addr < region_end && new_end > *start {
                return Err(anyhow!("Memory region overlaps with existing region at 0x{:X}", start));
            }
        }

        let region = MemoryRegion {
            start: aligned_addr,
            size: aligned_size,
            data: vec![0u8; aligned_size],
            readable: true,
            writable: true,
            faulty_pages: Vec::new(),
        };

        self.regions.insert(aligned_addr, region);
        Ok(aligned_addr)
    }

    /// Write data to memory
    ///
    /// # Arguments
    /// * `addr` - Starting address
    /// * `data` - Data to write
    pub fn mem_write(&mut self, addr: u64, data: &[u8]) -> Result<()> {
        let region = self.find_region_mut(addr, data.len())?;

        if !region.writable {
            return Err(anyhow!("Memory region at 0x{:X} is not writable", addr));
        }

        let offset = (addr - region.start) as usize;
        region.data[offset..offset + data.len()].copy_from_slice(data);
        Ok(())
    }

    /// Write a value to memory (little-endian)
    pub fn mem_write_u32(&mut self, addr: u64, value: u32) -> Result<()> {
        self.mem_write(addr, &value.to_le_bytes())
    }

    /// Write a value to memory (little-endian)
    pub fn mem_write_u64(&mut self, addr: u64, value: u64) -> Result<()> {
        self.mem_write(addr, &value.to_le_bytes())
    }

    /// Write a value to memory (little-endian)
    pub fn mem_write_i32(&mut self, addr: u64, value: i32) -> Result<()> {
        self.mem_write(addr, &value.to_le_bytes())
    }

    /// Write a value to memory (little-endian)
    pub fn mem_write_f32(&mut self, addr: u64, value: f32) -> Result<()> {
        self.mem_write(addr, &value.to_le_bytes())
    }

    /// Write a value to memory (little-endian)
    pub fn mem_write_f64(&mut self, addr: u64, value: f64) -> Result<()> {
        self.mem_write(addr, &value.to_le_bytes())
    }

    /// Read data from memory
    ///
    /// # Arguments
    /// * `addr` - Starting address
    /// * `size` - Number of bytes to read
    pub fn mem_read(&self, addr: u64, size: usize) -> Result<Vec<u8>> {
        let region = self.find_region(addr, size)?;

        if !region.readable {
            return Err(anyhow!("Memory region at 0x{:X} is not readable", addr));
        }

        let offset = (addr - region.start) as usize;
        Ok(region.data[offset..offset + size].to_vec())
    }

    /// Read data from memory with page fault simulation
    ///
    /// # Arguments
    /// * `addr` - Starting address
    /// * `buf` - Buffer to read into
    /// * `page_status` - Page status bitmap to track successful/failed pages
    pub fn mem_read_with_status(&self, addr: u64, buf: &mut [u8], page_status: &mut PageStatusBitmap) -> Result<()> {
        let region = self.find_region(addr, buf.len())?;

        if !region.readable {
            return Err(anyhow!("Memory region at 0x{:X} is not readable", addr));
        }

        let offset = (addr - region.start) as usize;
        let page_mask = !(self.page_size - 1);

        // Track which pages we've processed
        let mut page_index = 0usize;
        let mut last_page_va = u64::MAX;

        for i in 0..buf.len() {
            let current_addr = addr + i as u64;
            let page_va = current_addr & page_mask as u64;

            // Update page index when moving to a new page
            if page_va != last_page_va && last_page_va != u64::MAX {
                page_index += 1;
            }
            let last_page_eq_cur_page_va = last_page_va == page_va;
            last_page_va = page_va;

            // Check if this page is faulty
            let page_offset = (page_va - region.start) / self.page_size as u64;
            let is_faulty = region.faulty_pages.contains(&(page_offset as usize));

            if is_faulty {
                if last_page_eq_cur_page_va.not() {
                    println!("\t [!] 失败页号: {} (地址: 0x{:X})", page_offset, page_va);
                }
                buf[i] = 0; // Fill with zeros on fault
            } else {
                buf[i] = region.data[offset + i];
                if i == 0 || (current_addr & page_mask as u64) != ((addr + i as u64 - 1) & page_mask as u64) {
                    // Mark page as success on first byte of each page
                    page_status.mark_success(page_index);
                }
            }
        }

        Ok(())
    }

    /// Set specific pages as faulty (will fail to read)
    ///
    /// # Arguments
    /// * `addr` - Address within the region
    /// * `page_indices` - Relative page indices within the region to mark as faulty
    pub fn set_faulty_pages(&mut self, addr: u64, page_indices: &[usize]) -> Result<()> {
        let region = self.find_region_mut(addr, 1)?;
        region.faulty_pages = page_indices.to_vec();
        Ok(())
    }

    /// Get page size
    pub fn page_size(&self) -> usize {
        self.page_size
    }

    /// Clear all memory regions
    pub fn reset(&mut self) {
        self.regions.clear();
    }

    /// Get total allocated memory size
    pub fn total_allocated(&self) -> usize {
        self.regions.values().map(|r| r.size).sum()
    }

    /// Dump memory region for debugging
    pub fn dump(&self, addr: u64, size: usize) -> Result<String> {
        let data = self.mem_read(addr, size)?;
        let mut output = String::new();

        for (i, chunk) in data.chunks(16).enumerate() {
            let chunk_addr = addr + (i * 16) as u64;
            output.push_str(&format!("0x{:08X}:  ", chunk_addr));

            // Hex bytes
            for (j, byte) in chunk.iter().enumerate() {
                output.push_str(&format!("{:02X} ", byte));
                if j == 7 {
                    output.push(' ');
                }
            }

            // Padding for incomplete lines
            for _ in chunk.len()..16 {
                output.push_str("   ");
            }

            // ASCII representation
            output.push_str("  |");
            for byte in chunk {
                let ch = if *byte >= 0x20 && *byte <= 0x7E {
                    *byte as char
                } else {
                    '.'
                };
                output.push(ch);
            }
            output.push_str("|\n");
        }

        Ok(output)
    }

    // Helper to find region containing address range
    fn find_region(&self, addr: u64, size: usize) -> Result<&MemoryRegion> {
        for (start, region) in self.regions.iter() {
            let region_end = start + region.size as u64;
            let access_end = addr + size as u64;

            if addr >= *start && access_end <= region_end {
                return Ok(region);
            }
        }

        Err(anyhow!("No memory region found for address 0x{:X} (size: {})", addr, size))
    }

    // Helper to find mutable region containing address range
    fn find_region_mut(&mut self, addr: u64, size: usize) -> Result<&mut MemoryRegion> {
        for (start, region) in self.regions.iter_mut() {
            let region_end = start + region.size as u64;
            let access_end = addr + size as u64;

            if addr >= *start && access_end <= region_end {
                return Ok(region);
            }
        }

        Err(anyhow!("No memory region found for address 0x{:X} (size: {})", addr, size))
    }
}

impl Default for MockMemory {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_malloc_and_write_read() {
        let mut mem = MockMemory::new();

        // Allocate 1MB at 0x1000
        let addr = mem.malloc(0x1000, 1024 * 1024).unwrap();
        assert_eq!(addr, 0x1000);

        // Write some data
        let test_data = b"Hello, World!";
        mem.mem_write(addr + 0x100, test_data).unwrap();

        // Read it back
        let read_data = mem.mem_read(addr + 0x100, test_data.len()).unwrap();
        assert_eq!(read_data, test_data);

        println!("✓ malloc/write/read test passed");
    }

    #[test]
    fn test_typed_writes() {
        let mut mem = MockMemory::new();
        let addr = mem.malloc(0x10000, 4096).unwrap();

        mem.mem_write_u32(addr, 0x12345678).unwrap();
        mem.mem_write_u64(addr + 4, 0xDEADBEEFCAFEBABE).unwrap();
        mem.mem_write_f32(addr + 12, 3.14159).unwrap();

        let data = mem.mem_read(addr, 16).unwrap();
        assert_eq!(&data[0..4], &0x12345678u32.to_le_bytes());
        assert_eq!(&data[4..12], &0xDEADBEEFCAFEBABEu64.to_le_bytes());

        println!("✓ Typed writes test passed");
    }

    #[test]
    fn test_page_fault_simulation() {
        let mut mem = MockMemory::new();
        let addr = mem.malloc(0x100000, 32 * 1024).unwrap();

        // Write test pattern
        for i in 0..8192 {
            mem.mem_write_u32(addr + i * 4, i as u32).unwrap();
        }

        // Mark pages 1 and 3 as faulty (out of 8 pages total)
        mem.set_faulty_pages(addr, &[1, 3]).unwrap();

        // Read with page status
        let mut buffer = vec![0u8; 32 * 1024];
        let mut page_status = PageStatusBitmap::new(buffer.len(), addr as usize);

        mem.mem_read_with_status(addr, &mut buffer, &mut page_status).unwrap();

        // Calculate actual number of pages (buffer size / page size)
        let page_size = mem.page_size();
        let actual_page_count = (buffer.len() + page_size - 1) / page_size;

        println!("Buffer size: {}", buffer.len());
        println!("Page size: {}", page_size);
        println!("Actual page count: {}", actual_page_count);
        println!("Bitmap capacity (num_pages): {}", page_status.num_pages());
        println!("Success pages: {}", page_status.success_count());

        // Get only the failed pages that are within actual page range
        let failed_pages: Vec<usize> = page_status.failed_pages()
            .into_iter()
            .filter(|&page_idx| page_idx < actual_page_count)
            .collect();
        println!("Failed pages (filtered): {:?}", failed_pages);

        // Should have 6 successful pages (8 - 2 faulty)
        assert_eq!(page_status.success_count(), 6);
        assert_eq!(failed_pages.len(), 2, "Expected 2 failed pages within actual range, got {}", failed_pages.len());

        println!("✓ Page fault simulation test passed");
    }

    #[test]
    fn test_dump() {
        let mut mem = MockMemory::new();
        let addr = mem.malloc(0x1000, 4096).unwrap();

        // Write some interesting data
        mem.mem_write(addr, b"ABCDEFGHIJKLMNOP").unwrap();
        mem.mem_write_u32(addr + 16, 0xDEADBEEF).unwrap();

        let dump = mem.dump(addr, 32).unwrap();
        println!("\nMemory dump:\n{}", dump);

        assert!(dump.contains("ABCDEFGHIJKLMNOP"));
        println!("✓ Dump test passed");
    }
}