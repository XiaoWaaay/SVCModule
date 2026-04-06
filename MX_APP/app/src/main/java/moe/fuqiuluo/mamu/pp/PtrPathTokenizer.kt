package moe.fuqiuluo.mamu.pp

/**
 * Token types
 */
enum class TokenType {
    // Literals and identifiers
    NUMBER,        // 123, 0x10
    IDENTIFIER,    // ptr, base
    DATA_TYPE,     // u64, i32

    // Punctuation
    DOLLAR,        // $
    UNDERSCORE,    // _
    COLON,         // :
    STAR,          // *
    PLUS,          // +
    MINUS,         // -
    QUESTION,      // ?
    AT,            // @
    LBRACKET,      // [
    RBRACKET,      // ]
    LPAREN,        // (
    RPAREN,        // )
    COMMA,         // ,

    // Comparison operator
    EQ,            // ==
    NE,            // !=
    GT,            // >
    LT,            // <
    GE,            // >=
    LE,            // <=

    // Bitwise operator
    AND,           // &
    OR,            // |
    XOR,           // ^

    // Logical operator
    LAND,          // &&
    LOR,           // ||
    NOT,           // !

    // Special tokens
    EOF            // End of file
}

/**
 * Token data class
 */
data class Token(
    val type: TokenType,
    val value: String,
    val position: Int
)

/**
 * Tokenizer
 */
object PtrPathTokenizer {

    // Data-type keywords
    private val dataTypes = setOf("u8", "u16", "u32", "u64", "i8", "i16", "i32", "i64")

    // Built-in keywords
    private val builtinKeywords = setOf("skip", "null", "stop", "current")

    /**
     * Tokenizer entry point
     * @param input 输入字符串
     * @param hexMode Hexadecimal模式（true时所有数字按Hexadecimal解析）
     * @return Token列表
     */
    fun tokenize(input: String, hexMode: Boolean = false): List<Token> {
        val tokens = mutableListOf<Token>()
        var pos = 0

        while (pos < input.length) {
            val ch = input[pos]

            // 跳过空白字符
            if (ch.isWhitespace()) {
                pos++
                continue
            }

            // Hexadecimal数字（0x前缀）- 必须在普通数字之前检查
            if (ch == '0' && pos + 1 < input.length && input[pos + 1] in "xX") {
                val (token, newPos) = scanHexNumber(input, pos)
                tokens.add(token)
                pos = newPos
                continue
            }

            // 数字（hexMode下也包括a-f）
            if (ch.isDigit() || (hexMode && ch in 'a'..'f') || (hexMode && ch in 'A'..'F')) {
                val (token, newPos) = scanNumber(input, pos, hexMode)
                tokens.add(token)
                pos = newPos
                continue
            }

            // 标识符或关键字（非hexMode下，或hexMode下非a-f开头的标识符）
            if (ch.isLetter() || ch == '_') {
                // 在hexMode下，a-f会被上面的数字分支处理，这里只处理g-z和_开头的标识符
                val (token, newPos) = scanIdentifier(input, pos)
                tokens.add(token)
                pos = newPos
                continue
            }

            // 美元Punctuation $
            if (ch == '$') {
                tokens.add(Token(TokenType.DOLLAR, "$", pos))
                pos++
                continue
            }

            // 双字符运算符
            if (pos + 1 < input.length) {
                val twoChar = input.substring(pos, pos + 2)
                val tokenType = when (twoChar) {
                    "==" -> TokenType.EQ
                    "!=" -> TokenType.NE
                    ">=" -> TokenType.GE
                    "<=" -> TokenType.LE
                    "&&" -> TokenType.LAND
                    "||" -> TokenType.LOR
                    else -> null
                }

                if (tokenType != null) {
                    tokens.add(Token(tokenType, twoChar, pos))
                    pos += 2
                    continue
                }
            }

            // 单字符Punctuation
            val tokenType = when (ch) {
                ':' -> TokenType.COLON
                '*' -> TokenType.STAR
                '+' -> TokenType.PLUS
                '-' -> TokenType.MINUS
                '?' -> TokenType.QUESTION
                '@' -> TokenType.AT
                '[' -> TokenType.LBRACKET
                ']' -> TokenType.RBRACKET
                '(' -> TokenType.LPAREN
                ')' -> TokenType.RPAREN
                ',' -> TokenType.COMMA
                '>' -> TokenType.GT
                '<' -> TokenType.LT
                '&' -> TokenType.AND
                '|' -> TokenType.OR
                '^' -> TokenType.XOR
                '!' -> TokenType.NOT
                else -> null
            }

            if (tokenType != null) {
                tokens.add(Token(tokenType, ch.toString(), pos))
                pos++
                continue
            }

            // Unrecognized character
            throw IllegalArgumentException("Unrecognized character '$ch' at position $pos")
        }

        tokens.add(Token(TokenType.EOF, "", pos))
        return tokens
    }

    /**
     * Scan a numeric literal
     */
    private fun scanNumber(input: String, start: Int, hexMode: Boolean): Pair<Token, Int> {
        var pos = start
        val sb = StringBuilder()

        while (pos < input.length) {
            val ch = input[pos]
            if (hexMode) {
                if (ch.isDigit() || ch in 'a'..'f' || ch in 'A'..'F') {
                    sb.append(ch)
                    pos++
                } else {
                    break
                }
            } else {
                if (ch.isDigit()) {
                    sb.append(ch)
                    pos++
                } else {
                    break
                }
            }
        }

        // In hexMode, prefix plain numbers with 0x so the parser treats them as hexadecimal
        val tokenValue = if (hexMode) "0x${sb}" else sb.toString()

        return Token(TokenType.NUMBER, tokenValue, start) to pos
    }

    /**
     * Scan a hexadecimal number (0x prefix)
     */
    private fun scanHexNumber(input: String, start: Int): Pair<Token, Int> {
        var pos = start + 2  // 跳过 "0x"
        val sb = StringBuilder("0x")

        while (pos < input.length) {
            val ch = input[pos]
            if (ch.isDigit() || ch in 'a'..'f' || ch in 'A'..'F') {
                sb.append(ch)
                pos++
            } else {
                break
            }
        }

        if (sb.length == 2) {
            throw IllegalArgumentException("Invalid hexadecimal number at position $start")
        }

        return Token(TokenType.NUMBER, sb.toString(), start) to pos
    }

    /**
     * Scan an identifier or keyword
     */
    private fun scanIdentifier(input: String, start: Int): Pair<Token, Int> {
        var pos = start
        val sb = StringBuilder()

        // The first character must be a letter or underscore
        if (input[pos].isLetter() || input[pos] == '_') {
            sb.append(input[pos])
            pos++
        }

        // Subsequent characters may be letters, digits, or underscores
        while (pos < input.length && (input[pos].isLetterOrDigit() || input[pos] == '_')) {
            sb.append(input[pos])
            pos++
        }

        val identifier = sb.toString()

        // Check whether the token is a data-type keyword
        val tokenType = when {
            identifier in dataTypes -> TokenType.DATA_TYPE
            else -> TokenType.IDENTIFIER
        }

        return Token(tokenType, identifier, start) to pos
    }
}
