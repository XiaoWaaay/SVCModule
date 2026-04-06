package moe.fuqiuluo.mamu.pp

import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry

/**
 * Data type
 */
enum class DataType(val byteSize: Int, val code: String) {
    U8(1, "u8"),
    U16(2, "u16"),
    U32(4, "u32"),
    U64(8, "u64"),
    I8(1, "i8"),
    I16(2, "i16"),
    I32(4, "i32"),
    I64(8, "i64");

    companion object {
        fun fromCode(code: String): DataType? {
            return entries.find { it.code == code }
        }
    }
}

/**
 * Base AST node type
 */
sealed class ExprNode {
    /**
     * Offset operation：4, +8, -0x10
     */
    data class Offset(val value: Long) : ExprNode()

    /**
     * Dereference operation：*u64, **u32
     * @param type Data type
     * @param count 连续Dereference次数（**u64则count=2）
     */
    data class Deref(val type: DataType, val count: Int = 1) : ExprNode()

    /**
     * Variable definition：name:expr
     * @param name 变量名
     * @param expr 子表达式列表
     */
    data class VarDef(val name: String, val expr: List<ExprNode>) : ExprNode()

    /**
     * Variable reference：$name
     * @param name 变量名
     */
    data class VarRef(val name: String) : ExprNode()

    /**
     * Conditional expression：cond? true : false
     */
    data class Conditional(
        val condition: Condition,
        val trueBranch: List<ExprNode>,
        val falseBranch: List<ExprNode>
    ) : ExprNode()

    /**
     * Built-in operator
     */
    sealed class Builtin : ExprNode() {
        /**
         * @skip - Skip and keep the current address unchanged
         */
        object Skip : Builtin()

        /**
         * @null - Return the null address
         */
        object Null : Builtin()

        /**
         * @stop - Stop execution
         */
        object Stop : Builtin()

        /**
         * @[index] 或 @[index,elemSize] - Array access
         * @param indexExpr Index expression (constant or variable reference)
         * @param elemSize Element size in bytes; defaults to 8 when null
         */
        data class ArrayAccess(
            val indexExpr: Operand,
            val elemSize: Int? = null
        ) : Builtin()
    }
}

/**
 * Conditional expression
 */
sealed class Condition {
    /**
     * Comparison operation：==, !=, >, <, >=, <=
     */
    data class Compare(
        val left: Operand,
        val op: CompareOp,
        val right: Operand
    ) : Condition()

    /**
     * Bitwise operation：&, |, ^
     */
    data class Bitwise(
        val left: Operand,
        val op: BitwiseOp,
        val right: Operand
    ) : Condition()

    /**
     * Logical operation：&&, ||
     */
    data class Logical(
        val left: Condition,
        val op: LogicalOp,
        val right: Condition
    ) : Condition()

    /**
     * Logical NOT：!condition
     */
    data class Not(val condition: Condition) : Condition()
}

/**
 * Comparison operator
 */
enum class CompareOp(val symbol: String) {
    EQ("=="),
    NE("!="),
    GT(">"),
    LT("<"),
    GE(">="),
    LE("<=")
}

/**
 * Bitwise operator
 */
enum class BitwiseOp(val symbol: String) {
    AND("&"),
    OR("|"),
    XOR("^")
}

/**
 * Logical operator
 */
enum class LogicalOp(val symbol: String) {
    AND("&&"),
    OR("||")
}

/**
 * Conditional operand
 */
sealed class Operand {
    /**
     * Current address：_
     */
    object Current : Operand()

    /**
     * Variable reference：$name
     */
    data class Variable(val name: String) : Operand()

    /**
     * Constant：0x100
     */
    data class Constant(val value: Long) : Operand()
}

/**
 * Execution step record
 */
data class ExecutionStep(
    val stepIndex: Int,
    val description: String,
    val addressBefore: Long,
    val addressAfter: Long,
    val operation: String,
    val variables: Map<String, Long> = emptyMap()
)

/**
 * Execution result
 */
data class ExecutionResult(
    val success: Boolean,
    val finalAddress: Long,
    val steps: List<ExecutionStep>,
    val bytes: ByteArray? = null,
    val regions: List<DisplayMemRegionEntry>,
    val errorMessage: String? = null,
    val memoryValues: Map<String, String>? = null  // type code -> value string
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ExecutionResult

        if (success != other.success) return false
        if (finalAddress != other.finalAddress) return false
        if (steps != other.steps) return false
        if (!bytes.contentEquals(other.bytes)) return false
        if (regions != other.regions) return false
        if (errorMessage != other.errorMessage) return false
        if (memoryValues != other.memoryValues) return false

        return true
    }

    override fun hashCode(): Int {
        var result = success.hashCode()
        result = 31 * result + finalAddress.hashCode()
        result = 31 * result + steps.hashCode()
        result = 31 * result + (bytes?.contentHashCode() ?: 0)
        result = 31 * result + regions.hashCode()
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        result = 31 * result + (memoryValues?.hashCode() ?: 0)
        return result
    }
}
