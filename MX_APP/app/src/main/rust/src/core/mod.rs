//! Core business logic modules
//!
//! This module contains core components for driver management and memory access.

pub mod memory_mode;
pub mod driver_manager;
pub mod globals;
pub mod freeze_manager;

// Re-export commonly used items
pub use memory_mode::MemoryAccessMode;
pub use driver_manager::DriverManager;
pub use globals::DRIVER_MANAGER;
pub use freeze_manager::FreezeManager;