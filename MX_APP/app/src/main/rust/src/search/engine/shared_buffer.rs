//! Shared buffer for lock-free communication between Kotlin and Rust.
//!
//! Memory layout (32 bytes):
//! ```text
//! [0-3]   status         (Rust writes)  SearchStatus enum
//! [4-7]   progress       (Rust writes)  0-100
//! [8-11]  regions_done   (Rust writes)  completed region count
//! [12-19] found_count    (Rust writes)  total results found (i64)
//! [20-23] heartbeat      (Rust writes)  periodic random value
//! [24-27] cancel_flag    (Kotlin writes) 1 = cancel requested
//! [28-31] error_code     (Rust writes)  error code when status is Error
//! ```

use std::sync::atomic::{AtomicPtr, Ordering, fence};

/// Shared buffer size in bytes.
pub const SHARED_BUFFER_SIZE: usize = 32;

/// Offsets for shared buffer fields.
pub mod offsets {
    pub const STATUS: usize = 0;
    pub const PROGRESS: usize = 4;
    pub const REGIONS_DONE: usize = 8;
    pub const FOUND_COUNT: usize = 12;
    pub const HEARTBEAT: usize = 20;
    pub const CANCEL_FLAG: usize = 24;
    pub const ERROR_CODE: usize = 28;
}

/// Search status enum.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SearchStatus {
    /// No search in progress.
    Idle = 0,
    /// Search is running.
    Searching = 1,
    /// Search completed successfully.
    Completed = 2,
    /// Search was cancelled.
    Cancelled = 3,
    /// Search failed with error.
    Error = 4,
}

impl From<i32> for SearchStatus {
    fn from(value: i32) -> Self {
        match value {
            0 => SearchStatus::Idle,
            1 => SearchStatus::Searching,
            2 => SearchStatus::Completed,
            3 => SearchStatus::Cancelled,
            4 => SearchStatus::Error,
            _ => SearchStatus::Idle,
        }
    }
}

/// Error codes for search operations.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SearchErrorCode {
    None = 0,
    NotInitialized = 1,
    InvalidQuery = 2,
    MemoryReadFailed = 3,
    InternalError = 4,
    AlreadySearching = 5,
}

/// Thread-safe shared buffer for Kotlin-Rust communication.
///
/// This struct provides lock-free read/write access to a shared memory region
/// that is allocated by Kotlin (DirectByteBuffer) and passed to Rust.
#[derive(Debug)]
pub struct SharedBuffer {
    ptr: AtomicPtr<u8>,
    len: usize,
}

unsafe impl Send for SharedBuffer {}
unsafe impl Sync for SharedBuffer {}

impl SharedBuffer {
    /// Creates a new uninitialized SharedBuffer.
    pub const fn new() -> Self {
        Self {
            ptr: AtomicPtr::new(std::ptr::null_mut()),
            len: 0,
        }
    }

    /// Sets the buffer pointer and length.
    ///
    /// The caller must ensure:
    /// - The pointer is valid for the lifetime of this SharedBuffer.
    /// - The buffer is at least SHARED_BUFFER_SIZE bytes.
    /// - The buffer is properly aligned.
    pub fn set(&mut self, ptr: *mut u8, len: usize) -> bool {
        if ptr.is_null() || len < SHARED_BUFFER_SIZE {
            return false;
        }
        self.ptr.store(ptr, Ordering::Release);
        self.len = len;

        // Initialize buffer to zeros.
        self.reset();
        true
    }

    /// Clears the buffer reference.
    pub fn clear(&mut self) {
        self.ptr.store(std::ptr::null_mut(), Ordering::Release);
        self.len = 0;
    }

    /// Checks if buffer is set.
    pub fn is_set(&self) -> bool {
        !self.ptr.load(Ordering::Acquire).is_null() && self.len >= SHARED_BUFFER_SIZE
    }

    /// Resets all fields to initial values.
    pub fn reset(&self) {
        if !self.is_set() {
            return;
        }
        self.write_status(SearchStatus::Idle);
        self.write_progress(0);
        self.write_regions_done(0);
        self.write_found_count(0);
        self.write_heartbeat(0);
        self.write_error_code(SearchErrorCode::None);
        // Note: We don't reset cancel_flag here because Kotlin controls it.
    }

    /// Writes search status.
    ///
    /// Note: This uses a Release fence to ensure all previous writes
    /// (progress, found_count, etc.) are visible before the status change.
    #[inline]
    pub fn write_status(&self, status: SearchStatus) {
        // Ensure all previous writes are visible before status change.
        fence(Ordering::Release);
        self.write_i32(offsets::STATUS, status as i32);
    }

    /// Writes progress value (0-100).
    #[inline]
    pub fn write_progress(&self, progress: i32) {
        self.write_i32(offsets::PROGRESS, progress.clamp(0, 100));
    }

    /// Writes completed region count.
    #[inline]
    pub fn write_regions_done(&self, count: i32) {
        self.write_i32(offsets::REGIONS_DONE, count);
    }

    /// Writes total found count.
    #[inline]
    pub fn write_found_count(&self, count: i64) {
        self.write_i64(offsets::FOUND_COUNT, count);
    }

    /// Writes heartbeat value.
    #[inline]
    pub fn write_heartbeat(&self, value: i32) {
        self.write_i32(offsets::HEARTBEAT, value);
    }

    /// Writes error code.
    #[inline]
    pub fn write_error_code(&self, code: SearchErrorCode) {
        self.write_i32(offsets::ERROR_CODE, code as i32);
    }

    /// Reads cancel flag that is set by Kotlin.
    #[inline]
    pub fn is_cancel_requested(&self) -> bool {
        self.read_i32(offsets::CANCEL_FLAG) != 0
    }

    /// Clears the cancel flag.
    #[inline]
    pub fn clear_cancel_flag(&self) {
        self.write_i32(offsets::CANCEL_FLAG, 0);
    }

    /// Updates progress information atomically.
    #[inline]
    pub fn update_progress(&self, progress: i32, regions_done: i32, found_count: i64) {
        self.write_progress(progress);
        self.write_regions_done(regions_done);
        self.write_found_count(found_count);
    }

    /// Updates heartbeat with random value.
    #[inline]
    pub fn tick_heartbeat(&self) {
        let heartbeat: i32 = rand::random();
        self.write_heartbeat(heartbeat);
    }

    #[inline]
    fn write_i32(&self, offset: usize, value: i32) {
        let ptr = self.ptr.load(Ordering::Acquire);
        if ptr.is_null() || offset + 4 > self.len {
            return;
        }
        unsafe {
            std::ptr::write_unaligned(ptr.add(offset) as *mut i32, value);
        }
    }

    #[inline]
    fn write_i64(&self, offset: usize, value: i64) {
        let ptr = self.ptr.load(Ordering::Acquire);
        if ptr.is_null() || offset + 8 > self.len {
            return;
        }
        unsafe {
            std::ptr::write_unaligned(ptr.add(offset) as *mut i64, value);
        }
    }

    #[inline]
    fn read_i32(&self, offset: usize) -> i32 {
        let ptr = self.ptr.load(Ordering::Acquire);
        if ptr.is_null() || offset + 4 > self.len {
            return 0;
        }
        unsafe { std::ptr::read_unaligned(ptr.add(offset) as *const i32) }
    }
}

impl Default for SharedBuffer {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_shared_buffer_layout() {
        assert_eq!(offsets::STATUS, 0);
        assert_eq!(offsets::PROGRESS, 4);
        assert_eq!(offsets::REGIONS_DONE, 8);
        assert_eq!(offsets::FOUND_COUNT, 12);
        assert_eq!(offsets::HEARTBEAT, 20);
        assert_eq!(offsets::CANCEL_FLAG, 24);
        assert_eq!(offsets::ERROR_CODE, 28);
        assert_eq!(SHARED_BUFFER_SIZE, 32);
    }

    #[test]
    fn test_search_status_conversion() {
        assert_eq!(SearchStatus::from(0), SearchStatus::Idle);
        assert_eq!(SearchStatus::from(1), SearchStatus::Searching);
        assert_eq!(SearchStatus::from(2), SearchStatus::Completed);
        assert_eq!(SearchStatus::from(3), SearchStatus::Cancelled);
        assert_eq!(SearchStatus::from(4), SearchStatus::Error);
        assert_eq!(SearchStatus::from(99), SearchStatus::Idle);
    }
}
