package moe.fuqiuluo.mamu.pp

import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.ext.divideToSimpleMemoryRangeParallel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Execution exception
 */
class ExecutionException(message: String) : Exception(message)

/**
 * Execution engine
 * @param baseAddress Base address (starting address)
 */
class PtrPathExecutor(
    private val baseAddress: Long
) {
    private var currentAddress = baseAddress
    private val variables = mutableMapOf<String, Long>()
    private val steps = mutableListOf<ExecutionStep>()
    private var stepIndex = 0
    private var shouldStop = false

    /**
     * Execute a list of expression nodes
     * @param nodes AST节点列表
     * @return Execution result
     */
    suspend fun execute(nodes: List<ExprNode>): ExecutionResult {
        try {
            // Record the initial state
            addStep("Start execution", baseAddress, currentAddress, "base=${"0x%X".format(baseAddress)}")

            // 顺序执行每个节点
            for (node in nodes) {
                if (shouldStop) {
                    break
                }
                executeNode(node)
            }

            // If execution succeeds, read memory at the final address
            val memoryValues = if (currentAddress != 0L && !shouldStop) {
                readMemoryValues(currentAddress)
            } else {
                null
            }

            val bytes = WuwaDriver.readMemory(currentAddress, 8)
            val regions = WuwaDriver.queryMemRegionsWithRetry().divideToSimpleMemoryRangeParallel()
                .sortedBy { it.start }

            return ExecutionResult(
                success = true,
                finalAddress = currentAddress,
                steps = steps,
                errorMessage = null,
                memoryValues = memoryValues,
                bytes = bytes,
                regions = regions
            )
        } catch (e: Exception) {
            // Execution failed; return the partial result
            return ExecutionResult(
                success = false,
                finalAddress = currentAddress,
                steps = steps,
                errorMessage = e.message ?: "Unknown error",
                memoryValues = null,
                regions = emptyList(),
                bytes = null
            )
        }
    }

    /**
     * Execute a single node
     */
    private fun executeNode(node: ExprNode) {
        val before = currentAddress

        when (node) {
            is ExprNode.Offset -> {
                currentAddress += node.value
                val sign = if (node.value >= 0) "+" else ""
                addStep(
                    "Offset",
                    before,
                    currentAddress,
                    "$sign${node.value} (${sign}0x%X)".format(Math.abs(node.value))
                )
            }

            is ExprNode.Deref -> {
                // A node may require multiple chained dereferences
                repeat(node.count) { index ->
                    val addrBefore = currentAddress
                    val bytes = WuwaDriver.readMemory(currentAddress, node.type.byteSize)
                        ?: throw ExecutionException("Unable to read address 0x%X".format(currentAddress))

                    currentAddress = bytesToAddress(bytes, node.type)

                    val desc = if (node.count > 1) {
                        "Dereference [${index + 1}/${node.count}]"
                    } else {
                        "Dereference"
                    }

                    addStep(
                        desc,
                        addrBefore,
                        currentAddress,
                        "*${node.type.code}"
                    )
                }
            }

            is ExprNode.VarDef -> {
                val varBefore = currentAddress

                // Execute child expressions for the variable definition
                for (subNode in node.expr) {
                    if (shouldStop) break
                    executeNode(subNode)
                }

                // Store the current address in the variable
                variables[node.name] = currentAddress

                addStep(
                    "Variable definition",
                    varBefore,
                    currentAddress,
                    if (node.name == "_") {
                        "_: = 0x%X".format(currentAddress)
                    } else {
                        "${node.name}: = 0x%X".format(currentAddress)
                    }
                )
            }

            is ExprNode.VarRef -> {
                // Special tokens处理：如果是下划线Variable reference
                if (node.name == "_") {
                    // From变量表中获取，如果不存在则使用current
                    val varValue = variables["_"] ?: currentAddress
                    if (variables.containsKey("_")) {
                        currentAddress = varValue
                        addStep(
                            "Variable reference",
                            before,
                            currentAddress,
                            "\$_ = 0x%X".format(currentAddress)
                        )
                    } else {
                        // If _ is undefined, use current and keep the address unchanged
                        addStep(
                            "Current address",
                            before,
                            currentAddress,
                            "_ (current)"
                        )
                    }
                } else {
                    val varValue = variables[node.name]
                        ?: throw ExecutionException("Undefined variable: ${node.name}")
                    currentAddress = varValue
                    addStep(
                        "Variable reference",
                        before,
                        currentAddress,
                        "\$${node.name} = 0x%X".format(currentAddress)
                    )
                }
            }

            is ExprNode.Conditional -> {
                val condResult = evaluateCondition(node.condition)
                val branch = if (condResult) node.trueBranch else node.falseBranch
                val branchName = if (condResult) "true branch" else "false branch"

                addStep(
                    "Condition evaluation",
                    before,
                    currentAddress,
                    "condition=${condResult}, execute $branchName"
                )

                // Execute the selected branch
                for (subNode in branch) {
                    if (shouldStop) break
                    executeNode(subNode)
                }
            }

            is ExprNode.Builtin.Skip -> {
                addStep(
                    "Skip",
                    before,
                    currentAddress,
                    "@skip (keep current address)"
                )
            }

            is ExprNode.Builtin.Null -> {
                currentAddress = 0L
                addStep(
                    "Return null",
                    before,
                    currentAddress,
                    "@null"
                )
            }

            is ExprNode.Builtin.Stop -> {
                shouldStop = true
                addStep(
                    "Stop execution",
                    before,
                    currentAddress,
                    "@stop"
                )
            }

            is ExprNode.Builtin.ArrayAccess -> {
                val index = evaluateOperand(node.indexExpr)
                val elemSize = node.elemSize ?: 8

                currentAddress += index * elemSize

                addStep(
                    "Array access",
                    before,
                    currentAddress,
                    "@[%d] (elem_size=%d, offset=+0x%X)".format(index, elemSize, index * elemSize)
                )
            }
        }
    }

    /**
     * Evaluate a conditional expression
     */
    private fun evaluateCondition(condition: Condition): Boolean {
        return when (condition) {
            is Condition.Compare -> {
                val left = evaluateOperand(condition.left)
                val right = evaluateOperand(condition.right)
                when (condition.op) {
                    CompareOp.EQ -> left == right
                    CompareOp.NE -> left != right
                    CompareOp.GT -> left > right
                    CompareOp.LT -> left < right
                    CompareOp.GE -> left >= right
                    CompareOp.LE -> left <= right
                }
            }

            is Condition.Bitwise -> {
                val left = evaluateOperand(condition.left)
                val right = evaluateOperand(condition.right)
                val result = when (condition.op) {
                    BitwiseOp.AND -> left and right
                    BitwiseOp.OR -> left or right
                    BitwiseOp.XOR -> left xor right
                }
                // A non-zero bitwise result is treated as true
                result != 0L
            }

            is Condition.Logical -> {
                when (condition.op) {
                    LogicalOp.AND -> {
                        evaluateCondition(condition.left) && evaluateCondition(condition.right)
                    }

                    LogicalOp.OR -> {
                        evaluateCondition(condition.left) || evaluateCondition(condition.right)
                    }
                }
            }

            is Condition.Not -> {
                !evaluateCondition(condition.condition)
            }
        }
    }

    /**
     * Evaluate an operand
     */
    private fun evaluateOperand(operand: Operand): Long {
        return when (operand) {
            is Operand.Current -> currentAddress

            is Operand.Variable -> {
                variables[operand.name]
                    ?: throw ExecutionException("Undefined variable: ${operand.name}")
            }

            is Operand.Constant -> operand.value
        }
    }

    /**
     * Convert a byte array into an address value
     * @param bytes Byte array
     * @param type Data type
     * @return Address value
     */
    private fun bytesToAddress(bytes: ByteArray, type: DataType): Long {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        return when (type) {
            DataType.U8 -> buffer.get().toLong() and 0xFF
            DataType.U16 -> buffer.short.toLong() and 0xFFFF
            DataType.U32 -> buffer.int.toLong() and 0xFFFFFFFFL
            DataType.U64 -> buffer.long

            DataType.I8 -> buffer.get().toLong()
            DataType.I16 -> buffer.short.toLong()
            DataType.I32 -> buffer.int.toLong()
            DataType.I64 -> buffer.long
        }
    }

    /**
     * Read memory at the given address for every supported data type
     * @param address Target address
     * @return mapping from type code to value string
     */
    private fun readMemoryValues(address: Long): Map<String, String> {
        val results = mutableMapOf<String, String>()

        // Read every supported data type
        val types = DataType.entries

        for (type in types) {
            try {
                val bytes = WuwaDriver.readMemory(address, type.byteSize)
                if (bytes != null) {
                    val value = bytesToAddress(bytes, type)

                    // Format the output
                    val formatted = when (type) {
                        DataType.U8, DataType.U16, DataType.U32, DataType.U64 -> {
                            // Unsigned: decimal (hexadecimal)
                            "%d (0x%X)".format(value, value)
                        }

                        DataType.I8, DataType.I16, DataType.I32, DataType.I64 -> {
                            // Signed: decimal (hexadecimal)
                            "%d (0x%X)".format(value, value)
                        }
                    }

                    results[type.code] = formatted
                } else {
                    results[type.code] = "Read failed"
                }
            } catch (e: Exception) {
                results[type.code] = "Error: ${e.message}"
            }
        }

        return results
    }

    /**
     * Append an execution step record
     */
    private fun addStep(
        description: String,
        addressBefore: Long,
        addressAfter: Long,
        operation: String
    ) {
        steps.add(
            ExecutionStep(
                stepIndex = stepIndex++,
                description = description,
                addressBefore = addressBefore,
                addressAfter = addressAfter,
                operation = operation,
                variables = variables.toMap()  // Copy the current variable state
            )
        )
    }
}
