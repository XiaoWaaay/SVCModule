package moe.fuqiuluo.mamu.floating.controller

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import moe.fuqiuluo.mamu.floating.data.model.MemoryBackupRecord

/**
 * Property-based tests for Batch Memory Write Optimization
 * 
 * Feature: batch-memory-write-optimization
 * 
 * These tests validate the pure logic functions extracted from SearchController's
 * restoreSelectedItems() batch write implementation.
 */
class BatchMemoryWritePropertyTest : FunSpec({

    /**
     * Data class representing a write operation for batch processing.
     * Mirrors the WriteOperation class in SearchController.
     */
    data class WriteOperation(
        val address: Long,
        val dataBytes: ByteArray,
        val originalValue: String,
        val originalType: DisplayValueType
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as WriteOperation
            return address == other.address
        }

        override fun hashCode(): Int = address.hashCode()
    }

    /**
     * Simulates the batch preparation logic from restoreSelectedItems().
     * Given a list of addresses and a backup lookup function, returns:
     * - List of valid WriteOperations (addresses with backups)
     * - Count of addresses without backups
     */
    fun prepareBatchOperations(
        addresses: List<Long>,
        getBackup: (Long) -> MemoryBackupRecord?
    ): Pair<List<WriteOperation>, Int> {
        val writeOperations = mutableListOf<WriteOperation>()
        var noBackupCount = 0

        addresses.forEach { address ->
            val backup = getBackup(address)
            if (backup == null) {
                noBackupCount++
            } else {
                // Simulate successful byte conversion (in real code this could throw)
                val dataBytes = backup.originalValue.toByteArray()
                writeOperations.add(
                    WriteOperation(
                        address = address,
                        dataBytes = dataBytes,
                        originalValue = backup.originalValue,
                        originalType = backup.originalType
                    )
                )
            }
        }

        return Pair(writeOperations, noBackupCount)
    }

    /**
     * Property 1: Batch Preparation Correctness
     * 
     * *For any* set of selected items where some have backup records and some don't,
     * the batch write operation SHALL include exactly those addresses that have valid
     * backup records, and no others.
     * 
     * **Validates: Requirements 1.1, 1.4**
     */
    test("Property 1: Batch Preparation Correctness - only addresses with backups are included") {
        // Generator for valid DisplayValueType (excluding disabled types)
        val validValueTypes = DisplayValueType.entries.filter { !it.isDisabled }
        val valueTypeArb = Arb.enum<DisplayValueType>().map { type ->
            if (type.isDisabled) DisplayValueType.DWORD else type
        }

        checkAll(
            100,
            Arb.list(Arb.long(1L, Long.MAX_VALUE), 1..50),  // List of addresses
            Arb.list(Arb.boolean(), 1..50)  // Which addresses have backups
        ) { addresses, hasBackupFlags ->
            // Ensure lists are same size by taking minimum
            val size = minOf(addresses.size, hasBackupFlags.size)
            val addressList = addresses.take(size).distinct()  // Ensure unique addresses
            val backupFlags = hasBackupFlags.take(addressList.size)

            // Create backup map based on flags
            val backupMap = mutableMapOf<Long, MemoryBackupRecord>()
            val expectedAddressesWithBackup = mutableListOf<Long>()
            val expectedNoBackupCount = backupFlags.count { !it }

            addressList.forEachIndexed { index, address ->
                if (backupFlags.getOrElse(index) { false }) {
                    backupMap[address] = MemoryBackupRecord(
                        address = address,
                        originalValue = "value_$address",
                        originalType = DisplayValueType.DWORD,
                        firstModifiedTime = System.currentTimeMillis()
                    )
                    expectedAddressesWithBackup.add(address)
                }
            }

            // Execute batch preparation
            val (writeOperations, noBackupCount) = prepareBatchOperations(
                addresses = addressList,
                getBackup = { addr -> backupMap[addr] }
            )

            // Verify: only addresses with backups are included
            val actualAddresses = writeOperations.map { it.address }
            actualAddresses shouldContainExactlyInAnyOrder expectedAddressesWithBackup

            // Verify: noBackupCount matches expected
            noBackupCount shouldBe expectedNoBackupCount

            // Verify: total equals input size
            (writeOperations.size + noBackupCount) shouldBe addressList.size
        }
    }

    /**
     * Property 1 Variant: Addresses without backups are never included in batch
     * 
     * *For any* address that has no backup record, it SHALL NOT appear in the
     * batch write operations list.
     * 
     * **Validates: Requirements 1.4**
     */
    test("Property 1 Variant: Addresses without backups are excluded from batch") {
        checkAll(
            100,
            Arb.list(Arb.long(1L, Long.MAX_VALUE), 1..30)
        ) { addresses ->
            val uniqueAddresses = addresses.distinct()
            
            // No backups exist - all addresses should be excluded
            val (writeOperations, noBackupCount) = prepareBatchOperations(
                addresses = uniqueAddresses,
                getBackup = { null }  // No backups
            )

            writeOperations.size shouldBe 0
            noBackupCount shouldBe uniqueAddresses.size
        }
    }

    /**
     * Property 1 Variant: All addresses with backups are included
     * 
     * *For any* set of addresses where all have backup records,
     * all addresses SHALL appear in the batch write operations.
     * 
     * **Validates: Requirements 1.1**
     */
    test("Property 1 Variant: All addresses with backups are included in batch") {
        checkAll(
            100,
            Arb.list(Arb.long(1L, Long.MAX_VALUE), 1..30)
        ) { addresses ->
            val uniqueAddresses = addresses.distinct()
            
            // All addresses have backups
            val backupMap = uniqueAddresses.associateWith { addr ->
                MemoryBackupRecord(
                    address = addr,
                    originalValue = "value_$addr",
                    originalType = DisplayValueType.DWORD,
                    firstModifiedTime = System.currentTimeMillis()
                )
            }

            val (writeOperations, noBackupCount) = prepareBatchOperations(
                addresses = uniqueAddresses,
                getBackup = { addr -> backupMap[addr] }
            )

            writeOperations.size shouldBe uniqueAddresses.size
            noBackupCount shouldBe 0
            writeOperations.map { it.address } shouldContainExactlyInAnyOrder uniqueAddresses
        }
    }

    /**
     * Simulates the result processing logic from restoreSelectedItems().
     * Given batch write results, returns counts of success, failure, and no-backup.
     */
    data class RestoreResultCounts(
        val successCount: Int,
        val failureCount: Int,
        val noBackupCount: Int,
        val totalSelected: Int
    )

    fun processRestoreResults(
        writeResults: BooleanArray,
        noBackupCount: Int,
        totalSelected: Int
    ): RestoreResultCounts {
        var successCount = 0
        var failureCount = 0

        writeResults.forEach { success ->
            if (success) {
                successCount++
            } else {
                failureCount++
            }
        }

        return RestoreResultCounts(
            successCount = successCount,
            failureCount = failureCount,
            noBackupCount = noBackupCount,
            totalSelected = totalSelected
        )
    }

    /**
     * Property 5: Count Accuracy
     * 
     * *For any* batch restore operation, the sum of successCount, failureCount,
     * and noBackupCount SHALL equal the total number of selected items.
     * 
     * **Validates: Requirements 3.3**
     */
    test("Property 5: Count Accuracy - counts sum to total selected items") {
        checkAll(
            100,
            Arb.int(0, 100),  // Number of items with backups
            Arb.int(0, 50),   // Number of items without backups
            Arb.list(Arb.boolean(), 0..100)  // Write results (success/failure)
        ) { itemsWithBackup, itemsWithoutBackup, writeResultsList ->
            // Ensure write results match items with backup
            val actualItemsWithBackup = itemsWithBackup.coerceAtMost(100)
            val writeResults = if (actualItemsWithBackup == 0) {
                booleanArrayOf()
            } else {
                // Generate write results for items with backups
                val results = BooleanArray(actualItemsWithBackup)
                writeResultsList.take(actualItemsWithBackup).forEachIndexed { index, success ->
                    results[index] = success
                }
                // Fill remaining with random values if list was shorter
                for (i in writeResultsList.size until actualItemsWithBackup) {
                    results[i] = i % 2 == 0  // Deterministic fill
                }
                results
            }

            val totalSelected = actualItemsWithBackup + itemsWithoutBackup

            val counts = processRestoreResults(
                writeResults = writeResults,
                noBackupCount = itemsWithoutBackup,
                totalSelected = totalSelected
            )

            // Verify: sum of all counts equals total selected
            (counts.successCount + counts.failureCount + counts.noBackupCount) shouldBe totalSelected

            // Verify: success + failure equals items with backup (write results size)
            (counts.successCount + counts.failureCount) shouldBe writeResults.size
        }
    }

    /**
     * Property 5 Variant: Success count matches true values in results
     * 
     * *For any* batch write result array, the successCount SHALL equal
     * the number of true values in the results array.
     * 
     * **Validates: Requirements 3.3**
     */
    test("Property 5 Variant: Success count matches true values in results") {
        checkAll(
            100,
            Arb.list(Arb.boolean(), 0..100)
        ) { resultsList ->
            val writeResults = resultsList.toBooleanArray()
            val expectedSuccessCount = writeResults.count { it }
            val expectedFailureCount = writeResults.count { !it }

            val counts = processRestoreResults(
                writeResults = writeResults,
                noBackupCount = 0,
                totalSelected = writeResults.size
            )

            counts.successCount shouldBe expectedSuccessCount
            counts.failureCount shouldBe expectedFailureCount
        }
    }

    /**
     * Property 5 Variant: All success scenario
     * 
     * *For any* batch where all writes succeed, successCount SHALL equal
     * the number of items with backups, and failureCount SHALL be zero.
     * 
     * **Validates: Requirements 3.3**
     */
    test("Property 5 Variant: All success scenario has zero failures") {
        checkAll(
            100,
            Arb.int(1, 100),  // Number of successful writes
            Arb.int(0, 50)    // Number of items without backups
        ) { successfulWrites, noBackupItems ->
            val writeResults = BooleanArray(successfulWrites) { true }

            val counts = processRestoreResults(
                writeResults = writeResults,
                noBackupCount = noBackupItems,
                totalSelected = successfulWrites + noBackupItems
            )

            counts.successCount shouldBe successfulWrites
            counts.failureCount shouldBe 0
            counts.noBackupCount shouldBe noBackupItems
            (counts.successCount + counts.failureCount + counts.noBackupCount) shouldBe counts.totalSelected
        }
    }

    /**
     * Property 5 Variant: All failure scenario
     * 
     * *For any* batch where all writes fail, failureCount SHALL equal
     * the number of items with backups, and successCount SHALL be zero.
     * 
     * **Validates: Requirements 3.3**
     */
    test("Property 5 Variant: All failure scenario has zero successes") {
        checkAll(
            100,
            Arb.int(1, 100),  // Number of failed writes
            Arb.int(0, 50)    // Number of items without backups
        ) { failedWrites, noBackupItems ->
            val writeResults = BooleanArray(failedWrites) { false }

            val counts = processRestoreResults(
                writeResults = writeResults,
                noBackupCount = noBackupItems,
                totalSelected = failedWrites + noBackupItems
            )

            counts.successCount shouldBe 0
            counts.failureCount shouldBe failedWrites
            counts.noBackupCount shouldBe noBackupItems
            (counts.successCount + counts.failureCount + counts.noBackupCount) shouldBe counts.totalSelected
        }
    }
})
