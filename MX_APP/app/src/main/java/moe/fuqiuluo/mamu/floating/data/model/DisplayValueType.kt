package moe.fuqiuluo.mamu.floating.data.model

import android.graphics.Color
import moe.fuqiuluo.mamu.R

/**
 * GameGuardian compatible value types for memory search
 */
enum class DisplayValueType(
    val code: String,
    val displayName: String,
    val rangeDescription: String,
    val iconRes: Int,
    val textColor: Int,
    val nativeId: Int,
    val memorySize: Long,
    val isDisabled: Boolean = false
) {
    AUTO(
        code = "A",
        displayName = "Auto",
        rangeDescription = "Enter a value from -1.8e+308 to 1.8e+308",
        iconRes = R.drawable.type_auto_24px,
        textColor = Color.WHITE,
        nativeId = 6,
        memorySize = 4L,
        isDisabled = true
    ),
    DWORD(
        code = "D",
        displayName = "Dword",
        rangeDescription = "Enter a value from -2,147,483,648 to 4,294,967,295",
        iconRes = R.drawable.type_integer_24px,
        textColor = 0xFF9FF0F7.toInt(),
        nativeId = 2,
        memorySize = 4,
    ),
    FLOAT(
        code = "F",
        displayName = "Float",
        rangeDescription = "Enter a value from -3.4e+38 to 3.4e+38",
        iconRes = R.drawable.type_float_24px,
        textColor = 0xFFD09D96.toInt(),
        nativeId = 4,
        memorySize = 4,
    ),
    DOUBLE(
        code = "E",
        displayName = "Double",
        rangeDescription = "Enter a value from -1.8e+308 to 1.8e+308",
        iconRes = R.drawable.type_float_24px,
        textColor = 0xFFF0F2A6.toInt(),
        nativeId = 5,
        memorySize = 8,
    ),
    WORD(
        code = "W",
        displayName = "Word",
        rangeDescription = "Enter a value from -32,768 to 65,535",
        iconRes = R.drawable.type_integer_24px,
        textColor = 0xFF50E9AE.toInt(),
        nativeId = 1,
        memorySize = 2,
    ),
    BYTE(
        code = "B",
        displayName = "Byte",
        rangeDescription = "Enter a value from -128 to 255",
        iconRes = R.drawable.type_integer_24px,
        textColor = 0xFFCC95C2.toInt(),
        nativeId = 0,
        memorySize = 1,
    ),
    QWORD(
        code = "Q",
        displayName = "Qword",
        rangeDescription = "Enter a value from -9,223,372,036,854,775,808 to 18,446,744,073,709,551,615",
        iconRes = R.drawable.type_integer_24px,
        textColor = 0xFF459CFC.toInt(),
        nativeId = 3,
        memorySize = 8,
    ),
    XOR(
        code = "X",
        displayName = "Xor",
        rangeDescription = "Enter a value from -2,147,483,648 to 4,294,967,295",
        iconRes = R.drawable.type_xor_24px,
        textColor = 0xFF8283C9.toInt(),
        nativeId = 7,
        memorySize = 4,
        isDisabled = true
    ),
    UTF_8(
        code = "UTF-8",
        displayName = "UTF-8 text",
        rangeDescription = "Enter UTF-8 encoded text",
        iconRes = R.drawable.type_text_24px,
        textColor = Color.WHITE,
        nativeId = 100,
        memorySize = Long.MIN_VALUE,
        isDisabled = true
    ),
    UTF_16LE(
        code = "UTF-16LE",
        displayName = "UTF-16LE text",
        rangeDescription = "Enter UTF-16LE encoded text",
        iconRes = R.drawable.type_text_24px,
        textColor = Color.WHITE,
        nativeId = 101,
        memorySize = Long.MIN_VALUE,
        isDisabled = true
    ),
    HEX(
        code = "HEX",
        displayName = "HEX",
        rangeDescription = "Enter a hexadecimal value",
        iconRes = R.drawable.type_hex_24px,
        textColor = Color.WHITE,
        nativeId = 102,
        memorySize = Long.MIN_VALUE,
        isDisabled = true
    ),
    HEX_MIXED(
        code = "HEX_MIXED",
        displayName = "HEX + UTF-8 + UTF-16LE",
        rangeDescription = "Enter a hexadecimal value or text",
        iconRes = R.drawable.type_mixed_24px,
        textColor = Color.WHITE,
        nativeId = 103,
        memorySize = Long.MIN_VALUE,
        isDisabled = true
    ),
    ARM(
        code = "ARM",
        displayName = "ARM",
        rangeDescription = "Enter an ARM instruction",
        iconRes = R.drawable.type_code_24px,
        textColor = Color.WHITE,
        nativeId = 104,
        memorySize = Long.MIN_VALUE,
        isDisabled = true
    ),
    ARM64(
        code = "ARM64",
        displayName = "ARM64",
        rangeDescription = "Enter an ARM64 instruction",
        iconRes = R.drawable.type_code_24px,
        textColor = Color.WHITE,
        nativeId = 105,
        memorySize = Long.MIN_VALUE,
        isDisabled = true
    ),
    PATTERN(
        code = "P",
        displayName = "Pattern",
        rangeDescription = "Enter a pattern such as 1A 2B ?C D? ?? FF",
        iconRes = R.drawable.icon_search_24px,
        textColor = 0xFFFFAA00.toInt(),
        nativeId = 8,
        memorySize = 0,  // 可变长度
        isDisabled = false
    );

    companion object {
        fun fromCode(code: String): DisplayValueType? {
            return entries.find { it.code == code }
        }

        fun fromNativeId(id: Int): DisplayValueType? {
            return entries.find { it.nativeId == id }
        }
    }
}