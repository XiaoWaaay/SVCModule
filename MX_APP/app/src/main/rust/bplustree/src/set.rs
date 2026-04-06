use core::cmp::Ordering::{self, Equal, Greater, Less};
use core::fmt::{self, Debug};
use core::iter::{FusedIterator, Peekable};
use core::ops::{BitAnd, BitOr, BitXor, Sub};
use crate::BPlusTreeMap;

#[derive(Debug, Eq, PartialEq, Ord, PartialOrd, Hash, Clone, Default, Copy)]
pub(super) struct SetValZST;

pub struct BPlusTreeSet<T> {
    map: BPlusTreeMap<T, SetValZST>,
}

unsafe impl<T: Send> Send for BPlusTreeSet<T> {}
unsafe impl<T: Sync> Sync for BPlusTreeSet<T> {}

impl<T: Ord + Clone + Debug> Debug for BPlusTreeSet<T> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_set().entries(self.iter()).finish()
    }
}

impl<T: Ord + Clone> PartialEq for BPlusTreeSet<T> {
    fn eq(&self, other: &BPlusTreeSet<T>) -> bool {
        if self.len() != other.len() {
            return false;
        }
        self.iter().zip(other.iter()).all(|(a, b)| a == b)
    }
}

impl<T: Ord + Clone> Eq for BPlusTreeSet<T> {}

impl<T: Ord + Clone> PartialOrd for BPlusTreeSet<T> {
    fn partial_cmp(&self, other: &BPlusTreeSet<T>) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

impl<T: Ord + Clone> Ord for BPlusTreeSet<T> {
    fn cmp(&self, other: &BPlusTreeSet<T>) -> Ordering {
        self.iter().cmp(other.iter())
    }
}

impl<T: Ord + Clone> BPlusTreeSet<T> {
    /// Creates a new empty B+ tree set with the specified order.
    /// The order must be at least 3.
    pub fn new(order: u16) -> Self {
        BPlusTreeSet {
            map: BPlusTreeMap::new(order),
        }
    }

    /// Clears the set, removing all elements.
    pub fn clear(&mut self) {
        *self = BPlusTreeSet::new(self.map.order);
    }

    /// Returns true if the set contains the specified value.
    pub fn contains(&self, value: &T) -> bool {
        self.map.get(value).is_some()
    }

    /// Adds a value to the set.
    /// Returns true if the value was newly inserted, false if it already existed.
    pub fn insert(&mut self, value: T) -> bool {
        self.map.insert(value, SetValZST).is_none()
    }

    /// Removes a value from the set.
    /// Returns true if the value was present in the set.
    pub fn remove(&mut self, value: &T) -> bool {
        self.map.remove(value).is_some()
    }

    /// Returns the number of elements in the set.
    pub fn len(&self) -> usize {
        self.map.len()
    }

    /// Returns true if the set contains no elements.
    pub fn is_empty(&self) -> bool {
        self.map.is_empty()
    }

    /// Returns an iterator over the set's elements in ascending order.
    pub fn iter(&self) -> Iter<'_, T> {
        Iter {
            inner: self.map.iter(),
        }
    }

    /// Returns true if the set is a subset of another set.
    pub fn is_subset(&self, other: &BPlusTreeSet<T>) -> bool {
        if self.len() > other.len() {
            return false;
        }
        self.iter().all(|v| other.contains(v))
    }

    /// Returns true if the set is a superset of another set.
    pub fn is_superset(&self, other: &BPlusTreeSet<T>) -> bool {
        other.is_subset(self)
    }

    /// Returns true if the set has no elements in common with another set.
    pub fn is_disjoint(&self, other: &BPlusTreeSet<T>) -> bool {
        if self.len() <= other.len() {
            self.iter().all(|v| !other.contains(v))
        } else {
            other.iter().all(|v| !self.contains(v))
        }
    }

    /// Returns the union of two sets as a new set.
    pub fn union<'a>(&'a self, other: &'a BPlusTreeSet<T>) -> Union<'a, T> {
        Union {
            a: self.iter().peekable(),
            b: other.iter().peekable(),
        }
    }

    /// Returns the intersection of two sets as a new set.
    pub fn intersection<'a>(&'a self, other: &'a BPlusTreeSet<T>) -> Intersection<'a, T> {
        let (small, large) = if self.len() <= other.len() {
            (self, other)
        } else {
            (other, self)
        };
        Intersection {
            iter: small.iter(),
            other: large,
        }
    }

    /// Returns the difference of two sets (elements in self but not in other).
    pub fn difference<'a>(&'a self, other: &'a BPlusTreeSet<T>) -> Difference<'a, T> {
        Difference {
            iter: self.iter(),
            other,
        }
    }

    /// Returns the symmetric difference of two sets.
    pub fn symmetric_difference<'a>(
        &'a self,
        other: &'a BPlusTreeSet<T>,
    ) -> SymmetricDifference<'a, T> {
        SymmetricDifference {
            a: self.iter().peekable(),
            b: other.iter().peekable(),
        }
    }
}

impl<T: Ord + Clone> Default for BPlusTreeSet<T> {
    fn default() -> Self {
        BPlusTreeSet::new(3)
    }
}

impl<T: Ord + Clone> FromIterator<T> for BPlusTreeSet<T> {
    fn from_iter<I: IntoIterator<Item = T>>(iter: I) -> Self {
        let mut set = BPlusTreeSet::new(3);
        for item in iter {
            set.insert(item);
        }
        set
    }
}

impl<T: Ord + Clone, const N: usize> From<[T; N]> for BPlusTreeSet<T> {
    fn from(arr: [T; N]) -> Self {
        arr.into_iter().collect()
    }
}

impl<T: Ord + Clone> Extend<T> for BPlusTreeSet<T> {
    fn extend<I: IntoIterator<Item = T>>(&mut self, iter: I) {
        for item in iter {
            self.insert(item);
        }
    }
}

impl<'a, T: 'a + Ord + Clone + Copy> Extend<&'a T> for BPlusTreeSet<T> {
    fn extend<I: IntoIterator<Item = &'a T>>(&mut self, iter: I) {
        self.extend(iter.into_iter().cloned());
    }
}

impl<'a, T: Ord + Clone> IntoIterator for &'a BPlusTreeSet<T> {
    type Item = &'a T;
    type IntoIter = Iter<'a, T>;

    fn into_iter(self) -> Iter<'a, T> {
        self.iter()
    }
}

pub struct Iter<'a, T> {
    inner: crate::map::Iter<'a, T, SetValZST>,
}

impl<'a, T> Iterator for Iter<'a, T> {
    type Item = &'a T;

    fn next(&mut self) -> Option<&'a T> {
        self.inner.next().map(|(k, _)| k)
    }

    fn size_hint(&self) -> (usize, Option<usize>) {
        self.inner.size_hint()
    }
}

impl<'a, T> FusedIterator for Iter<'a, T> {}

pub struct Union<'a, T> {
    a: Peekable<Iter<'a, T>>,
    b: Peekable<Iter<'a, T>>,
}

impl<'a, T: Ord> Iterator for Union<'a, T> {
    type Item = &'a T;

    fn next(&mut self) -> Option<&'a T> {
        let a_peek = self.a.peek();
        let b_peek = self.b.peek();

        match (a_peek, b_peek) {
            (Some(a_val), Some(b_val)) => match a_val.cmp(b_val) {
                Less => self.a.next(),
                Greater => self.b.next(),
                Equal => {
                    self.b.next();
                    self.a.next()
                }
            },
            (Some(_), None) => self.a.next(),
            (None, Some(_)) => self.b.next(),
            (None, None) => None,
        }
    }
}

impl<T: Ord> FusedIterator for Union<'_, T> {}

pub struct Intersection<'a, T> {
    iter: Iter<'a, T>,
    other: &'a BPlusTreeSet<T>,
}

impl<'a, T: Ord + Clone> Iterator for Intersection<'a, T> {
    type Item = &'a T;

    fn next(&mut self) -> Option<&'a T> {
        loop {
            let elt = self.iter.next()?;
            if self.other.contains(elt) {
                return Some(elt);
            }
        }
    }

    fn size_hint(&self) -> (usize, Option<usize>) {
        (0, self.iter.size_hint().1)
    }
}

impl<T: Ord + Clone> FusedIterator for Intersection<'_, T> {}

pub struct Difference<'a, T> {
    iter: Iter<'a, T>,
    other: &'a BPlusTreeSet<T>,
}

impl<'a, T: Ord + Clone> Iterator for Difference<'a, T> {
    type Item = &'a T;

    fn next(&mut self) -> Option<&'a T> {
        loop {
            let elt = self.iter.next()?;
            if !self.other.contains(elt) {
                return Some(elt);
            }
        }
    }

    fn size_hint(&self) -> (usize, Option<usize>) {
        (0, self.iter.size_hint().1)
    }
}

impl<T: Ord + Clone> FusedIterator for Difference<'_, T> {}

pub struct SymmetricDifference<'a, T> {
    a: Peekable<Iter<'a, T>>,
    b: Peekable<Iter<'a, T>>,
}

impl<'a, T: Ord> Iterator for SymmetricDifference<'a, T> {
    type Item = &'a T;

    fn next(&mut self) -> Option<&'a T> {
        let a_peek = self.a.peek();
        let b_peek = self.b.peek();

        match (a_peek, b_peek) {
            (Some(a_val), Some(b_val)) => match a_val.cmp(b_val) {
                Less => self.a.next(),
                Greater => self.b.next(),
                Equal => {
                    self.a.next();
                    self.b.next();
                    self.next()
                }
            },
            (Some(_), None) => self.a.next(),
            (None, Some(_)) => self.b.next(),
            (None, None) => None,
        }
    }
}

impl<T: Ord> FusedIterator for SymmetricDifference<'_, T> {}

impl<T: Ord + Clone> BitOr<&BPlusTreeSet<T>> for &BPlusTreeSet<T> {
    type Output = BPlusTreeSet<T>;

    fn bitor(self, rhs: &BPlusTreeSet<T>) -> BPlusTreeSet<T> {
        self.union(rhs).cloned().collect()
    }
}

impl<T: Ord + Clone> BitAnd<&BPlusTreeSet<T>> for &BPlusTreeSet<T> {
    type Output = BPlusTreeSet<T>;

    fn bitand(self, rhs: &BPlusTreeSet<T>) -> BPlusTreeSet<T> {
        self.intersection(rhs).cloned().collect()
    }
}

impl<T: Ord + Clone> BitXor<&BPlusTreeSet<T>> for &BPlusTreeSet<T> {
    type Output = BPlusTreeSet<T>;

    fn bitxor(self, rhs: &BPlusTreeSet<T>) -> BPlusTreeSet<T> {
        self.symmetric_difference(rhs).cloned().collect()
    }
}

impl<T: Ord + Clone> Sub<&BPlusTreeSet<T>> for &BPlusTreeSet<T> {
    type Output = BPlusTreeSet<T>;

    fn sub(self, rhs: &BPlusTreeSet<T>) -> BPlusTreeSet<T> {
        self.difference(rhs).cloned().collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_new_and_empty() {
        let set: BPlusTreeSet<i32> = BPlusTreeSet::new(3);
        assert!(set.is_empty());
        assert_eq!(set.len(), 0);
    }

    #[test]
    fn test_insert_and_contains() {
        let mut set = BPlusTreeSet::new(3);

        assert!(set.insert(5));
        assert!(set.insert(3));
        assert!(set.insert(7));
        assert!(!set.insert(5)); // Already exists

        assert_eq!(set.len(), 3);
        assert!(set.contains(&5));
        assert!(set.contains(&3));
        assert!(set.contains(&7));
        assert!(!set.contains(&1));
    }

    #[test]
    fn test_insert_many_elements() {
        let mut set = BPlusTreeSet::new(4);

        for i in 0..100 {
            assert!(set.insert(i));
        }

        assert_eq!(set.len(), 100);

        for i in 0..100 {
            assert!(set.contains(&i));
        }
    }

    #[test]
    fn test_remove() {
        let mut set = BPlusTreeSet::new(3);

        set.insert(1);
        set.insert(2);
        set.insert(3);
        set.insert(4);
        set.insert(5);

        assert!(set.remove(&3));
        assert!(!set.contains(&3));
        assert_eq!(set.len(), 4);

        assert!(!set.remove(&3)); // Already removed
        assert!(set.remove(&1));
        assert!(set.remove(&5));

        assert_eq!(set.len(), 2);
        assert!(set.contains(&2));
        assert!(set.contains(&4));
    }

    #[test]
    fn test_clear() {
        let mut set = BPlusTreeSet::new(3);

        set.insert(1);
        set.insert(2);
        set.insert(3);

        assert_eq!(set.len(), 3);

        set.clear();

        assert!(set.is_empty());
        assert_eq!(set.len(), 0);
        assert!(!set.contains(&1));
    }

    #[test]
    fn test_iter() {
        let mut set = BPlusTreeSet::new(3);

        set.insert(5);
        set.insert(1);
        set.insert(3);
        set.insert(7);
        set.insert(2);

        let collected: Vec<_> = set.iter().copied().collect();
        assert_eq!(collected, vec![1, 2, 3, 5, 7]); // Should be sorted
    }

    #[test]
    fn test_into_iterator() {
        let mut set = BPlusTreeSet::new(3);

        set.insert(3);
        set.insert(1);
        set.insert(2);

        let collected: Vec<_> = (&set).into_iter().copied().collect();
        assert_eq!(collected, vec![1, 2, 3]);
    }

    #[test]
    fn test_from_iter() {
        let set: BPlusTreeSet<_> = [5, 3, 7, 1, 9].into_iter().collect();

        assert_eq!(set.len(), 5);
        assert!(set.contains(&1));
        assert!(set.contains(&3));
        assert!(set.contains(&5));
        assert!(set.contains(&7));
        assert!(set.contains(&9));
    }

    #[test]
    fn test_from_array() {
        let set = BPlusTreeSet::from([1, 2, 3, 4, 5]);

        assert_eq!(set.len(), 5);
        for i in 1..=5 {
            assert!(set.contains(&i));
        }
    }

    #[test]
    fn test_extend() {
        let mut set = BPlusTreeSet::new(3);
        set.insert(1);
        set.insert(2);

        set.extend([3, 4, 5]);

        assert_eq!(set.len(), 5);
        for i in 1..=5 {
            assert!(set.contains(&i));
        }
    }

    #[test]
    fn test_is_subset() {
        let set1: BPlusTreeSet<_> = [1, 2, 3].into_iter().collect();
        let set2: BPlusTreeSet<_> = [1, 2, 3, 4, 5].into_iter().collect();
        let set3: BPlusTreeSet<_> = [1, 2, 6].into_iter().collect();

        assert!(set1.is_subset(&set2));
        assert!(!set2.is_subset(&set1));
        assert!(!set1.is_subset(&set3));
        assert!(set1.is_subset(&set1)); // Set is subset of itself
    }

    #[test]
    fn test_is_superset() {
        let set1: BPlusTreeSet<_> = [1, 2, 3, 4, 5].into_iter().collect();
        let set2: BPlusTreeSet<_> = [1, 2, 3].into_iter().collect();

        assert!(set1.is_superset(&set2));
        assert!(!set2.is_superset(&set1));
        assert!(set1.is_superset(&set1));
    }

    #[test]
    fn test_is_disjoint() {
        let set1: BPlusTreeSet<_> = [1, 2, 3].into_iter().collect();
        let set2: BPlusTreeSet<_> = [4, 5, 6].into_iter().collect();
        let set3: BPlusTreeSet<_> = [3, 4, 5].into_iter().collect();

        assert!(set1.is_disjoint(&set2));
        assert!(!set1.is_disjoint(&set3));
        assert!(!set2.is_disjoint(&set3));
    }

    #[test]
    fn test_union() {
        let set1: BPlusTreeSet<_> = [1, 2, 3].into_iter().collect();
        let set2: BPlusTreeSet<_> = [3, 4, 5].into_iter().collect();

        let union: Vec<_> = set1.union(&set2).copied().collect();
        assert_eq!(union, vec![1, 2, 3, 4, 5]);
    }

    #[test]
    fn test_union_operator() {
        let set1: BPlusTreeSet<_> = [1, 2, 3].into_iter().collect();
        let set2: BPlusTreeSet<_> = [3, 4, 5].into_iter().collect();

        let union = &set1 | &set2;

        assert_eq!(union.len(), 5);
        for i in 1..=5 {
            assert!(union.contains(&i));
        }
    }

    #[test]
    fn test_intersection() {
        let set1: BPlusTreeSet<_> = [1, 2, 3, 4].into_iter().collect();
        let set2: BPlusTreeSet<_> = [3, 4, 5, 6].into_iter().collect();

        let intersection: Vec<_> = set1.intersection(&set2).copied().collect();
        assert_eq!(intersection, vec![3, 4]);
    }

    #[test]
    fn test_intersection_operator() {
        let set1: BPlusTreeSet<_> = [1, 2, 3, 4].into_iter().collect();
        let set2: BPlusTreeSet<_> = [3, 4, 5, 6].into_iter().collect();

        let intersection = &set1 & &set2;

        assert_eq!(intersection.len(), 2);
        assert!(intersection.contains(&3));
        assert!(intersection.contains(&4));
    }

    #[test]
    fn test_difference() {
        let set1: BPlusTreeSet<_> = [1, 2, 3, 4].into_iter().collect();
        let set2: BPlusTreeSet<_> = [3, 4, 5, 6].into_iter().collect();

        let diff: Vec<_> = set1.difference(&set2).copied().collect();
        assert_eq!(diff, vec![1, 2]);
    }

    #[test]
    fn test_difference_operator() {
        let set1: BPlusTreeSet<_> = [1, 2, 3, 4].into_iter().collect();
        let set2: BPlusTreeSet<_> = [3, 4, 5, 6].into_iter().collect();

        let diff = &set1 - &set2;

        assert_eq!(diff.len(), 2);
        assert!(diff.contains(&1));
        assert!(diff.contains(&2));
    }

    #[test]
    fn test_symmetric_difference() {
        let set1: BPlusTreeSet<_> = [1, 2, 3, 4].into_iter().collect();
        let set2: BPlusTreeSet<_> = [3, 4, 5, 6].into_iter().collect();

        let sym_diff: Vec<_> = set1.symmetric_difference(&set2).copied().collect();
        assert_eq!(sym_diff, vec![1, 2, 5, 6]);
    }

    #[test]
    fn test_symmetric_difference_operator() {
        let set1: BPlusTreeSet<_> = [1, 2, 3, 4].into_iter().collect();
        let set2: BPlusTreeSet<_> = [3, 4, 5, 6].into_iter().collect();

        let sym_diff = &set1 ^ &set2;

        assert_eq!(sym_diff.len(), 4);
        assert!(sym_diff.contains(&1));
        assert!(sym_diff.contains(&2));
        assert!(sym_diff.contains(&5));
        assert!(sym_diff.contains(&6));
        assert!(!sym_diff.contains(&3));
        assert!(!sym_diff.contains(&4));
    }

    #[test]
    fn test_equality() {
        let set1: BPlusTreeSet<_> = [1, 2, 3].into_iter().collect();
        let set2: BPlusTreeSet<_> = [1, 2, 3].into_iter().collect();
        let set3: BPlusTreeSet<_> = [1, 2, 4].into_iter().collect();

        assert_eq!(set1, set2);
        assert_ne!(set1, set3);
    }

    #[test]
    fn test_ordering() {
        let set1: BPlusTreeSet<_> = [1, 2, 3].into_iter().collect();
        let set2: BPlusTreeSet<_> = [1, 2, 4].into_iter().collect();
        let set3: BPlusTreeSet<_> = [1, 2, 3, 4].into_iter().collect();

        assert!(set1 < set2);
        assert!(set1 < set3);
        assert!(set2 > set1);
    }

    #[test]
    fn test_default() {
        let set: BPlusTreeSet<i32> = Default::default();
        assert!(set.is_empty());
    }

    #[test]
    fn test_large_dataset() {
        let mut set = BPlusTreeSet::new(5);

        for i in 0..1000 {
            set.insert(i);
        }

        assert_eq!(set.len(), 1000);

        for i in 0..1000 {
            assert!(set.contains(&i));
        }

        for i in (0..1000).step_by(2) {
            set.remove(&i);
        }

        assert_eq!(set.len(), 500);

        for i in (0..1000).step_by(2) {
            assert!(!set.contains(&i));
        }

        for i in (1..1000).step_by(2) {
            assert!(set.contains(&i));
        }
    }

    #[test]
    fn test_string_set() {
        let mut set = BPlusTreeSet::new(3);

        set.insert("apple".to_string());
        set.insert("banana".to_string());
        set.insert("cherry".to_string());

        assert!(set.contains(&"apple".to_string()));
        assert!(set.contains(&"banana".to_string()));
        assert!(!set.contains(&"orange".to_string()));

        let items: Vec<_> = set.iter().map(|s| s.as_str()).collect();
        assert_eq!(items, vec!["apple", "banana", "cherry"]);
    }

    #[test]
    fn test_reverse_insertion_order() {
        let mut set = BPlusTreeSet::new(4);

        for i in (0..50).rev() {
            set.insert(i);
        }

        assert_eq!(set.len(), 50);

        let items: Vec<_> = set.iter().copied().collect();
        assert_eq!(items, (0..50).collect::<Vec<_>>());
    }

    #[test]
    fn test_complex_set_operations() {
        let set1: BPlusTreeSet<_> = (1..=10).collect();
        let set2: BPlusTreeSet<_> = (5..=15).collect();
        let set3: BPlusTreeSet<_> = (10..=20).collect();

        let result1 = &(&set1 | &set2) & &set3;
        let expected1: Vec<_> = result1.iter().copied().collect();
        assert_eq!(expected1, vec![10, 11, 12, 13, 14, 15]);

        let result2 = &set1 - &(&set2 & &set3);
        let expected2: Vec<_> = result2.iter().copied().collect();
        assert_eq!(expected2, (1..=9).collect::<Vec<_>>());
    }
}
