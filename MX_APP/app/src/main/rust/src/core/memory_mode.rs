//! Memory access mode definitions

use crate::wuwa::WuwaMemoryType;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MemoryAccessMode {
    None,
    NonCacheable,
    WriteThrough,
    Normal,
    PageFault,
}

impl MemoryAccessMode {
    #[inline]
    pub fn from_id(id: i32) -> Option<Self> {
        match id {
            0 => Some(MemoryAccessMode::None),
            1 => Some(MemoryAccessMode::NonCacheable),
            2 => Some(MemoryAccessMode::WriteThrough),
            3 => Some(MemoryAccessMode::Normal),
            4 => Some(MemoryAccessMode::PageFault),
            _ => None,
        }
    }
}