use std::collections::VecDeque;
use std::mem::ManuallyDrop;
use std::{mem, ptr};
use std::ptr::NonNull;
use crate::node::{InternalNode, LeafNode, TreeNode};

pub struct BPlusTreeMap<K, V> {
    pub(crate) root: Option<TreeNode<K, V>>,
    pub(crate) order: u16,
    pub(crate) length: usize,
    pub(crate) head: Option<NonNull<LeafNode<K, V>>>
}

unsafe impl<K: Send, V: Send> Send for BPlusTreeMap<K, V> {}
unsafe impl<K: Sync, V: Sync> Sync for BPlusTreeMap<K, V> {}

// K must be Clone because keys are copied up to internal nodes from leaves.
impl<K: Ord + Clone, V> BPlusTreeMap<K, V> {
    pub fn new(order: u16) -> Self {
        // The order must be at least 3 to allow for splitting.
        assert!(order >= 3, "B+ Tree order must be at least 3");
        Self {
            root: None,
            order,
            length: 0,
            head: None
        }
    }

    /// Inserts a key-value pair into the map.
    /// If the key already exists, replaces the old value and returns it.
    /// If the key doesn't exist, inserts the new pair and returns None.
    pub fn insert(&mut self, key: K, value: V) -> Option<V> {
        // The tree is empty.
        if self.root.is_none() {
            let leaf_box = LeafNode::new(self.order);
            let leaked_ref: &mut LeafNode<K, V> = Box::leak(leaf_box);
            let mut leaf_ptr = NonNull::from(leaked_ref);
            let leaf = unsafe { leaf_ptr.as_mut() };

            leaf.keys.push(key);
            leaf.vals.push(value);
            leaf.len = 1;

            self.root = Some(TreeNode::LeafNode(leaf_ptr));
            self.head = Some(leaf_ptr);
            self.length = 1;
            return None;
        }

        // Find the leaf where the key should be inserted.
        let mut current_node = self.root.as_mut().unwrap();
        while let TreeNode::Internal(internal_ptr) = current_node {
            let internal = unsafe { internal_ptr.as_mut() };
            // Same navigation logic as get: if key is found at position i, go to child i+1
            // because keys[i] is the first key of child i+1 (or separator between i and i+1)
            let pos = match internal.keys.binary_search(&key) {
                Ok(i) => i + 1,  // Found: key is in the right subtree
                Err(i) => i,      // Not found: i is the correct child position
            };
            current_node = &mut internal.children[pos];
        }

        let mut leaf_node_ptr = match current_node {
            TreeNode::LeafNode(ptr) => *ptr,
            _ => unreachable!(),
        };
        let leaf_node = unsafe { leaf_node_ptr.as_mut() };

        // Check if key already exists
        match leaf_node.keys.binary_search(&key) {
            Ok(pos) => {
                // Key exists, replace the value and return the old one
                let old_value = mem::replace(&mut leaf_node.vals[pos], value);
                return Some(old_value);
            }
            Err(pos) => {
                // Key doesn't exist, insert new key-value pair
                leaf_node.keys.insert(pos, key);
                leaf_node.vals.insert(pos, value);
                leaf_node.len += 1;
                self.length += 1;
            }
        }

        // The leaf is full and needs to be split.
        if leaf_node.len > self.order {
            let midpoint = (leaf_node.len as usize) / 2; // 用order / 2作为分裂点也可以
            let new_sibling_box = LeafNode::new(self.order);
            let mut new_sibling_ptr = NonNull::from(Box::leak(new_sibling_box));
            let sibling = unsafe { new_sibling_ptr.as_mut() };

            // The first key of the new sibling is copied up to the parent.
            //let first_key = leaf_node.keys[0].clone(); // 插入父节点的key1
            let promoted_key = leaf_node.keys[midpoint].clone(); // 插入父节点的key2

            // Move the second half of keys/values to the new sibling.
            sibling.keys.extend(leaf_node.keys.drain(midpoint..));
            sibling.vals.extend(leaf_node.vals.drain(midpoint..));

            // Update lengths
            leaf_node.len = leaf_node.keys.len() as u16;
            sibling.len = sibling.keys.len() as u16;

            // Update the doubly linked list of leaves
            sibling.next = leaf_node.next;
            if let Some(mut next_node) = leaf_node.next {
                unsafe { next_node.as_mut().prev = Some(new_sibling_ptr) };
            }
            leaf_node.next = Some(new_sibling_ptr);
            sibling.prev = Some(leaf_node_ptr);

            // Insert the promoted key into the parent node.
            if let Some(parent_ptr) = leaf_node.parent {
                sibling.parent = Some(parent_ptr);
                self.insert_into_parent(parent_ptr, promoted_key, TreeNode::LeafNode(new_sibling_ptr));
            } else {
                // The leaf was the root, so we must create a new root.
                let mut new_root = InternalNode::new(self.order);
                //new_root.keys.push(first_key);
                new_root.keys.push(promoted_key);
                new_root.children.push(TreeNode::LeafNode(leaf_node_ptr));
                new_root.children.push(TreeNode::LeafNode(new_sibling_ptr));
                new_root.len = 1;
                let new_root_ptr = NonNull::from(Box::leak(new_root));

                leaf_node.parent = Some(new_root_ptr);
                sibling.parent = Some(new_root_ptr);
                self.root = Some(TreeNode::Internal(new_root_ptr));
            }
        }

        None
    }

    /// Searches for a key in the tree and returns a reference to the value if found.
    /// If there are duplicate keys, returns the first occurrence.
    pub fn get(&self, key: &K) -> Option<&V> {
        let mut current_node = self.root.as_ref()?;

        // Navigate down to the leaf node
        while let TreeNode::Internal(internal_ptr) = current_node {
            let internal = unsafe { internal_ptr.as_ref() };
            // In B+ tree, keys[i] is the minimum value of the right subtree
            // So if key == keys[i], we should go to children[i+1]
            let pos = match internal.keys.binary_search(key) {
                Ok(i) => i + 1,  // Found: key is in the right subtree
                Err(i) => i,      // Not found: i is the correct child position
            };
            current_node = &internal.children[pos];
        }

        // Search in the leaf node
        if let TreeNode::LeafNode(leaf_ptr) = current_node {
            let leaf = unsafe { leaf_ptr.as_ref() };
            if let Ok(pos) = leaf.keys.binary_search(key) {
                return Some(&leaf.vals[pos]);
            }
        }

        None
    }

    /// Searches for a key in the tree and returns a mutable reference to the value if found.
    /// If there are duplicate keys, returns the first occurrence.
    pub fn get_mut(&mut self, key: &K) -> Option<&mut V> {
        let mut current_node = self.root.as_mut()?;

        // Navigate down to the leaf node
        loop {
            match current_node {
                TreeNode::Internal(internal_ptr) => {
                    let internal = unsafe { internal_ptr.as_mut() };
                    let pos = match internal.keys.binary_search(key) {
                        Ok(i) => i + 1,
                        Err(i) => i,
                    };
                    current_node = &mut internal.children[pos];
                }
                TreeNode::LeafNode(leaf_ptr) => {
                    let leaf = unsafe { leaf_ptr.as_mut() };
                    if let Ok(pos) = leaf.keys.binary_search(key) {
                        return Some(&mut leaf.vals[pos]);
                    }
                    return None;
                }
            }
        }
    }

    /// Returns the minimum number of keys allowed in a leaf node (except root).
    fn min_keys_for_leaf(&self) -> u16 {
        (self.order + 1) / 2
    }

    /// Returns the minimum number of keys allowed in an internal node (except root).
    fn min_keys_for_internal(&self) -> u16 {
        self.order / 2
    }

    /// Finds the leaf node that should contain the given key.
    fn find_leaf(&self, key: &K) -> Option<NonNull<LeafNode<K, V>>> {
        let mut current = self.root.as_ref()?;

        while let TreeNode::Internal(internal_ptr) = current {
            let internal = unsafe { internal_ptr.as_ref() };
            let pos = match internal.keys.binary_search(key) {
                Ok(i) => i + 1,
                Err(i) => i,
            };
            current = &internal.children[pos];
        }

        match current {
            TreeNode::LeafNode(ptr) => Some(*ptr),
            _ => None,
        }
    }

    /// Removes a key from the tree and returns the associated value if found.
    pub fn remove(&mut self, key: &K) -> Option<V> {
        let mut leaf_ptr = self.find_leaf(key)?;
        let leaf = unsafe { leaf_ptr.as_mut() };

        // Find and remove the key
        let pos = leaf.keys.binary_search(key).ok()?;
        let value = leaf.vals.remove(pos);
        leaf.keys.remove(pos);
        leaf.len -= 1;
        self.length -= 1;

        // Check for underflow
        let min_keys = self.min_keys_for_leaf();
        if leaf.len < min_keys && leaf.parent.is_some() {
            self.handle_underflow_leaf(leaf_ptr);
        } else if leaf.len == 0 && leaf.parent.is_none() {
            // Tree becomes empty
            self.root = None;
            self.head = None;
            unsafe { drop(Box::from_raw(leaf_ptr.as_ptr())) };
        }

        Some(value)
    }

    /// Returns the number of key-value pairs in the map.
    pub fn len(&self) -> usize {
        self.length
    }

    /// Returns true if the map contains no elements.
    pub fn is_empty(&self) -> bool {
        self.length == 0
    }

    /// Returns an iterator over the key-value pairs in the map, in sorted key order.
    pub fn iter(&self) -> Iter<'_, K, V> {
        Iter {
            current: self.head,
            index: 0,
            remaining: self.length,
            _marker: std::marker::PhantomData,
        }
    }

    /// Handles underflow in a leaf node by borrowing from siblings or merging.
    fn handle_underflow_leaf(&mut self, mut leaf_ptr: NonNull<LeafNode<K, V>>) {
        let leaf = unsafe { leaf_ptr.as_ref() };
        let parent_ptr = leaf.parent.unwrap();

        // Find the position of this leaf in parent's children
        let leaf_pos = {
            let parent = unsafe { parent_ptr.as_ref() };
            parent.children.iter().position(|child| {
                matches!(child, TreeNode::LeafNode(p) if *p == leaf_ptr)
            }).unwrap()
        };

        // Try to borrow from left sibling
        if leaf_pos > 0 {
            let left_sibling_ptr = {
                let parent = unsafe { parent_ptr.as_ref() };
                match &parent.children[leaf_pos - 1] {
                    TreeNode::LeafNode(p) => Some(*p),
                    _ => None,
                }
            };
            if let Some(left_sibling_ptr) = left_sibling_ptr {
                let left_sibling = unsafe { left_sibling_ptr.as_ref() };
                if left_sibling.len > self.min_keys_for_leaf() {
                    self.borrow_from_left_leaf(leaf_ptr, left_sibling_ptr, parent_ptr, leaf_pos);
                    return;
                }
            }
        }

        // Try to borrow from right sibling
        let parent_children_len = unsafe { parent_ptr.as_ref().children.len() };
        if leaf_pos < parent_children_len - 1 {
            let right_sibling_ptr = {
                let parent = unsafe { parent_ptr.as_ref() };
                match &parent.children[leaf_pos + 1] {
                    TreeNode::LeafNode(p) => Some(*p),
                    _ => None,
                }
            };
            if let Some(right_sibling_ptr) = right_sibling_ptr {
                let right_sibling = unsafe { right_sibling_ptr.as_ref() };
                if right_sibling.len > self.min_keys_for_leaf() {
                    self.borrow_from_right_leaf(leaf_ptr, right_sibling_ptr, parent_ptr, leaf_pos);
                    return;
                }
            }
        }

        // Cannot borrow, must merge
        if leaf_pos > 0 {
            // Merge with left sibling
            let left_sibling_ptr = {
                let parent = unsafe { parent_ptr.as_ref() };
                match parent.children[leaf_pos - 1] {
                    TreeNode::LeafNode(p) => p,
                    _ => return,
                }
            };
            self.merge_with_left_leaf(leaf_ptr, left_sibling_ptr, parent_ptr, leaf_pos);
        } else {
            // Merge with right sibling
            let right_sibling_ptr = {
                let parent = unsafe { parent_ptr.as_ref() };
                match parent.children[leaf_pos + 1] {
                    TreeNode::LeafNode(p) => p,
                    _ => return,
                }
            };
            self.merge_with_right_leaf(leaf_ptr, right_sibling_ptr, parent_ptr, leaf_pos);
        }
    }

    /// Borrows a key from the left sibling of a leaf node.
    fn borrow_from_left_leaf(
        &mut self,
        mut leaf_ptr: NonNull<LeafNode<K, V>>,
        mut left_sibling_ptr: NonNull<LeafNode<K, V>>,
        mut parent_ptr: NonNull<InternalNode<K, V>>,
        leaf_pos: usize,
    ) {
        let leaf = unsafe { leaf_ptr.as_mut() };
        let left_sibling = unsafe { left_sibling_ptr.as_mut() };
        let parent = unsafe { parent_ptr.as_mut() };

        // Take the last key-value from left sibling
        let borrowed_key = left_sibling.keys.pop().unwrap();
        let borrowed_val = left_sibling.vals.pop().unwrap();
        left_sibling.len -= 1;

        // Insert at the beginning of current leaf
        leaf.keys.insert(0, borrowed_key.clone());
        leaf.vals.insert(0, borrowed_val);
        leaf.len += 1;

        // Update parent's separator key
        parent.keys[leaf_pos - 1] = borrowed_key;
    }

    /// Borrows a key from the right sibling of a leaf node.
    fn borrow_from_right_leaf(
        &mut self,
        mut leaf_ptr: NonNull<LeafNode<K, V>>,
        mut right_sibling_ptr: NonNull<LeafNode<K, V>>,
        mut parent_ptr: NonNull<InternalNode<K, V>>,
        leaf_pos: usize,
    ) {
        let leaf = unsafe { leaf_ptr.as_mut() };
        let right_sibling = unsafe { right_sibling_ptr.as_mut() };
        let parent = unsafe { parent_ptr.as_mut() };

        // Take the first key-value from right sibling
        let borrowed_key = right_sibling.keys.remove(0);
        let borrowed_val = right_sibling.vals.remove(0);
        right_sibling.len -= 1;

        // Add to the end of current leaf
        leaf.keys.push(borrowed_key);
        leaf.vals.push(borrowed_val);
        leaf.len += 1;

        // Update parent's separator key
        parent.keys[leaf_pos] = right_sibling.keys[0].clone();
    }

    /// Merges a leaf node with its left sibling.
    fn merge_with_left_leaf(
        &mut self,
        mut leaf_ptr: NonNull<LeafNode<K, V>>,
        mut left_sibling_ptr: NonNull<LeafNode<K, V>>,
        mut parent_ptr: NonNull<InternalNode<K, V>>,
        leaf_pos: usize,
    ) {
        let leaf = unsafe { leaf_ptr.as_mut() };
        let left_sibling = unsafe { left_sibling_ptr.as_mut() };
        let parent = unsafe { parent_ptr.as_mut() };

        // Move all keys and values from current leaf to left sibling
        left_sibling.keys.append(&mut leaf.keys);
        left_sibling.vals.append(&mut leaf.vals);
        left_sibling.len += leaf.len;

        // Update leaf linked list
        left_sibling.next = leaf.next;
        if let Some(mut next) = leaf.next {
            unsafe { next.as_mut().prev = Some(left_sibling_ptr) };
        }

        // Update head pointer if we're deleting the head
        if self.head == Some(leaf_ptr) {
            self.head = Some(left_sibling_ptr);
        }

        // Remove current leaf from parent
        parent.children.remove(leaf_pos);
        parent.keys.remove(leaf_pos - 1);
        parent.len -= 1;

        // Deallocate the current leaf
        unsafe { drop(Box::from_raw(leaf_ptr.as_ptr())) };

        // Check if parent underflows
        if parent.len < self.min_keys_for_internal() && parent.parent.is_some() {
            self.handle_underflow_internal(parent_ptr);
        } else if parent.len == 0 && parent.children.len() == 1 {
            // Parent is root with only one child
            self.root = Some(parent.children.pop().unwrap());
            match self.root.as_mut().unwrap() {
                TreeNode::Internal(ptr) => unsafe { ptr.as_mut().parent = None },
                TreeNode::LeafNode(ptr) => unsafe { ptr.as_mut().parent = None },
            }
            unsafe { drop(Box::from_raw(parent_ptr.as_ptr())) };
        }
    }

    /// Merges a leaf node with its right sibling.
    fn merge_with_right_leaf(
        &mut self,
        mut leaf_ptr: NonNull<LeafNode<K, V>>,
        mut right_sibling_ptr: NonNull<LeafNode<K, V>>,
        mut parent_ptr: NonNull<InternalNode<K, V>>,
        leaf_pos: usize,
    ) {
        let leaf = unsafe { leaf_ptr.as_mut() };
        let right_sibling = unsafe { right_sibling_ptr.as_mut() };
        let parent = unsafe { parent_ptr.as_mut() };

        // Move all keys and values from right sibling to current leaf
        leaf.keys.append(&mut right_sibling.keys);
        leaf.vals.append(&mut right_sibling.vals);
        leaf.len += right_sibling.len;

        // Update leaf linked list
        leaf.next = right_sibling.next;
        if let Some(mut next) = right_sibling.next {
            unsafe { next.as_mut().prev = Some(leaf_ptr) };
        }

        // Remove right sibling from parent
        parent.children.remove(leaf_pos + 1);
        parent.keys.remove(leaf_pos);
        parent.len -= 1;

        // Deallocate the right sibling
        unsafe { drop(Box::from_raw(right_sibling_ptr.as_ptr())) };

        // Check if parent underflows
        if parent.len < self.min_keys_for_internal() && parent.parent.is_some() {
            self.handle_underflow_internal(parent_ptr);
        } else if parent.len == 0 && parent.children.len() == 1 {
            // Parent is root with only one child
            self.root = Some(parent.children.pop().unwrap());
            match self.root.as_mut().unwrap() {
                TreeNode::Internal(ptr) => unsafe { ptr.as_mut().parent = None },
                TreeNode::LeafNode(ptr) => unsafe { ptr.as_mut().parent = None },
            }
            unsafe { drop(Box::from_raw(parent_ptr.as_ptr())) };
        }
    }

    /// Handles underflow in an internal node by borrowing from siblings or merging.
    fn handle_underflow_internal(&mut self, mut node_ptr: NonNull<InternalNode<K, V>>) {
        let node = unsafe { node_ptr.as_ref() };
        let parent_ptr = match node.parent {
            Some(p) => p,
            None => return, // Root node, no underflow handling needed
        };

        // Find the position of this node in parent's children
        let node_pos = {
            let parent = unsafe { parent_ptr.as_ref() };
            parent.children.iter().position(|child| {
                matches!(child, TreeNode::Internal(p) if *p == node_ptr)
            }).unwrap()
        };

        // Try to borrow from left sibling
        if node_pos > 0 {
            let left_sibling_ptr = {
                let parent = unsafe { parent_ptr.as_ref() };
                match &parent.children[node_pos - 1] {
                    TreeNode::Internal(p) => Some(*p),
                    _ => None,
                }
            };
            if let Some(left_sibling_ptr) = left_sibling_ptr {
                let left_sibling = unsafe { left_sibling_ptr.as_ref() };
                if left_sibling.len > self.min_keys_for_internal() {
                    self.borrow_from_left_internal(node_ptr, left_sibling_ptr, parent_ptr, node_pos);
                    return;
                }
            }
        }

        // Try to borrow from right sibling
        let parent_children_len = unsafe { parent_ptr.as_ref().children.len() };
        if node_pos < parent_children_len - 1 {
            let right_sibling_ptr = {
                let parent = unsafe { parent_ptr.as_ref() };
                match &parent.children[node_pos + 1] {
                    TreeNode::Internal(p) => Some(*p),
                    _ => None,
                }
            };
            if let Some(right_sibling_ptr) = right_sibling_ptr {
                let right_sibling = unsafe { right_sibling_ptr.as_ref() };
                if right_sibling.len > self.min_keys_for_internal() {
                    self.borrow_from_right_internal(node_ptr, right_sibling_ptr, parent_ptr, node_pos);
                    return;
                }
            }
        }

        // Cannot borrow, must merge
        if node_pos > 0 {
            // Merge with left sibling
            let left_sibling_ptr = {
                let parent = unsafe { parent_ptr.as_ref() };
                match parent.children[node_pos - 1] {
                    TreeNode::Internal(p) => p,
                    _ => return,
                }
            };
            self.merge_with_left_internal(node_ptr, left_sibling_ptr, parent_ptr, node_pos);
        } else {
            // Merge with right sibling
            let right_sibling_ptr = {
                let parent = unsafe { parent_ptr.as_ref() };
                match parent.children[node_pos + 1] {
                    TreeNode::Internal(p) => p,
                    _ => return,
                }
            };
            self.merge_with_right_internal(node_ptr, right_sibling_ptr, parent_ptr, node_pos);
        }
    }

    /// Borrows a key from the left sibling of an internal node.
    fn borrow_from_left_internal(
        &mut self,
        mut node_ptr: NonNull<InternalNode<K, V>>,
        mut left_sibling_ptr: NonNull<InternalNode<K, V>>,
        mut parent_ptr: NonNull<InternalNode<K, V>>,
        node_pos: usize,
    ) {
        let node = unsafe { node_ptr.as_mut() };
        let left_sibling = unsafe { left_sibling_ptr.as_mut() };
        let parent = unsafe { parent_ptr.as_mut() };

        // Move parent's separator key down to current node
        let separator = parent.keys[node_pos - 1].clone();
        node.keys.insert(0, separator);

        // Move last child from left sibling to current node
        let borrowed_child = left_sibling.children.pop().unwrap();
        node.children.insert(0, borrowed_child);

        // Update moved child's parent pointer
        match &mut node.children[0] {
            TreeNode::Internal(ptr) => unsafe { ptr.as_mut().parent = Some(node_ptr) },
            TreeNode::LeafNode(ptr) => unsafe { ptr.as_mut().parent = Some(node_ptr) },
        }

        // Move last key from left sibling to parent
        parent.keys[node_pos - 1] = left_sibling.keys.pop().unwrap();

        left_sibling.len -= 1;
        node.len += 1;
    }

    /// Borrows a key from the right sibling of an internal node.
    fn borrow_from_right_internal(
        &mut self,
        mut node_ptr: NonNull<InternalNode<K, V>>,
        mut right_sibling_ptr: NonNull<InternalNode<K, V>>,
        mut parent_ptr: NonNull<InternalNode<K, V>>,
        node_pos: usize,
    ) {
        let node = unsafe { node_ptr.as_mut() };
        let right_sibling = unsafe { right_sibling_ptr.as_mut() };
        let parent = unsafe { parent_ptr.as_mut() };

        // Move parent's separator key down to current node
        let separator = parent.keys[node_pos].clone();
        node.keys.push(separator);

        // Move first child from right sibling to current node
        let borrowed_child = right_sibling.children.remove(0);
        node.children.push(borrowed_child);

        // Update moved child's parent pointer
        match node.children.last_mut().unwrap() {
            TreeNode::Internal(ptr) => unsafe { ptr.as_mut().parent = Some(node_ptr) },
            TreeNode::LeafNode(ptr) => unsafe { ptr.as_mut().parent = Some(node_ptr) },
        }

        // Move first key from right sibling to parent
        parent.keys[node_pos] = right_sibling.keys.remove(0);

        right_sibling.len -= 1;
        node.len += 1;
    }

    /// Merges an internal node with its left sibling.
    fn merge_with_left_internal(
        &mut self,
        mut node_ptr: NonNull<InternalNode<K, V>>,
        mut left_sibling_ptr: NonNull<InternalNode<K, V>>,
        mut parent_ptr: NonNull<InternalNode<K, V>>,
        node_pos: usize,
    ) {
        let node = unsafe { node_ptr.as_mut() };
        let left_sibling = unsafe { left_sibling_ptr.as_mut() };
        let parent = unsafe { parent_ptr.as_mut() };

        // Pull down the separator key from parent
        let separator = parent.keys.remove(node_pos - 1);
        left_sibling.keys.push(separator);

        // Move all keys and children from current node to left sibling
        left_sibling.keys.append(&mut node.keys);
        let moved_children_count = node.children.len();
        left_sibling.children.append(&mut node.children);
        left_sibling.len = left_sibling.keys.len() as u16;

        // Update parent pointers of moved children
        let start_idx = left_sibling.children.len() - moved_children_count;
        for child in &mut left_sibling.children[start_idx..] {
            match child {
                TreeNode::Internal(ptr) => unsafe { ptr.as_mut().parent = Some(left_sibling_ptr) },
                TreeNode::LeafNode(ptr) => unsafe { ptr.as_mut().parent = Some(left_sibling_ptr) },
            }
        }

        // Remove current node from parent
        parent.children.remove(node_pos);
        parent.len -= 1;

        // Deallocate current node
        unsafe { drop(Box::from_raw(node_ptr.as_ptr())) };

        // Check if parent underflows
        if parent.len < self.min_keys_for_internal() && parent.parent.is_some() {
            self.handle_underflow_internal(parent_ptr);
        } else if parent.len == 0 && parent.children.len() == 1 {
            // Parent is root with only one child
            self.root = Some(parent.children.pop().unwrap());
            match self.root.as_mut().unwrap() {
                TreeNode::Internal(ptr) => unsafe { ptr.as_mut().parent = None },
                TreeNode::LeafNode(ptr) => unsafe { ptr.as_mut().parent = None },
            }
            unsafe { drop(Box::from_raw(parent_ptr.as_ptr())) };
        }
    }

    /// Merges an internal node with its right sibling.
    fn merge_with_right_internal(
        &mut self,
        mut node_ptr: NonNull<InternalNode<K, V>>,
        mut right_sibling_ptr: NonNull<InternalNode<K, V>>,
        mut parent_ptr: NonNull<InternalNode<K, V>>,
        node_pos: usize,
    ) {
        let node = unsafe { node_ptr.as_mut() };
        let right_sibling = unsafe { right_sibling_ptr.as_mut() };
        let parent = unsafe { parent_ptr.as_mut() };

        // Pull down the separator key from parent
        let separator = parent.keys.remove(node_pos);
        node.keys.push(separator);

        // Move all keys and children from right sibling to current node
        node.keys.append(&mut right_sibling.keys);
        let moved_children_count = right_sibling.children.len();
        node.children.append(&mut right_sibling.children);
        node.len = node.keys.len() as u16;

        // Update parent pointers of moved children
        let start_idx = node.children.len() - moved_children_count;
        for child in &mut node.children[start_idx..] {
            match child {
                TreeNode::Internal(ptr) => unsafe { ptr.as_mut().parent = Some(node_ptr) },
                TreeNode::LeafNode(ptr) => unsafe { ptr.as_mut().parent = Some(node_ptr) },
            }
        }

        // Remove right sibling from parent
        parent.children.remove(node_pos + 1);
        parent.len -= 1;

        // Deallocate right sibling
        unsafe { drop(Box::from_raw(right_sibling_ptr.as_ptr())) };

        // Check if parent underflows
        if parent.len < self.min_keys_for_internal() && parent.parent.is_some() {
            self.handle_underflow_internal(parent_ptr);
        } else if parent.len == 0 && parent.children.len() == 1 {
            // Parent is root with only one child
            self.root = Some(parent.children.pop().unwrap());
            match self.root.as_mut().unwrap() {
                TreeNode::Internal(ptr) => unsafe { ptr.as_mut().parent = None },
                TreeNode::LeafNode(ptr) => unsafe { ptr.as_mut().parent = None },
            }
            unsafe { drop(Box::from_raw(parent_ptr.as_ptr())) };
        }
    }

    /// Recursively inserts a key and a new child node into a parent, splitting if necessary.
    fn insert_into_parent(&mut self, mut parent_ptr: NonNull<InternalNode<K, V>>, key: K, mut child: TreeNode<K, V>) {
        let parent = unsafe { parent_ptr.as_mut() };

        // Set child's parent pointer
        match &mut child {
            TreeNode::Internal(ptr) => unsafe { ptr.as_mut().parent = Some(parent_ptr) },
            TreeNode::LeafNode(ptr) => unsafe { ptr.as_mut().parent = Some(parent_ptr) },
        }

        let pos = parent.keys.binary_search(&key).unwrap_or_else(|x| x);
        parent.keys.insert(pos, key);
        parent.children.insert(pos + 1, child);
        parent.len += 1;

        if parent.len > self.order {
            let midpoint = (parent.len as usize) / 2;

            // The key at the midpoint is moved up to the grandparent.
            let promoted_key = parent.keys.remove(midpoint);

            let new_sibling_box = InternalNode::new(self.order);
            let mut new_sibling_ptr = NonNull::from(Box::leak(new_sibling_box));
            let sibling = unsafe { new_sibling_ptr.as_mut() };

            // Move keys and children to the new sibling.
            sibling.keys.extend(parent.keys.drain(midpoint..));
            sibling.children.extend(parent.children.drain(midpoint + 1..));

            parent.len = parent.keys.len() as u16;
            sibling.len = sibling.keys.len() as u16;

            // Update the parent pointers of the moved children.
            for child_node in &mut sibling.children {
                match child_node {
                    TreeNode::Internal(ptr) => unsafe { ptr.as_mut().parent = Some(new_sibling_ptr) },
                    TreeNode::LeafNode(ptr) => unsafe { ptr.as_mut().parent = Some(new_sibling_ptr) },
                }
            }

            if let Some(grandparent_ptr) = parent.parent {
                sibling.parent = Some(grandparent_ptr);
                self.insert_into_parent(grandparent_ptr, promoted_key, TreeNode::Internal(new_sibling_ptr));
            } else {
                // The parent was the root, create a new root.
                let mut new_root = InternalNode::new(self.order);
                new_root.keys.push(promoted_key);
                new_root.children.push(TreeNode::Internal(parent_ptr));
                new_root.children.push(TreeNode::Internal(new_sibling_ptr));
                new_root.len = 1;
                let new_root_ptr = NonNull::from(Box::leak(new_root));

                parent.parent = Some(new_root_ptr);
                sibling.parent = Some(new_root_ptr);
                self.root = Some(TreeNode::Internal(new_root_ptr));
            }
        }
    }
}

/// Iterator over the key-value pairs in a B+ tree map.
pub struct Iter<'a, K, V> {
    current: Option<NonNull<LeafNode<K, V>>>,
    index: usize,
    remaining: usize,
    _marker: std::marker::PhantomData<&'a (K, V)>,
}

impl<'a, K, V> Iterator for Iter<'a, K, V> {
    type Item = (&'a K, &'a V);

    fn next(&mut self) -> Option<Self::Item> {
        if self.remaining == 0 {
            self.current = None; // Ensure iterator stops
            return None;
        }

        let mut leaf_ptr = self.current?;
        let mut leaf = unsafe { leaf_ptr.as_ref() };

        if self.index >= leaf.keys.len() {
            // Move to the next leaf node
            self.current = leaf.next;
            self.index = 0;
            if self.current.is_none() {
                self.remaining = 0;
                return None;
            }
            leaf_ptr = self.current.unwrap();
            leaf = unsafe { leaf_ptr.as_ref() };
        }

        if self.index < leaf.keys.len() {
            let key = &leaf.keys[self.index];
            let val = &leaf.vals[self.index];
            self.index += 1;
            self.remaining -= 1;
            Some((key, val))
        } else {
            self.remaining = 0;
            self.current = None;
            None
        }
    }

    fn size_hint(&self) -> (usize, Option<usize>) {
        (self.remaining, Some(self.remaining))
    }
}

impl<'a, K, V> ExactSizeIterator for Iter<'a, K, V> {
    fn len(&self) -> usize {
        self.remaining
    }
}

/// Implement Drop to prevent memory leaks from Box::leak.
impl<K, V> Drop for BPlusTreeMap<K, V> {
    fn drop(&mut self) {
        if let Some(root) = self.root.take() {
            let mut queue = VecDeque::new();
            queue.push_back(root);
            while let Some(node) = queue.pop_front() {
                match node {
                    TreeNode::Internal(internal_ptr) => {
                        let mut internal_node = unsafe { Box::from_raw(internal_ptr.as_ptr()) };
                        for child in internal_node.children.drain(..) {
                            queue.push_back(child);
                        }
                    }
                    TreeNode::LeafNode(leaf_ptr) => {
                        // Deallocate the leaf node by converting the raw pointer back into a Box.
                        let _ = unsafe { Box::from_raw(leaf_ptr.as_ptr()) };
                    }
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use std::ptr::NonNull;
    use crate::map::BPlusTreeMap;
    use crate::node::LeafNode;

    /// 简单插入少量元素，检查根是叶子，长度和链表正确
    #[test]
    fn insert_small() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        map.insert(10, 100);
        map.insert(5, 50);
        map.insert(20, 200);

        assert_eq!(map.length, 3);

        // 只有一个叶子，root 应该是叶子
        let root = map.root.as_ref().expect("root must exist");
        assert!(root.is_leaf());

        // head 应该存在，且只有一个节点（next 为 None）
        let head_ptr: NonNull<LeafNode<i32, i32>> = map.head.expect("head must exist");
        let head = unsafe { head_ptr.as_ref() };
        assert!(head.prev.is_none());
        assert!(head.next.is_none());
        assert_eq!(head.len, 3);
        assert_eq!(head.keys, vec![5, 10, 20]);
    }

    /// 插入足够多的数据触发叶子分裂和根分裂，检查链表和内部节点结构
    #[test]
    fn insert_causes_leaf_and_root_split() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        // 插入顺序打乱一点
        for &(k, v) in &[(10, 1), (20, 2), (5, 3), (15, 4), (25, 5), (30, 6)] {
            map.insert(k, v);
        }

        assert_eq!(map.length, 6);

        // root 应该是 internal
        let root = map.root.as_ref().expect("root must exist");
        assert!(root.is_internal());
        let internal = root.to_internal();

        // order=4，叶子分裂后根 typically 有 1~2 个 key（实现依赖，此处只做一些基本检查）
        assert!(internal.len >= 1);
        assert_eq!(internal.keys.len(), internal.len as usize);
        assert_eq!(internal.children.len(), internal.keys.len() + 1);

        // 通过叶子链表从 head 开始遍历，检查 key 的有序性和数量
        let mut total_keys = 0usize;
        let mut cur_ptr = map.head.expect("head must exist");
        let mut last_key: Option<i32> = None;
        let mut prev_ptr_opt: Option<NonNull<LeafNode<i32, i32>>> = None;

        loop {
            let leaf = unsafe { cur_ptr.as_ref() };
            // prev 链必须和我们记录的一致
            assert_eq!(leaf.prev, prev_ptr_opt);

            for &k in &leaf.keys {
                if let Some(last) = last_key {
                    assert!(last <= k, "keys in leaves must be sorted and non-decreasing");
                }
                last_key = Some(k);
                total_keys += 1;
            }

            match leaf.next {
                Some(next_ptr) => {
                    // next.prev 必须指回当前
                    let next_leaf = unsafe { next_ptr.as_ref() };
                    assert_eq!(next_leaf.prev, Some(cur_ptr));

                    prev_ptr_opt = Some(cur_ptr);
                    cur_ptr = next_ptr;
                }
                None => break,
            }
        }

        assert_eq!(total_keys, map.length);
    }

    /// 测试插入重复键会替换旧值
    #[test]
    fn insert_duplicate_keys() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        assert_eq!(map.insert(10, 1), None);
        assert_eq!(map.len(), 1);

        assert_eq!(map.insert(10, 2), Some(1));
        assert_eq!(map.len(), 1);

        assert_eq!(map.insert(10, 3), Some(2));
        assert_eq!(map.len(), 1);

        // 应该只有一个键值对
        assert_eq!(map.get(&10), Some(&3));
    }

    /// 随机插入一些数据，确认 drop 不崩（用 Miri 跑）
    #[test]
    fn random_inserts_drop_safely() {
        use rand::Rng;
        let mut rng = rand::thread_rng();

        for _ in 0..10 {
            let mut map = BPlusTreeMap::<i32, i32>::new(8);
            for _ in 0..1000 {
                let k = rng.random_range(0..1000);
                let v = rng.random::<i32>();
                map.insert(k, v);
            }
            // 这里不做结构检查，主要是让 drop 在大量插入后跑一遍，看是否触发 UB（配合 Miri）
        }
    }

    /// 测试 get 和 get_mut 方法
    #[test]
    fn test_get_and_get_mut() {
        let mut map = BPlusTreeMap::<i32, String>::new(4);

        map.insert(10, "ten".to_string());
        map.insert(5, "five".to_string());
        map.insert(20, "twenty".to_string());
        map.insert(15, "fifteen".to_string());

        // 测试 get
        assert_eq!(map.get(&10), Some(&"ten".to_string()));
        assert_eq!(map.get(&5), Some(&"five".to_string()));
        assert_eq!(map.get(&20), Some(&"twenty".to_string()));
        assert_eq!(map.get(&15), Some(&"fifteen".to_string()));
        assert_eq!(map.get(&100), None);

        // 测试 get_mut
        if let Some(val) = map.get_mut(&10) {
            *val = "TEN".to_string();
        }
        assert_eq!(map.get(&10), Some(&"TEN".to_string()));
    }

    /// 测试 remove 方法
    #[test]
    fn test_remove() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        for i in 0..10 {
            map.insert(i, i * 10);
        }

        assert_eq!(map.len(), 10);
        assert_eq!(map.remove(&5), Some(50));
        assert_eq!(map.len(), 9);
        assert_eq!(map.get(&5), None);
        assert_eq!(map.remove(&5), None);

        // 删除剩余元素
        for i in 0..10 {
            if i != 5 {
                map.remove(&i);
            }
        }

        assert_eq!(map.len(), 0);
        assert!(map.is_empty());
    }

    /// 测试迭代器
    #[test]
    fn test_iterator() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        let data = vec![(10, 100), (5, 50), (20, 200), (15, 150), (25, 250), (30, 300)];
        for &(k, v) in &data {
            map.insert(k, v);
        }

        // 使用迭代器收集所有键值对
        let collected: Vec<(i32, i32)> = map.iter().map(|(k, v)| (*k, *v)).collect();

        // 迭代器应该按照键的顺序返回
        let expected = vec![(5, 50), (10, 100), (15, 150), (20, 200), (25, 250), (30, 300)];
        assert_eq!(collected, expected);

        // 测试 ExactSizeIterator
        let iter = map.iter();
        assert_eq!(iter.len(), 6);
    }

    /// 测试 len 和 is_empty
    #[test]
    fn test_len_and_is_empty() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        assert_eq!(map.len(), 0);
        assert!(map.is_empty());

        map.insert(10, 100);
        assert_eq!(map.len(), 1);
        assert!(!map.is_empty());

        map.insert(20, 200);
        map.insert(30, 300);
        assert_eq!(map.len(), 3);

        map.remove(&20);
        assert_eq!(map.len(), 2);

        map.remove(&10);
        map.remove(&30);
        assert_eq!(map.len(), 0);
        assert!(map.is_empty());
    }

    /// 测试在有分裂的情况下 get 是否工作正常
    #[test]
    fn test_get_after_splits() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        // 插入足够多的数据触发多次分裂
        for i in 0..100 {
            map.insert(i, i * 10);
        }

        // 验证所有值都能正确获取
        for i in 0..100 {
            assert_eq!(map.get(&i), Some(&(i * 10)));
        }

        // 验证不存在的键返回 None
        assert_eq!(map.get(&100), None);
        assert_eq!(map.get(&-1), None);
    }

    /// 测试迭代器可以多次循环使用
    #[test]
    fn test_iterator_multiple_loops() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        // 插入数据
        for i in 0..20 {
            map.insert(i, i * 100);
        }

        // 循环 3 次迭代，确保迭代器每次都能正确工作
        for round in 0..3 {
            let mut count = 0;
            let mut last_key = None;

            for (k, v) in map.iter() {
                // 验证值的正确性
                assert_eq!(*v, *k * 100);

                // 验证顺序性
                if let Some(prev) = last_key {
                    assert!(prev < *k, "Round {}: keys must be in ascending order", round);
                }
                last_key = Some(*k);
                count += 1;
            }

            // 确保每次都遍历了所有元素
            assert_eq!(count, 20, "Round {}: iterator should return all 20 elements", round);
        }
    }

    /// 测试迭代器在空树上的行为
    #[test]
    fn test_iterator_empty_tree() {
        let map = BPlusTreeMap::<i32, i32>::new(4);

        let mut iter = map.iter();
        assert_eq!(iter.len(), 0);
        assert_eq!(iter.next(), None);

        // 再次调用 next 应该仍然返回 None
        assert_eq!(iter.next(), None);
    }

    /// 测试迭代器在单元素树上的行为
    #[test]
    fn test_iterator_single_element() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);
        map.insert(42, 100);

        for _ in 0..3 {
            let mut iter = map.iter();
            assert_eq!(iter.len(), 1);
            assert_eq!(iter.next(), Some((&42, &100)));
            assert_eq!(iter.next(), None);
        }
    }

    /// 测试在大量数据下的迭代器
    #[test]
    fn test_iterator_large_dataset() {
        let mut map = BPlusTreeMap::<i32, i32>::new(8);

        // 插入 1000 个元素
        for i in 0..1000 {
            map.insert(i, i * 2);
        }

        // 多次迭代
        for _ in 0..3 {
            let collected: Vec<_> = map.iter().collect();
            assert_eq!(collected.len(), 1000);

            // 验证前几个和最后几个元素
            assert_eq!(collected[0], (&0, &0));
            assert_eq!(collected[1], (&1, &2));
            assert_eq!(collected[999], (&999, &1998));

            // 验证排序
            for i in 1..collected.len() {
                assert!(collected[i-1].0 < collected[i].0);
            }
        }
    }

    /// 测试删除后迭代器的正确性
    #[test]
    fn test_iterator_after_removals() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        // 插入 50 个元素
        for i in 0..50 {
            map.insert(i, i * 10);
        }

        // 删除偶数
        for i in (0..50).step_by(2) {
            map.remove(&i);
        }

        // 验证迭代器只返回奇数
        for _ in 0..3 {
            let collected: Vec<_> = map.iter().map(|(k, _)| *k).collect();
            assert_eq!(collected.len(), 25);

            for (idx, &k) in collected.iter().enumerate() {
                assert_eq!(k, idx as i32 * 2 + 1, "Should only have odd numbers");
            }
        }
    }

    /// 测试混合插入删除后的迭代器
    #[test]
    fn test_iterator_after_mixed_operations() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        // 插入一些数据
        for i in 0..30 {
            map.insert(i, i);
        }

        // 删除一些
        for i in (10..20).rev() {
            map.remove(&i);
        }

        // 再插入一些
        for i in 100..110 {
            map.insert(i, i);
        }

        // 多次迭代验证
        for _ in 0..3 {
            let keys: Vec<_> = map.iter().map(|(k, _)| *k).collect();

            // 应该有 30 - 10 + 10 = 30 个元素
            assert_eq!(keys.len(), 30);

            // 验证排序
            for i in 1..keys.len() {
                assert!(keys[i-1] < keys[i]);
            }

            // 验证不包含 10..20
            for k in keys.iter() {
                assert!(*k < 10 || *k >= 20);
            }
        }
    }

    /// 测试迭代器的 size_hint
    #[test]
    fn test_iterator_size_hint() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        for i in 0..50 {
            map.insert(i, i);
        }

        let mut iter = map.iter();
        assert_eq!(iter.size_hint(), (50, Some(50)));
        assert_eq!(iter.len(), 50);

        // 消耗一些元素
        for _ in 0..10 {
            iter.next();
        }

        assert_eq!(iter.size_hint(), (40, Some(40)));
        assert_eq!(iter.len(), 40);

        // 消耗剩余所有元素
        iter.for_each(drop);
    }

    /// Miri 测试：迭代器内存安全
    #[test]
    fn test_iterator_memory_safety() {
        let mut cnt = 0;
        loop {
            let mut map = BPlusTreeMap::<i32, String>::new(4);

            // 插入包含堆分配的数据
            for i in 0..100 {
                map.insert(i, format!("value_{}", i));
            }

            // 多次迭代，检查是否有内存问题
            for round in 0..5 {
                let mut count = 0;
                for (k, v) in map.iter() {
                    assert_eq!(*v, format!("value_{}", k));
                    count += 1;
                }
                assert_eq!(count, 100, "Round {}", round);
            }

            // 清空部分数据
            for i in (0..100).step_by(2) {
                map.remove(&i);
            }

            // 再次迭代
            for _ in 0..5 {
                let count = map.iter().count();
                assert_eq!(count, 50);
            }

            cnt += 1;
            if cnt >= 10 {
                break;
            }
        }
    }

    /// Miri 测试：删除和内存清理
    #[test]
    fn test_remove_memory_safety() {
        let mut map = BPlusTreeMap::<i32, Vec<i32>>::new(4);

        // 插入包含堆分配的 Vec
        for i in 0..50 {
            map.insert(i, vec![i, i * 2, i * 3]);
        }

        // 删除所有偶数
        for i in (0..50).step_by(2) {
            let removed = map.remove(&i);
            assert_eq!(removed, Some(vec![i, i * 2, i * 3]));
        }

        // 验证剩余元素
        for i in (1..50).step_by(2) {
            assert_eq!(map.get(&i), Some(&vec![i, i * 2, i * 3]));
        }

        // 删除所有剩余元素
        for i in (1..50).step_by(2) {
            map.remove(&i);
        }

        assert!(map.is_empty());
    }

    /// Miri 测试：大量随机操作
    #[test]
    fn test_random_operations_memory_safety() {
        use rand::Rng;
        let mut rng = rand::thread_rng();
        let mut map = BPlusTreeMap::<i32, String>::new(8);
        let mut existing_keys = std::collections::HashSet::new();

        // 随机插入
        for _ in 0..200 {
            let k = rng.gen_range(0..1000);
            let v = format!("val_{}", k);
            map.insert(k, v);
            existing_keys.insert(k);
        }

        // 随机删除一半
        let keys_to_remove: Vec<_> = existing_keys.iter().copied().filter(|_| rng.gen_bool(0.5)).collect();
        for k in &keys_to_remove {
            let removed = map.remove(k);
            if removed.is_none() {
                panic!("Failed to remove key {} that should exist in map", k);
            }
            existing_keys.remove(k);
        }

        // 验证长度
        if map.len() != existing_keys.len() {
            // Find keys that are in map but not in existing_keys
            let map_keys: std::collections::HashSet<_> = map.iter().map(|(k, _)| *k).collect();
            let diff: Vec<_> = map_keys.difference(&existing_keys).collect();
            panic!("Map has {} elements but expected {}. Extra keys in map: {:?}",
                   map.len(), existing_keys.len(), diff);
        }

        // 多次迭代验证
        for _ in 0..3 {
            let mut count = 0;
            for (k, v) in map.iter() {
                assert_eq!(*v, format!("val_{}", k));
                count += 1;
            }
            assert_eq!(count, existing_keys.len());
        }
    }

    /// 测试最小 order（边界情况）
    #[test]
    fn test_minimum_order() {
        let mut map = BPlusTreeMap::<i32, i32>::new(3);

        // 插入数据触发分裂
        for i in 0..20 {
            map.insert(i, i * 10);
        }

        // 验证所有数据都能找到
        for i in 0..20 {
            assert_eq!(map.get(&i), Some(&(i * 10)));
        }

        // 验证排序
        let keys: Vec<_> = map.iter().map(|(k, _)| *k).collect();
        for i in 1..keys.len() {
            assert!(keys[i-1] < keys[i]);
        }
    }

    /// 测试逆序插入
    #[test]
    fn test_reverse_insertion() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        // 逆序插入
        for i in (0..100).rev() {
            map.insert(i, i * 2);
        }

        // 验证数据完整性
        assert_eq!(map.len(), 100);

        // 验证迭代器返回正序
        let collected: Vec<_> = map.iter().map(|(k, _)| *k).collect();
        for i in 0..100 {
            assert_eq!(collected[i as usize], i);
        }
    }

    /// 测试键的替换功能
    #[test]
    fn test_key_replacement() {
        let mut map = BPlusTreeMap::<i32, String>::new(4);

        // 插入多个键
        map.insert(10, "first".to_string());
        map.insert(20, "value20".to_string());
        map.insert(30, "value30".to_string());

        assert_eq!(map.len(), 3);

        // 替换键 10 的值
        let old = map.insert(10, "second".to_string());
        assert_eq!(old, Some("first".to_string()));
        assert_eq!(map.len(), 3);  // 长度不变

        // 验证新值
        assert_eq!(map.get(&10), Some(&"second".to_string()));

        // 再次替换
        let old = map.insert(10, "third".to_string());
        assert_eq!(old, Some("second".to_string()));
        assert_eq!(map.len(), 3);

        // 删除
        assert_eq!(map.remove(&10), Some("third".to_string()));
        assert_eq!(map.len(), 2);

        // 删除不存在的键
        assert_eq!(map.remove(&10), None);
        assert_eq!(map.len(), 2);
    }

    /// 测试交替插入和删除
    #[test]
    fn test_alternating_insert_remove() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        for i in 0..50 {
            map.insert(i, i);
            if i > 0 && i % 2 == 0 {
                map.remove(&(i - 1));
            }
        }

        // 验证剩余元素：所有偶数（0, 2, 4, ..., 48）和49
        for i in 0..50 {
            if i % 2 == 0 || i == 49 {
                assert_eq!(map.get(&i), Some(&i), "Key {} should exist", i);
            } else {
                assert_eq!(map.get(&i), None, "Key {} should not exist", i);
            }
        }
    }

    /// 测试删除到只剩一个元素
    #[test]
    fn test_remove_to_single_element() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        // 插入多个元素
        for i in 0..10 {
            map.insert(i, i * 10);
        }

        // 删除到只剩一个
        for i in 0..9 {
            map.remove(&i);
        }

        assert_eq!(map.len(), 1);
        assert_eq!(map.get(&9), Some(&90));
        assert!(!map.is_empty());

        // 迭代器应该只返回一个元素
        let collected: Vec<_> = map.iter().collect();
        assert_eq!(collected.len(), 1);
        assert_eq!(collected[0], (&9, &90));
    }

    /// 测试清空后重新插入
    #[test]
    fn test_clear_and_reinsert() {
        let mut map = BPlusTreeMap::<i32, String>::new(4);

        const C: i32 = 13;
        // 第一轮：插入和删除
        for i in 0..C {
            //println!("Inserting {}", i);
            map.insert(i, format!("first_{}", i));
        }

        for i in 0..C {
            println!("Removing {}", i);
            map.remove(&i);
            if i == 3 {
                let v4 = map.get(&4);
                assert_eq!(v4, Some(&"first_4".to_string()));
            }
        }

        assert!(map.is_empty());

        // 第二轮：重新插入不同的数据
        for i in 0..C {
            map.insert(i, format!("second_{}", i));
        }

        // 验证新数据
        for i in 0..C {
            assert_eq!(map.get(&i), Some(&format!("second_{}", i)));
        }

        assert_eq!(map.len(), C as usize);
    }

    /// 测试大对象的插入和删除（内存压力测试）
    #[test]
    fn test_large_objects() {
        let mut map = BPlusTreeMap::<i32, Vec<u8>>::new(4);

        // 插入大对象（每个 1KB）
        for i in 0..50 {
            let large_vec = vec![i as u8; 1024];
            map.insert(i, large_vec);
        }

        // 验证数据
        for i in 0..50 {
            let v = map.get(&i).unwrap();
            assert_eq!(v.len(), 1024);
            assert_eq!(v[0], i as u8);
        }

        // 删除一半
        for i in (0..50).step_by(2) {
            map.remove(&i);
        }

        // 再次验证
        for i in (1..50).step_by(2) {
            let v = map.get(&i).unwrap();
            assert_eq!(v.len(), 1024);
        }
    }

    /// 测试查找不存在的键（各种情况）
    #[test]
    fn test_get_nonexistent_keys() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        // 空树
        assert_eq!(map.get(&0), None);

        // 插入一些数据
        for i in [10, 20, 30, 40, 50] {
            map.insert(i, i);
        }

        // 测试各种不存在的键
        assert_eq!(map.get(&0), None);   // 小于最小值
        assert_eq!(map.get(&15), None);  // 在两个值之间
        assert_eq!(map.get(&25), None);  // 在两个值之间
        assert_eq!(map.get(&100), None); // 大于最大值
        assert_eq!(map.get(&-1), None);  // 负数
    }

    /// 测试连续删除相同的键
    #[test]
    fn test_remove_same_key_multiple_times() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        map.insert(10, 100);
        map.insert(20, 200);

        // 第一次删除成功
        assert_eq!(map.remove(&10), Some(100));
        assert_eq!(map.len(), 1);

        // 第二次删除应该返回 None
        assert_eq!(map.remove(&10), None);
        assert_eq!(map.len(), 1);

        // 第三次删除仍然返回 None
        assert_eq!(map.remove(&10), None);
        assert_eq!(map.len(), 1);

        // 验证剩余数据没有被影响
        assert_eq!(map.get(&20), Some(&200));
    }

    /// 测试迭代器在删除后的稳定性
    #[test]
    fn test_iterator_stability_after_deletion() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        // 插入 0-99
        for i in 0..100 {
            map.insert(i, i * 2);
        }

        // 删除所有能被 3 整除的数
        for i in (0..100).filter(|x| x % 3 == 0) {
            map.remove(&i);
        }

        // 多次迭代验证
        for _ in 0..3 {
            let keys: Vec<_> = map.iter().map(|(k, _)| *k).collect();

            // 验证所有键都不能被 3 整除
            for k in &keys {
                assert_ne!(k % 3, 0);
            }

            // 验证排序
            for i in 1..keys.len() {
                assert!(keys[i-1] < keys[i]);
            }
        }
    }

    /// Miri 测试：压力测试 - 大量插入删除循环
    #[test]
    fn test_stress_insert_remove_cycles() {
        let mut map = BPlusTreeMap::<i32, String>::new(5);

        // 进行 5 轮插入删除循环
        for round in 0..5 {
            // 插入 100 个元素
            for i in 0..100 {
                map.insert(i, format!("round_{}_val_{}", round, i));
            }

            assert_eq!(map.len(), 100);

            // 删除前 50 个
            for i in 0..50 {
                assert_eq!(map.remove(&i), Some(format!("round_{}_val_{}", round, i)));
            }

            assert_eq!(map.len(), 50);

            // 删除剩余的 50 个
            for i in 50..100 {
                assert_eq!(map.remove(&i), Some(format!("round_{}_val_{}", round, i)));
            }

            assert!(map.is_empty());
        }
    }

    /// 测试 get_mut 修改后的正确性
    #[test]
    fn test_get_mut_modifications() {
        let mut map = BPlusTreeMap::<i32, Vec<i32>>::new(4);

        for i in 0..20 {
            map.insert(i, vec![i]);
        }

        // 使用 get_mut 修改值
        for i in 0..20 {
            if let Some(v) = map.get_mut(&i) {
                v.push(i * 10);
                v.push(i * 100);
            }
        }

        // 验证修改
        for i in 0..20 {
            assert_eq!(map.get(&i), Some(&vec![i, i * 10, i * 100]));
        }

        // 迭代器验证
        for (k, v) in map.iter() {
            assert_eq!(v, &vec![*k, k * 10, k * 100]);
        }
    }

    /// 测试树的不变性：确保没有重复键
    #[test]
    fn test_no_duplicate_keys() {
        use std::collections::HashSet;
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        // 插入大量数据
        for i in 0..500 {
            map.insert(i, i * 2);
        }

        // 收集所有键
        let keys: Vec<_> = map.iter().map(|(k, _)| *k).collect();
        let unique_keys: HashSet<_> = keys.iter().copied().collect();

        // 确保没有重复
        assert_eq!(keys.len(), unique_keys.len(), "Found duplicate keys in tree");
        assert_eq!(keys.len(), 500);

        // 删除一些键后再次检查
        for i in (0..500).step_by(3) {
            map.remove(&i);
        }

        let keys2: Vec<_> = map.iter().map(|(k, _)| *k).collect();
        let unique_keys2: HashSet<_> = keys2.iter().copied().collect();
        assert_eq!(keys2.len(), unique_keys2.len(), "Found duplicate keys after deletion");
    }

    /// 测试分隔键的正确性：确保父节点的分隔键正确
    #[test]
    fn test_separator_keys_correctness() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        // 插入能够触发多次分裂的数据
        for i in 0..100 {
            map.insert(i, i);
        }

        // 验证所有插入的键都能正确查找
        for i in 0..100 {
            assert_eq!(map.get(&i), Some(&i), "Failed to find key {}", i);
        }

        // 验证不存在的键返回None
        for i in 100..110 {
            assert_eq!(map.get(&i), None, "Found non-existent key {}", i);
        }
    }

    /// 测试大量重复插入（替换值）
    #[test]
    fn test_massive_value_replacement() {
        let mut map = BPlusTreeMap::<i32, String>::new(5);

        // 初始插入
        for i in 0..50 {
            map.insert(i, format!("v0_{}", i));
        }

        // 多次替换相同的键
        for round in 1..10 {
            for i in 0..50 {
                let old = map.insert(i, format!("v{}_{}", round, i));
                assert_eq!(old, Some(format!("v{}_{}", round - 1, i)));
            }
            assert_eq!(map.len(), 50);
        }

        // 验证最终值
        for i in 0..50 {
            assert_eq!(map.get(&i), Some(&format!("v9_{}", i)));
        }
    }

    /// 测试边界情况：在根分裂时的正确性
    #[test]
    fn test_root_split_correctness() {
        let mut map = BPlusTreeMap::<i32, i32>::new(3); // 使用最小order

        // 插入直到根分裂
        for i in 0..10 {
            map.insert(i, i * 100);
        }

        // 验证所有键都能找到
        for i in 0..10 {
            assert_eq!(map.get(&i), Some(&(i * 100)));
        }

        // 验证顺序
        let keys: Vec<_> = map.iter().map(|(k, _)| *k).collect();
        for i in 0..keys.len() - 1 {
            assert!(keys[i] < keys[i + 1]);
        }
    }

    /// 测试连续删除导致合并的情况
    #[test]
    fn test_consecutive_deletions_with_merges() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        // 插入100个元素
        for i in 0..100 {
            map.insert(i, i);
        }

        // 从前往后删除，触发多次合并
        for i in 0..90 {
            let removed = map.remove(&i);
            assert_eq!(removed, Some(i), "Failed to remove key {}", i);

            // 验证剩余元素的正确性
            for j in (i + 1)..100 {
                assert_eq!(map.get(&j), Some(&j), "Key {} lost after removing {}", j, i);
            }
        }

        assert_eq!(map.len(), 10);
    }

    /// 测试随机顺序插入和删除
    #[test]
    fn test_random_order_operations() {
        use rand::seq::SliceRandom;
        use rand::Rng;
        let mut rng = rand::thread_rng();
        let mut map = BPlusTreeMap::<i32, i32>::new(7);

        // 创建随机顺序
        let mut keys: Vec<i32> = (0..200).collect();
        keys.shuffle(&mut rng);

        // 随机顺序插入
        for &k in &keys {
            map.insert(k, k * 10);
        }

        // 验证所有键都存在
        for i in 0..200 {
            assert_eq!(map.get(&i), Some(&(i * 10)));
        }

        // 重新打乱并删除
        keys.shuffle(&mut rng);
        for &k in keys.iter().take(150) {
            assert_eq!(map.remove(&k), Some(k * 10));
        }

        assert_eq!(map.len(), 50);

        // 验证迭代器返回正确数量
        assert_eq!(map.iter().count(), 50);
    }

    /// 测试交替插入删除同一个键
    #[test]
    fn test_insert_remove_same_key_alternating() {
        let mut map = BPlusTreeMap::<i32, String>::new(4);

        // 先插入一些其他键以建立树结构
        for i in 0..20 {
            map.insert(i, format!("val_{}", i));
        }

        // 对键10进行多次插入删除
        for round in 0..20 {
            map.insert(10, format!("round_{}", round));
            assert_eq!(map.get(&10), Some(&format!("round_{}", round)));
            assert_eq!(map.remove(&10), Some(format!("round_{}", round)));
            assert_eq!(map.get(&10), None);
        }

        // 验证其他键未受影响
        for i in 0..20 {
            if i != 10 {
                assert_eq!(map.get(&i), Some(&format!("val_{}", i)));
            }
        }
    }

    /// 测试分裂后的兄弟节点借用
    #[test]
    fn test_borrowing_after_splits() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        // 插入足够多的数据触发多次分裂
        for i in 0..50 {
            map.insert(i, i);
        }

        // 删除部分数据触发借用操作
        for i in (10..20).rev() {
            map.remove(&i);
        }

        // 验证所有剩余键都能正确访问
        for i in 0..50 {
            if i < 10 || i >= 20 {
                assert_eq!(map.get(&i), Some(&i), "Key {} should exist", i);
            } else {
                assert_eq!(map.get(&i), None, "Key {} should not exist", i);
            }
        }

        // 验证顺序
        let keys: Vec<_> = map.iter().map(|(k, _)| *k).collect();
        for i in 1..keys.len() {
            assert!(keys[i - 1] < keys[i]);
        }
    }

    /// 测试极端情况：只保留第一个和最后一个元素
    #[test]
    fn test_keep_first_and_last_only() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        for i in 0..100 {
            map.insert(i, i * 10);
        }

        // 删除中间所有元素
        for i in 1..99 {
            map.remove(&i);
        }

        assert_eq!(map.len(), 2);
        assert_eq!(map.get(&0), Some(&0));
        assert_eq!(map.get(&99), Some(&990));

        let collected: Vec<_> = map.iter().map(|(k, v)| (*k, *v)).collect();
        assert_eq!(collected, vec![(0, 0), (99, 990)]);
    }

    /// 测试在不同order下的一致性
    #[test]
    fn test_consistency_across_different_orders() {
        for order in [3, 4, 5, 7, 10, 16] {
            let mut map = BPlusTreeMap::<i32, i32>::new(order);

            // 插入相同数据
            for i in 0..100 {
                map.insert(i, i * 2);
            }

            // 验证所有键
            for i in 0..100 {
                assert_eq!(
                    map.get(&i),
                    Some(&(i * 2)),
                    "Order {}: failed to get key {}",
                    order,
                    i
                );
            }

            // 删除一半
            for i in (0..100).step_by(2) {
                map.remove(&i);
            }

            // 验证剩余键
            for i in 0..100 {
                if i % 2 == 0 {
                    assert_eq!(
                        map.get(&i),
                        None,
                        "Order {}: key {} should be removed",
                        order,
                        i
                    );
                } else {
                    assert_eq!(
                        map.get(&i),
                        Some(&(i * 2)),
                        "Order {}: key {} should exist",
                        order,
                        i
                    );
                }
            }

            assert_eq!(map.len(), 50, "Order {}: wrong length", order);
        }
    }

    /// 压力测试：大量随机操作
    #[test]
    fn test_stress_random_operations_extended() {
        use rand::Rng;
        let mut rng = rand::thread_rng();
        let mut map = BPlusTreeMap::<i32, i32>::new(8);
        let mut expected: std::collections::HashMap<i32, i32> = std::collections::HashMap::new();

        // 进行1000次随机操作
        for _ in 0..1000 {
            let operation = rng.gen_range(0..3);
            let key = rng.gen_range(0..200);

            match operation {
                0 => {
                    // Insert
                    let value = rng.gen_range(0..10000);
                    map.insert(key, value);
                    expected.insert(key, value);
                }
                1 => {
                    // Remove
                    let map_result = map.remove(&key);
                    let expected_result = expected.remove(&key);
                    assert_eq!(map_result, expected_result, "Remove mismatch for key {}", key);
                }
                2 => {
                    // Get
                    let map_result = map.get(&key).copied();
                    let expected_result = expected.get(&key).copied();
                    assert_eq!(map_result, expected_result, "Get mismatch for key {}", key);
                }
                _ => unreachable!(),
            }

            // 定期验证长度
            if rng.gen_bool(0.1) {
                assert_eq!(map.len(), expected.len(), "Length mismatch");
            }
        }

        // 最终验证
        assert_eq!(map.len(), expected.len());
        for (&k, &v) in &expected {
            assert_eq!(map.get(&k), Some(&v), "Final verification failed for key {}", k);
        }
    }

    /// 测试链表完整性
    #[test]
    fn test_leaf_linked_list_integrity() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        // 插入数据
        for i in 0..100 {
            map.insert(i, i);
        }

        // 通过迭代器验证链表
        let iter_keys: Vec<_> = map.iter().map(|(k, _)| *k).collect();
        assert_eq!(iter_keys.len(), 100);

        // 删除一些数据
        for i in (0..100).step_by(3) {
            map.remove(&i);
        }

        // 再次验证链表
        let iter_keys2: Vec<_> = map.iter().map(|(k, _)| *k).collect();
        assert_eq!(iter_keys2.len(), map.len());

        // 验证顺序
        for i in 1..iter_keys2.len() {
            assert!(iter_keys2[i - 1] < iter_keys2[i], "List order broken");
        }
    }

    /// 测试在高度较大的树中的操作
    #[test]
    fn test_deep_tree_operations() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        // 插入足够多数据以创建深度较大的树
        for i in 0..1000 {
            map.insert(i, i * 10);
        }

        // 随机访问
        use rand::Rng;
        let mut rng = rand::thread_rng();
        for _ in 0..200 {
            let k = rng.gen_range(0..1000);
            assert_eq!(map.get(&k), Some(&(k * 10)));
        }

        // 删除一些节点
        for i in (300..700).step_by(5) {
            map.remove(&i);
        }

        // 验证删除后的访问
        for i in 0..1000 {
            if i >= 300 && i < 700 && i % 5 == 0 {
                assert_eq!(map.get(&i), None);
            } else {
                assert_eq!(map.get(&i), Some(&(i * 10)));
            }
        }
    }

    /// 测试删除根节点的唯一子节点情况
    #[test]
    fn test_remove_single_root_child() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        // 插入后删除，反复测试根节点变化
        for round in 0..5 {
            for i in 0..20 {
                map.insert(i, round * 100 + i);
            }

            for i in 0..20 {
                assert_eq!(map.remove(&i), Some(round * 100 + i));
            }

            assert!(map.is_empty());
        }
    }

    /// 测试值的完整性（确保删除不会影响其他值）
    #[test]
    fn test_value_integrity() {
        let mut map = BPlusTreeMap::<i32, Vec<i32>>::new(5);

        // 插入包含不同内容的Vec
        for i in 0..100 {
            map.insert(i, (0..i).collect());
        }

        // 删除一些键
        for i in (0..100).step_by(3) {
            map.remove(&i);
        }

        // 验证剩余值的完整性
        for i in 0..100 {
            if i % 3 != 0 {
                let expected: Vec<i32> = (0..i).collect();
                assert_eq!(map.get(&i), Some(&expected), "Value corrupted for key {}", i);
            }
        }
    }

    /// 测试空树的各种操作
    #[test]
    fn test_empty_tree_operations() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        // 空树删除
        assert_eq!(map.remove(&0), None);
        assert_eq!(map.remove(&100), None);

        // 空树查询
        assert_eq!(map.get(&0), None);
        assert_eq!(map.get_mut(&0), None);

        // 空树迭代
        assert_eq!(map.iter().count(), 0);
        assert!(map.is_empty());
        assert_eq!(map.len(), 0);

        // 插入一个后再删除
        map.insert(42, 100);
        assert_eq!(map.len(), 1);
        assert_eq!(map.remove(&42), Some(100));
        assert!(map.is_empty());
    }

    /// 测试负数键
    #[test]
    fn test_negative_keys() {
        let mut map = BPlusTreeMap::<i32, String>::new(4);

        // 插入负数、零、正数混合
        for i in -50..50 {
            map.insert(i, format!("val_{}", i));
        }

        // 验证所有键都能找到
        for i in -50..50 {
            assert_eq!(map.get(&i), Some(&format!("val_{}", i)));
        }

        // 验证顺序
        let keys: Vec<_> = map.iter().map(|(k, _)| *k).collect();
        for i in 1..keys.len() {
            assert!(keys[i - 1] < keys[i]);
        }

        // 删除负数
        for i in -50..0 {
            assert_eq!(map.remove(&i), Some(format!("val_{}", i)));
        }

        assert_eq!(map.len(), 50);
    }

    /// 测试字符串键
    #[test]
    fn test_string_keys() {
        let mut map = BPlusTreeMap::<String, i32>::new(5);

        let words = vec![
            "apple", "banana", "cherry", "date", "elderberry",
            "fig", "grape", "honeydew", "kiwi", "lemon",
            "mango", "nectarine", "orange", "papaya", "quince"
        ];

        // 插入
        for (i, &word) in words.iter().enumerate() {
            map.insert(word.to_string(), i as i32);
        }

        // 验证
        for (i, &word) in words.iter().enumerate() {
            assert_eq!(map.get(&word.to_string()), Some(&(i as i32)));
        }

        // 验证按字母顺序排序
        let keys: Vec<_> = map.iter().map(|(k, _)| k.clone()).collect();
        let mut sorted_words: Vec<_> = words.iter().map(|s| s.to_string()).collect();
        sorted_words.sort();
        assert_eq!(keys, sorted_words);
    }

    /// 测试大order值
    #[test]
    fn test_large_order() {
        let mut map = BPlusTreeMap::<i32, i32>::new(100);

        // 插入数据
        for i in 0..500 {
            map.insert(i, i * 2);
        }

        // 验证
        for i in 0..500 {
            assert_eq!(map.get(&i), Some(&(i * 2)));
        }

        // 删除
        for i in (0..500).step_by(2) {
            map.remove(&i);
        }

        assert_eq!(map.len(), 250);
    }

    /// 测试专门从右兄弟借用
    #[test]
    fn test_borrow_from_right_sibling() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        // 构造特定结构：创建一个会从右兄弟借用的场景
        for i in 0..20 {
            map.insert(i, i);
        }

        // 删除左侧节点的元素，触发从右兄弟借用
        // 这个具体取决于树的结构，但我们可以通过删除来触发
        for i in 0..5 {
            map.remove(&i);
        }

        // 验证剩余元素正确
        for i in 5..20 {
            assert_eq!(map.get(&i), Some(&i));
        }

        // 验证长度和顺序
        assert_eq!(map.len(), 15);
        let keys: Vec<_> = map.iter().map(|(k, _)| *k).collect();
        assert_eq!(keys.len(), 15);
        for i in 1..keys.len() {
            assert!(keys[i - 1] < keys[i]);
        }
    }

    /// 测试与右兄弟合并
    #[test]
    fn test_merge_with_right_sibling() {
        let mut map = BPlusTreeMap::<i32, i32>::new(3);

        // 插入数据
        for i in 0..20 {
            map.insert(i, i * 10);
        }

        // 删除足够多的元素以触发合并
        for i in 0..15 {
            map.remove(&i);
        }

        // 验证剩余元素
        for i in 15..20 {
            assert_eq!(map.get(&i), Some(&(i * 10)));
        }

        assert_eq!(map.len(), 5);
    }

    /// 测试树高度降低（多层合并）
    #[test]
    fn test_tree_height_reduction() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        // 插入足够数据构建多层树
        for i in 0..200 {
            map.insert(i, i);
        }

        // 删除大部分数据，应该导致树高度降低
        for i in 0..190 {
            map.remove(&i);
        }

        // 验证剩余10个元素
        assert_eq!(map.len(), 10);
        for i in 190..200 {
            assert_eq!(map.get(&i), Some(&i));
        }

        // 继续删除直到只剩1个
        for i in 190..199 {
            map.remove(&i);
        }

        assert_eq!(map.len(), 1);
        assert_eq!(map.get(&199), Some(&199));
    }

    /// 测试迭代器在各种状态下的正确性
    #[test]
    fn test_iterator_edge_cases() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        // 空迭代器
        assert_eq!(map.iter().count(), 0);

        // 插入1个元素后迭代
        map.insert(1, 10);
        assert_eq!(map.iter().count(), 1);

        // 插入后再删除，然后迭代
        map.insert(2, 20);
        map.remove(&1);
        assert_eq!(map.iter().count(), 1);

        // 构建较大的树，然后测试部分迭代
        for i in 0..50 {
            map.insert(i, i * 10);
        }

        let mut iter = map.iter();
        let first_10: Vec<_> = iter.by_ref().take(10).collect();
        assert_eq!(first_10.len(), 10);

        // 继续迭代剩余部分
        let remaining: Vec<_> = iter.collect();
        assert_eq!(remaining.len(), 40); // 50(keys 0-49) - 10(taken) = 40
    }

    /// 测试所有键都相等的情况（插入重复键）
    #[test]
    fn test_all_same_keys() {
        let mut map = BPlusTreeMap::<i32, Vec<i32>>::new(4);

        // 多次插入相同的键，每次更新值
        for i in 0..20 {
            let old = map.insert(42, vec![i]);
            if i == 0 {
                assert_eq!(old, None);
            } else {
                assert_eq!(old, Some(vec![i - 1]));
            }
        }

        // 应该只有一个键
        assert_eq!(map.len(), 1);
        assert_eq!(map.get(&42), Some(&vec![19]));
    }

    /// 测试逆序删除
    #[test]
    fn test_reverse_deletion() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        for i in 0..100 {
            map.insert(i, i);
        }

        // 逆序删除
        for i in (0..100).rev() {
            assert_eq!(map.remove(&i), Some(i));
            assert_eq!(map.len(), i as usize);
        }

        assert!(map.is_empty());
    }

    /// 测试交替从两端删除
    #[test]
    fn test_delete_from_both_ends() {
        let mut map = BPlusTreeMap::<i32, i32>::new(5);

        for i in 0..100 {
            map.insert(i, i * 2);
        }

        // 交替从两端删除
        for i in 0..25 {
            // 从前面删除
            assert_eq!(map.remove(&i), Some(i * 2));
            // 从后面删除
            assert_eq!(map.remove(&(99 - i)), Some((99 - i) * 2));
        }

        assert_eq!(map.len(), 50);

        // 验证中间的元素
        for i in 25..75 {
            assert_eq!(map.get(&i), Some(&(i * 2)));
        }
    }

    /// 测试get_mut后不改变树结构
    #[test]
    fn test_get_mut_preserves_structure() {
        let mut map = BPlusTreeMap::<i32, Vec<i32>>::new(4);

        for i in 0..50 {
            map.insert(i, vec![i]);
        }

        // 使用get_mut修改所有值
        for i in 0..50 {
            if let Some(v) = map.get_mut(&i) {
                v.push(i * 10);
            }
        }

        // 验证修改成功且树结构正确
        for i in 0..50 {
            assert_eq!(map.get(&i), Some(&vec![i, i * 10]));
        }

        // 验证长度不变
        assert_eq!(map.len(), 50);

        // 验证可以继续插入和删除
        map.insert(100, vec![100]);
        assert_eq!(map.len(), 51);

        map.remove(&25);
        assert_eq!(map.len(), 50);
    }

    /// 测试在极小order下的稳定性
    #[test]
    fn test_minimum_order_stability() {
        let mut map = BPlusTreeMap::<i32, i32>::new(3);

        // 大量随机操作
        use rand::Rng;
        let mut rng = rand::thread_rng();

        for _ in 0..200 {
            let op = rng.gen_range(0..2);
            let key = rng.gen_range(0..50);

            match op {
                0 => { map.insert(key, key * 10); }
                1 => { map.remove(&key); }
                _ => unreachable!(),
            }
        }

        // 验证树的一致性
        let keys: Vec<_> = map.iter().map(|(k, _)| *k).collect();
        for i in 1..keys.len() {
            assert!(keys[i - 1] < keys[i], "Keys not in order");
        }

        // 验证长度一致性
        assert_eq!(map.iter().count(), map.len());
    }

    /// 测试特定的内部节点合并场景
    #[test]
    fn test_internal_node_merge() {
        let mut map = BPlusTreeMap::<i32, i32>::new(4);

        // 插入足够数据创建内部节点
        for i in 0..100 {
            map.insert(i, i);
        }

        // 删除足够多数据触发内部节点合并
        for i in 0..85 {
            map.remove(&i);
        }

        // 验证剩余数据
        assert_eq!(map.len(), 15);
        for i in 85..100 {
            assert_eq!(map.get(&i), Some(&i));
        }

        // 验证迭代器
        let keys: Vec<_> = map.iter().map(|(k, _)| *k).collect();
        assert_eq!(keys.len(), 15);
        for i in 1..keys.len() {
            assert!(keys[i - 1] < keys[i]);
        }
    }

    /// 测试范围查询（通过迭代器实现）
    #[test]
    fn test_range_query_simulation() {
        let mut map = BPlusTreeMap::<i32, i32>::new(5);

        for i in 0..100 {
            map.insert(i, i * 10);
        }

        // 模拟范围查询 [20, 30)
        let range_items: Vec<_> = map.iter()
            .filter(|(k, _)| **k >= 20 && **k < 30)
            .map(|(k, v)| (*k, *v))
            .collect();

        assert_eq!(range_items.len(), 10);
        for (i, &(k, v)) in range_items.iter().enumerate() {
            assert_eq!(k, 20 + i as i32);
            assert_eq!(v, (20 + i as i32) * 10);
        }
    }

    /// 测试混合类型的复杂值
    #[test]
    fn test_complex_value_types() {
        #[derive(Debug, PartialEq, Clone)]
        struct ComplexValue {
            id: i32,
            name: String,
            data: Vec<u8>,
        }

        let mut map = BPlusTreeMap::<i32, ComplexValue>::new(4);

        for i in 0..30 {
            map.insert(i, ComplexValue {
                id: i,
                name: format!("item_{}", i),
                data: vec![i as u8; 10],
            });
        }

        // 验证
        for i in 0..30 {
            let val = map.get(&i).unwrap();
            assert_eq!(val.id, i);
            assert_eq!(val.name, format!("item_{}", i));
            assert_eq!(val.data.len(), 10);
        }

        // 删除并验证
        for i in (0..30).step_by(3) {
            let removed = map.remove(&i).unwrap();
            assert_eq!(removed.id, i);
        }

        assert_eq!(map.len(), 20);
    }
}