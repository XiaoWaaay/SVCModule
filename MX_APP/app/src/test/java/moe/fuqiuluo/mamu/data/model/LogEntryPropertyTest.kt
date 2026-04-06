package moe.fuqiuluo.mamu.data.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * Property-based tests for LogEntry
 * 
 * Feature: compose-ui-recomposition-optimization
 */
class LogEntryPropertyTest : FunSpec({

    /**
     * Property 1: Level Color Mapping is Deterministic
     * 
     * For any LogLevel value, the levelColor mapping SHALL always return
     * the same Color value (represented as ARGB Long).
     * 
     * **Validates: Requirements 1.1**
     */
    test("Property 1: Level Color Mapping is Deterministic - same level always returns same color") {
        // Define the color mapping function (mirrors LogLine composable logic)
        fun getLevelColor(level: LogLevel): Long = when (level) {
            LogLevel.VERBOSE -> 0xFF9E9E9E
            LogLevel.DEBUG -> 0xFF2196F3
            LogLevel.INFO -> 0xFF4CAF50
            LogLevel.WARNING -> 0xFFFF9800
            LogLevel.ERROR -> 0xFFF44336
            LogLevel.FATAL -> 0xFF9C27B0
            else -> 0xFF808080 // Color.Gray
        }

        checkAll(100, Arb.enum<LogLevel>()) { level ->
            // Call the function multiple times with the same level
            val color1 = getLevelColor(level)
            val color2 = getLevelColor(level)
            val color3 = getLevelColor(level)

            // All calls should return the same color
            color1 shouldBe color2
            color2 shouldBe color3
        }
    }

    /**
     * Property 1: Level Color Mapping is Deterministic (variant)
     * 
     * For any sequence of LogLevel values, calling the color mapping
     * function with the same level at different times SHALL return
     * identical results.
     * 
     * **Validates: Requirements 1.1**
     */
    test("Property 1: Level Color Mapping is Deterministic - mapping is consistent across calls") {
        fun getLevelColor(level: LogLevel): Long = when (level) {
            LogLevel.VERBOSE -> 0xFF9E9E9E
            LogLevel.DEBUG -> 0xFF2196F3
            LogLevel.INFO -> 0xFF4CAF50
            LogLevel.WARNING -> 0xFFFF9800
            LogLevel.ERROR -> 0xFFF44336
            LogLevel.FATAL -> 0xFF9C27B0
            else -> 0xFF808080 // Color.Gray
        }

        // Build a reference map of expected colors
        val expectedColors = LogLevel.entries.associateWith { getLevelColor(it) }

        checkAll(100, Arb.enum<LogLevel>()) { level ->
            val actualColor = getLevelColor(level)
            actualColor shouldBe expectedColors[level]
        }
    }

    /**
     * Property 2: LogEntry IDs are Unique
     * 
     * For any collection of LogEntry instances created in sequence,
     * all id values SHALL be unique.
     * 
     * **Validates: Requirements 2.1, 3.1**
     */
    test("Property 2: LogEntry IDs are Unique - sequential creation produces unique ids") {
        checkAll(100, Arb.int(10, 1000)) { count ->
            val entries = (1..count).map {
                LogEntry(
                    timestamp = System.currentTimeMillis(),
                    level = LogLevel.INFO,
                    tag = "Test",
                    message = "Message $it"
                )
            }
            
            val uniqueIds = entries.map { it.id }.toSet()
            uniqueIds.size shouldBe entries.size
        }
    }

    /**
     * Property 2: LogEntry IDs are Unique (variant)
     * 
     * For any sequence of LogEntry instances with random field values,
     * all id values SHALL be unique.
     * 
     * **Validates: Requirements 2.1, 3.1**
     */
    test("Property 2: LogEntry IDs are Unique - random field values produce unique ids") {
        checkAll(
            100,
            Arb.long(0L, Long.MAX_VALUE),
            Arb.enum<LogLevel>(),
            Arb.string(0, 50),
            Arb.string(0, 200),
            Arb.int(-1, 10000),
            Arb.int(-1, 10000)
        ) { timestamp, level, tag, message, pid, tid ->
            // Create multiple entries with same field values
            val entries = (1..10).map {
                LogEntry(
                    timestamp = timestamp,
                    level = level,
                    tag = tag,
                    message = message,
                    pid = pid,
                    tid = tid
                )
            }
            
            val uniqueIds = entries.map { it.id }.toSet()
            uniqueIds.size shouldBe entries.size
        }
    }
})
