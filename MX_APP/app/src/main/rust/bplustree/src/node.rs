use std::marker::PhantomData;
use std::mem::MaybeUninit;
use std::ptr::NonNull;

pub(crate) struct LeafNode<K, V> {
    pub(crate) parent: Option<NonNull<InternalNode<K, V>>>,
    pub(crate) len: u16,
    pub(crate) keys: Vec<K>,
    pub(crate) vals: Vec<V>,
    pub(crate) next: Option<NonNull<LeafNode<K, V>>>,
    pub(crate) prev: Option<NonNull<LeafNode<K, V>>>,
}

impl<K, V> LeafNode<K, V> {
    pub(crate) fn new(order: u16) -> Box<Self> {
        Box::new(Self {
            parent: None,
            len: 0,
            keys: Vec::with_capacity(order as usize),
            vals: Vec::with_capacity(order as usize),
            next: None,
            prev: None,
        })
    }
}

pub(crate) struct InternalNode<K, V> {
    pub(crate) parent: Option<NonNull<InternalNode<K, V>>>,
    pub(crate) len: u16,
    pub(crate) keys: Vec<K>,
    pub(crate) children: Vec<TreeNode<K, V>>,
}

impl<K, V> InternalNode<K, V> {
    pub(crate) fn new(order: u16) -> Box<Self> {
        Box::new(Self {
            parent: None,
            len: 0,
            keys: Vec::with_capacity(order as usize),
            children: Vec::with_capacity(order as usize + 1),
        })
    }
}

#[derive(Debug)]
pub enum TreeNode<K, V> {
    Internal(NonNull<InternalNode<K, V>>),
    LeafNode(NonNull<LeafNode<K, V>>),
}

impl<K: Ord, V> TreeNode<K, V> {
    pub fn new_leaf(order: u16) -> Self {
        let leaf = LeafNode::new(order);
        TreeNode::LeafNode(NonNull::from(Box::leak(leaf)))
    }

    pub fn new_internal(order: u16) -> Self {
        let internal = InternalNode::new(order);
        TreeNode::Internal(NonNull::from(Box::leak(internal)))
    }

    #[inline]
    pub fn is_leaf(&self) -> bool {
        match self {
            TreeNode::LeafNode(_) => true,
            _ => false,
        }
    }

    #[inline]
    pub fn is_internal(&self) -> bool {
        match self {
            TreeNode::Internal(_) => true,
            _ => false,
        }
    }

    pub fn to_leaf_mut(&mut self) -> &mut LeafNode<K, V> {
        match self {
            TreeNode::LeafNode(ptr) => unsafe { ptr.as_mut() },
            _ => panic!("Not a leaf node"),
        }
    }

    pub fn to_internal_mut(&mut self) -> &mut InternalNode<K, V> {
        match self {
            TreeNode::Internal(ptr) => unsafe { ptr.as_mut() },
            _ => panic!("Not an internal node"),
        }
    }

    pub fn to_leaf(&self) -> &LeafNode<K, V> {
        match self {
            TreeNode::LeafNode(ptr) => unsafe { ptr.as_ref() },
            _ => panic!("Not a leaf node"),
        }
    }

    pub fn to_internal(&self) -> &InternalNode<K, V> {
        match self {
            TreeNode::Internal(ptr) => unsafe { ptr.as_ref() },
            _ => panic!("Not an internal node"),
        }
    }

    pub fn to_owned_leaf(self: Self) -> Box<LeafNode<K, V>> {
        match self {
            TreeNode::LeafNode(ptr) => unsafe { Box::from_raw(ptr.as_ptr()) },
            _ => panic!("Not a leaf node"),
        }
    }

    pub fn to_owned_internal(self: Self) -> Box<InternalNode<K, V>> {
        match self {
            TreeNode::Internal(ptr) => unsafe { Box::from_raw(ptr.as_ptr()) },
            _ => panic!("Not an internal node"),
        }
    }
}
