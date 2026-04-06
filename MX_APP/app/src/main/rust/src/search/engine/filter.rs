use super::super::types::ValueType;

/// 搜索过滤器
/// 用于在搜索过程中应用地址范围和类型过滤
#[derive(Debug, Clone, Default)]
pub struct SearchFilter {
    /// 是否启用地址过滤
    pub enable_address_filter: bool,
    /// 地址范围起始
    pub address_start: u64,
    /// 地址范围结束
    pub address_end: u64,

    //// 是否启用类型过滤
    pub enable_type_filter: bool,

    /// 类型ID列表
    pub type_ids: Vec<ValueType>,
}

impl SearchFilter {
    pub fn new() -> Self {
        Self::default()
    }

    #[inline]
    pub fn is_active(&self) -> bool {
        self.enable_address_filter || self.enable_type_filter || !self.type_ids.is_empty()
    }

    #[inline]
    pub fn clear(&mut self) {
        *self = Self::default();
    }
}