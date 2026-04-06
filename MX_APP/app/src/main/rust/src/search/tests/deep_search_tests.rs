//! Deep search mode tests
//!
//! Deep search mode exhaustively finds all possible combinations
//! when there are duplicate values in memory, unlike the standard
//! first-match strategy.

#[cfg(test)]
mod tests {
    use bplustree::BPlusTreeSet;
    use crate::search::{SearchMode, SearchQuery, SearchValue, ValueType};
    use crate::search::tests::mock_memory::MockMemory;
    use crate::search::engine::manager::ValuePair;
    use crate::search::engine::group_search::search_in_buffer_group_deep;
    use crate::wuwa::PageStatusBitmap;

    // ==================== Test Cases ====================

    /// Test the exact scenario from the user's bug report:
    /// Memory: 0x1000(100), 0x1004(200), 0x1008(300), 0x100C(300)
    /// Query: [100, 200, 300] ordered, range=16
    ///
    /// Standard search returns: [0x1000, 0x1004, 0x1008] (first match only)
    /// Deep search should return: [0x1000, 0x1004, 0x1008, 0x100C] (all combinations)
    #[test]
    fn test_deep_search_ordered_duplicate_last_value() {
        println!("\n=== Deep search test: duplicate last value ===\n");

        let mut mem = MockMemory::new();
        let base_addr = mem.malloc(0x7000000000, 64 * 1024).unwrap();

        println!("Allocated memory: 0x{:X}, size: 64KB", base_addr);

        // Write test pattern: 100, 200, 300, 300
        let offset_0 = 0x1000u64;
        mem.mem_write_u32(base_addr + offset_0, 100).unwrap();
        mem.mem_write_u32(base_addr + offset_0 + 0x4, 200).unwrap();
        mem.mem_write_u32(base_addr + offset_0 + 0x8, 300).unwrap();
        mem.mem_write_u32(base_addr + offset_0 + 0xC, 300).unwrap();

        println!("Memory layout:");
        println!("  0x{:X}: 100", base_addr + offset_0);
        println!("  0x{:X}: 200", base_addr + offset_0 + 0x4);
        println!("  0x{:X}: 300", base_addr + offset_0 + 0x8);
        println!("  0x{:X}: 300 (duplicate)", base_addr + offset_0 + 0xC);

        // Create query: [100, 200, 300] ordered, range=16
        let values = vec![
            SearchValue::fixed(100, ValueType::Dword),
            SearchValue::fixed(200, ValueType::Dword),
            SearchValue::fixed(300, ValueType::Dword),
        ];
        let query = SearchQuery::new(values, SearchMode::Ordered, 16);

        println!("\nQuery: [100, 200, 300] ordered, range=16 bytes");

        // Read memory chunk
        let search_start = base_addr;
        let search_size = 64 * 1024; // 16 bytes
        let mut buffer = vec![0u8; search_size];
        let mut page_status = PageStatusBitmap::new(buffer.len(), search_start as usize);

        mem.mem_read_with_status(search_start, &mut buffer, &mut page_status).unwrap();

        println!("\nPerforming deep search...");

        // Execute deep search
        let mut results = BPlusTreeSet::new(32);
        let mut matches_checked = 0usize;

        search_in_buffer_group_deep(
            &buffer,
            search_start,
            search_start,
            search_start + search_size as u64,
            4, // min_element_size (Dword)
            &query,
            &page_status,
            &mut results,
            &mut matches_checked,
        );

        println!("\n=== Search results ===");
        println!("Checked {} positions", matches_checked);
        println!("Found {} addresses", results.len());

        for (i, pair) in results.iter().enumerate() {
            let offset = pair.addr - base_addr;
            println!("  [{}] 0x{:X} (offset: 0x{:X})", i + 1, pair.addr, offset);
        }

        // Verify results - should contain ALL 4 addresses
        assert_eq!(results.len(), 4, "Deep search should find all 4 addresses");

        let expected_addrs = vec![
            base_addr + offset_0,
            base_addr + offset_0 + 0x4,
            base_addr + offset_0 + 0x8,
            base_addr + offset_0 + 0xC,
        ];

        for (i, expected_addr) in expected_addrs.iter().enumerate() {
            assert!(
                results.iter().any(|p| p.addr == *expected_addr),
                "Should find address at position {} (0x{:X})", i, expected_addr
            );
        }

        println!("\nTest passed! Deep search found all combinations.");
    }

    /// Test with triple duplicates (three identical values)
    /// Memory: 0x1000(100), 0x1004(200), 0x1008(200), 0x100C(200), 0x1010(300)
    /// Query: [100, 200, 300] ordered, range=20
    ///
    /// Expected combinations:
    /// - [0x1000, 0x1004, 0x1010]
    /// - [0x1000, 0x1008, 0x1010]
    /// - [0x1000, 0x100C, 0x1010]
    /// All 5 addresses should be found
    #[test]
    fn test_deep_search_ordered_triple_duplicate() {
        println!("\n=== Deep search test: triple duplicate middle value ===\n");

        let mut mem = MockMemory::new();
        let base_addr = mem.malloc(0x8000000000, 64 * 1024).unwrap();

        println!("Allocated memory: 0x{:X}, size: 64KB", base_addr);

        // Write test pattern: 100, 200, 200, 200, 300
        let offset_0 = 0x2000u64;
        mem.mem_write_u32(base_addr + offset_0, 100).unwrap();
        mem.mem_write_u32(base_addr + offset_0 + 0x4, 200).unwrap();
        mem.mem_write_u32(base_addr + offset_0 + 0x8, 200).unwrap();
        mem.mem_write_u32(base_addr + offset_0 + 0xC, 200).unwrap();
        mem.mem_write_u32(base_addr + offset_0 + 0x10, 300).unwrap();

        println!("Memory layout:");
        println!("  0x{:X}: 100", base_addr + offset_0);
        println!("  0x{:X}: 200", base_addr + offset_0 + 0x4);
        println!("  0x{:X}: 200 (dup)", base_addr + offset_0 + 0x8);
        println!("  0x{:X}: 200 (dup)", base_addr + offset_0 + 0xC);
        println!("  0x{:X}: 300", base_addr + offset_0 + 0x10);

        let values = vec![
            SearchValue::fixed(100, ValueType::Dword),
            SearchValue::fixed(200, ValueType::Dword),
            SearchValue::fixed(300, ValueType::Dword),
        ];
        let query = SearchQuery::new(values, SearchMode::Ordered, 20);

        println!("\nQuery: [100, 200, 300] ordered, range=20 bytes");

        let search_start = base_addr;
        let search_size = 64 * 1024;
        let mut buffer = vec![0u8; search_size];
        let mut page_status = PageStatusBitmap::new(buffer.len(), search_start as usize);

        mem.mem_read_with_status(search_start, &mut buffer, &mut page_status).unwrap();

        println!("\nPerforming deep search...");

        let mut results = BPlusTreeSet::new(32);
        let mut matches_checked = 0usize;

        search_in_buffer_group_deep(
            &buffer,
            search_start,
            search_start,
            search_start + search_size as u64,
            4,
            &query,
            &page_status,
            &mut results,
            &mut matches_checked,
        );

        println!("\n=== Search results ===");
        println!("Checked {} positions", matches_checked);
        println!("Found {} addresses", results.len());

        for (i, pair) in results.iter().enumerate() {
            let offset = pair.addr - base_addr;
            println!("  [{}] 0x{:X} (offset: 0x{:X})", i + 1, pair.addr, offset);
        }

        // Should find all 5 addresses (3 combinations)
        assert_eq!(results.len(), 5, "Should find all 5 addresses from 3 combinations");

        let expected_addrs = vec![
            base_addr + offset_0,
            base_addr + offset_0 + 0x4,
            base_addr + offset_0 + 0x8,
            base_addr + offset_0 + 0xC,
            base_addr + offset_0 + 0x10,
        ];

        for (i, expected_addr) in expected_addrs.iter().enumerate() {
            assert!(
                results.iter().any(|p| p.addr == *expected_addr),
                "Should find address at position {} (0x{:X})", i, expected_addr
            );
        }

        println!("\nTest passed! Found 3 combinations with 5 unique addresses.");
    }

    /// Test with first value duplicated
    /// Memory: 0x1000(100), 0x1004(100), 0x1008(200), 0x100C(300)
    /// Query: [100, 200, 300] ordered, range=16
    ///
    /// Expected combinations:
    /// - [0x1000, 0x1008, 0x100C]
    /// - [0x1004, 0x1008, 0x100C]
    #[test]
    fn test_deep_search_ordered_duplicate_first_value() {
        println!("\n=== Deep search test: duplicate first value ===\n");

        let mut mem = MockMemory::new();
        let base_addr = mem.malloc(0x9000000000, 64 * 1024).unwrap();

        println!("Allocated memory: 0x{:X}, size: 64KB", base_addr);

        // Write pattern: 100, 100, 200, 300
        let offset_0 = 0x3000u64;
        mem.mem_write_u32(base_addr + offset_0, 100).unwrap();
        mem.mem_write_u32(base_addr + offset_0 + 0x4, 100).unwrap();
        mem.mem_write_u32(base_addr + offset_0 + 0x8, 200).unwrap();
        mem.mem_write_u32(base_addr + offset_0 + 0xC, 300).unwrap();

        println!("Memory layout:");
        println!("  0x{:X}: 100", base_addr + offset_0);
        println!("  0x{:X}: 100 (duplicate)", base_addr + offset_0 + 0x4);
        println!("  0x{:X}: 200", base_addr + offset_0 + 0x8);
        println!("  0x{:X}: 300", base_addr + offset_0 + 0xC);

        let values = vec![
            SearchValue::fixed(100, ValueType::Dword),
            SearchValue::fixed(200, ValueType::Dword),
            SearchValue::fixed(300, ValueType::Dword),
        ];
        let query = SearchQuery::new(values, SearchMode::Ordered, 16);

        println!("\nQuery: [100, 200, 300] ordered, range=16 bytes");

        let search_start = base_addr;
        let search_size = 64 * 1024;
        let mut buffer = vec![0u8; search_size];
        let mut page_status = PageStatusBitmap::new(buffer.len(), search_start as usize);

        mem.mem_read_with_status(search_start, &mut buffer, &mut page_status).unwrap();

        println!("\nPerforming deep search...");

        let mut results = BPlusTreeSet::new(32);
        let mut matches_checked = 0usize;

        search_in_buffer_group_deep(
            &buffer,
            search_start,
            search_start,
            search_start + search_size as u64,
            4,
            &query,
            &page_status,
            &mut results,
            &mut matches_checked,
        );

        println!("\n=== Search results ===");
        println!("Checked {} positions", matches_checked);
        println!("Found {} addresses", results.len());

        for (i, pair) in results.iter().enumerate() {
            let offset = pair.addr - base_addr;
            println!("  [{}] 0x{:X} (offset: 0x{:X})", i + 1, pair.addr, offset);
        }

        // Should find all 4 addresses (2 combinations)
        assert_eq!(results.len(), 4, "Should find all 4 addresses from 2 combinations");

        let expected_addrs = vec![
            base_addr + offset_0,
            base_addr + offset_0 + 0x4,
            base_addr + offset_0 + 0x8,
            base_addr + offset_0 + 0xC,
        ];

        for (i, expected_addr) in expected_addrs.iter().enumerate() {
            assert!(
                results.iter().any(|p| p.addr == *expected_addr),
                "Should find address at position {} (0x{:X})", i, expected_addr
            );
        }

        println!("\nTest passed! Found 2 combinations with 4 unique addresses.");
    }

    /// Test single value query with multiple matches
    /// Memory: 0x1000(300), 0x1004(300), 0x1008(300)
    /// Query: [300] ordered, range=4
    ///
    /// Each occurrence is a valid match by itself
    #[test]
    fn test_deep_search_single_value_multiple_matches() {
        println!("\n=== Deep search test: single value with multiple matches ===\n");

        let mut mem = MockMemory::new();
        let base_addr = mem.malloc(0xA000000000, 64 * 1024).unwrap();

        println!("Allocated memory: 0x{:X}, size: 64KB", base_addr);

        // Write pattern: 300, 300, 300
        let offset_0 = 0x4000u64;
        mem.mem_write_u32(base_addr + offset_0, 300).unwrap();
        mem.mem_write_u32(base_addr + offset_0 + 0x4, 300).unwrap();
        mem.mem_write_u32(base_addr + offset_0 + 0x8, 300).unwrap();

        println!("Memory layout:");
        println!("  0x{:X}: 300", base_addr + offset_0);
        println!("  0x{:X}: 300", base_addr + offset_0 + 0x4);
        println!("  0x{:X}: 300", base_addr + offset_0 + 0x8);

        let values = vec![
            SearchValue::fixed(300, ValueType::Dword),
        ];
        let query = SearchQuery::new(values, SearchMode::Ordered, 4);

        println!("\nQuery: [300] ordered, range=4 bytes");

        let search_start = base_addr;
        let search_size = 64 * 1024;
        let mut buffer = vec![0u8; search_size];
        let mut page_status = PageStatusBitmap::new(buffer.len(), search_start as usize);

        mem.mem_read_with_status(search_start, &mut buffer, &mut page_status).unwrap();

        println!("\nPerforming deep search...");

        let mut results = BPlusTreeSet::new(32);
        let mut matches_checked = 0usize;

        search_in_buffer_group_deep(
            &buffer,
            search_start,
            search_start,
            search_start + search_size as u64,
            4,
            &query,
            &page_status,
            &mut results,
            &mut matches_checked,
        );

        println!("\n=== Search results ===");
        println!("Checked {} positions", matches_checked);
        println!("Found {} addresses", results.len());

        for (i, pair) in results.iter().enumerate() {
            let offset = pair.addr - base_addr;
            println!("  [{}] 0x{:X} (offset: 0x{:X})", i + 1, pair.addr, offset);
        }

        // Should find all 3 addresses
        assert_eq!(results.len(), 3, "Should find all 3 addresses");

        let expected_addrs = vec![
            base_addr + offset_0,
            base_addr + offset_0 + 0x4,
            base_addr + offset_0 + 0x8,
        ];

        for (i, expected_addr) in expected_addrs.iter().enumerate() {
            assert!(
                results.iter().any(|p| p.addr == *expected_addr),
                "Should find address at position {} (0x{:X})", i, expected_addr
            );
        }

        println!("\nTest passed! Found all 3 single-value matches.");
    }

    /// Test with gaps between values (sparse pattern)
    /// Memory: 0x1000(100), 0x1010(200), 0x1014(200), 0x1020(300)
    /// Query: [100, 200, 300] ordered, range=36
    ///
    /// Expected combinations with gaps:
    /// - [0x1000, 0x1010, 0x1020]
    /// - [0x1000, 0x1014, 0x1020]
    #[test]
    fn test_deep_search_ordered_with_gaps() {
        println!("\n=== Deep search test: sparse pattern with gaps ===\n");

        let mut mem = MockMemory::new();
        let base_addr = mem.malloc(0xB000000000, 64 * 1024).unwrap();

        println!("Allocated memory: 0x{:X}, size: 64KB", base_addr);

        // Write sparse pattern: 100 @ 0, 200 @ 16, 200 @ 20, 300 @ 32
        let offset_0 = 0x5000u64;
        mem.mem_write_u32(base_addr + offset_0, 100).unwrap();
        mem.mem_write_u32(base_addr + offset_0 + 0x10, 200).unwrap();
        mem.mem_write_u32(base_addr + offset_0 + 0x14, 200).unwrap();
        mem.mem_write_u32(base_addr + offset_0 + 0x20, 300).unwrap();

        println!("Memory layout (sparse):");
        println!("  0x{:X}: 100", base_addr + offset_0);
        println!("  0x{:X}: 200 (+16 bytes gap)", base_addr + offset_0 + 0x10);
        println!("  0x{:X}: 200 (adjacent)", base_addr + offset_0 + 0x14);
        println!("  0x{:X}: 300 (+12 bytes gap)", base_addr + offset_0 + 0x20);

        let values = vec![
            SearchValue::fixed(100, ValueType::Dword),
            SearchValue::fixed(200, ValueType::Dword),
            SearchValue::fixed(300, ValueType::Dword),
        ];
        let query = SearchQuery::new(values, SearchMode::Ordered, 36);

        println!("\nQuery: [100, 200, 300] ordered, range=36 bytes");

        let search_start = base_addr;
        let search_size = 64 * 1024;
        let mut buffer = vec![0u8; search_size];
        let mut page_status = PageStatusBitmap::new(buffer.len(), search_start as usize);

        mem.mem_read_with_status(search_start, &mut buffer, &mut page_status).unwrap();

        println!("\nPerforming deep search...");

        let mut results = BPlusTreeSet::new(32);
        let mut matches_checked = 0usize;

        search_in_buffer_group_deep(
            &buffer,
            search_start,
            search_start,
            search_start + search_size as u64,
            4,
            &query,
            &page_status,
            &mut results,
            &mut matches_checked,
        );

        println!("\n=== Search results ===");
        println!("Checked {} positions", matches_checked);
        println!("Found {} addresses", results.len());

        for (i, pair) in results.iter().enumerate() {
            let offset = pair.addr - base_addr;
            println!("  [{}] 0x{:X} (offset: 0x{:X})", i + 1, pair.addr, offset);
        }

        // Should find all 4 addresses (2 combinations with gaps)
        assert_eq!(results.len(), 4, "Should find all 4 addresses from 2 sparse combinations");

        let expected_addrs = vec![
            base_addr + offset_0,
            base_addr + offset_0 + 0x10,
            base_addr + offset_0 + 0x14,
            base_addr + offset_0 + 0x20,
        ];

        for (i, expected_addr) in expected_addrs.iter().enumerate() {
            assert!(
                results.iter().any(|p| p.addr == *expected_addr),
                "Should find address at position {} (0x{:X})", i, expected_addr
            );
        }

        println!("\nTest passed! Deep search handles sparse patterns correctly.");
    }

    /// Test comparison with standard search behavior
    /// This test demonstrates the difference between standard and deep search
    #[test]
    fn test_deep_vs_standard_search_comparison() {
        println!("\n=== Comparison: Deep search vs Standard search ===\n");

        let mut mem = MockMemory::new();
        let base_addr = mem.malloc(0xC000000000, 64 * 1024).unwrap();

        println!("Allocated memory: 0x{:X}, size: 64KB", base_addr);

        // Same pattern as the original bug report
        let offset_0 = 0x1000u64;
        mem.mem_write_u32(base_addr + offset_0, 100).unwrap();
        mem.mem_write_u32(base_addr + offset_0 + 0x4, 200).unwrap();
        mem.mem_write_u32(base_addr + offset_0 + 0x8, 300).unwrap();
        mem.mem_write_u32(base_addr + offset_0 + 0xC, 300).unwrap();

        println!("Memory: [100, 200, 300, 300]");
        println!("Query: [100, 200, 300] ordered, range=16\n");

        let values = vec![
            SearchValue::fixed(100, ValueType::Dword),
            SearchValue::fixed(200, ValueType::Dword),
            SearchValue::fixed(300, ValueType::Dword),
        ];
        let query = SearchQuery::new(values, SearchMode::Ordered, 16);

        let search_start = base_addr;
        let search_size = 64 * 1024;
        let mut buffer = vec![0u8; search_size];
        let mut page_status = PageStatusBitmap::new(buffer.len(), search_start as usize);

        mem.mem_read_with_status(search_start, &mut buffer, &mut page_status).unwrap();

        // Deep search
        let mut deep_results = BPlusTreeSet::new(32);
        let mut matches_checked = 0usize;

        search_in_buffer_group_deep(
            &buffer,
            search_start,
            search_start,
            search_start + search_size as u64,
            4,
            &query,
            &page_status,
            &mut deep_results,
            &mut matches_checked,
        );

        println!("Deep search results: {} addresses", deep_results.len());
        for pair in deep_results.iter() {
            println!("  - 0x{:X}", pair.addr);
        }

        println!("\nExpected behavior:");
        println!("  Standard search would find: 3 addresses (first match only)");
        println!("  Deep search finds: 4 addresses (all combinations)");

        // Verify deep search found all 4
        assert_eq!(deep_results.len(), 4);
        assert!(deep_results.iter().any(|p| p.addr == base_addr + offset_0));
        assert!(deep_results.iter().any(|p| p.addr == base_addr + offset_0 + 0x4));
        assert!(deep_results.iter().any(|p| p.addr == base_addr + offset_0 + 0x8));
        assert!(deep_results.iter().any(|p| p.addr == base_addr + offset_0 + 0xC));

        println!("\n✓ Deep search correctly finds ALL participating addresses!");
    }
}
