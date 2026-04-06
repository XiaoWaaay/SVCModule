use std::i128;
use super::types::ValueType;

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Token<'a> {
    Number(&'a str, bool),
    Type(ValueType),
    Semicolon,
    Colon,
    DoubleColon,
    Tilde,
    DoubleTilde,
}

pub struct Lexer<'a> {
    input: &'a str,
    pos: usize,
    bytes: &'a [u8],
}

impl<'a> Lexer<'a> {
    #[inline]
    pub fn new(input: &'a str) -> Self {
        Lexer {
            input,
            pos: 0,
            bytes: input.as_bytes(),
        }
    }

    #[inline]
    fn peek(&self) -> Option<u8> {
        if self.pos < self.bytes.len() {
            Some(self.bytes[self.pos])
        } else {
            None
        }
    }

    #[inline]
    fn peek_at(&self, offset: usize) -> Option<u8> {
        let pos = self.pos + offset;
        if pos < self.bytes.len() {
            Some(self.bytes[pos])
        } else {
            None
        }
    }

    #[inline]
    fn advance(&mut self) -> Option<u8> {
        if self.pos < self.bytes.len() {
            let ch = self.bytes[self.pos];
            self.pos += 1;
            Some(ch)
        } else {
            None
        }
    }

    #[inline]
    fn skip_whitespace(&mut self) {
        while let Some(ch) = self.peek() {
            if ch.is_ascii_whitespace() {
                self.pos += 1;
            } else {
                break;
            }
        }
    }

    fn has_hex_suffix(&self, from_pos: usize) -> bool {
        let mut pos = from_pos;
        while pos < self.bytes.len() {
            match self.bytes[pos] {
                b'0'..=b'9' | b'A'..=b'F' | b'a'..=b'f' | b',' => pos += 1,
                b'h' | b'H' => return true,
                _ => return false,
            }
        }
        false
    }

    fn read_number(&mut self) -> Result<Token<'a>, String> {
        let start = self.pos;
        let mut is_hex = false;
        let mut has_decimal_point = false;

        // 处理可选的负号前缀
        if self.peek() == Some(b'-') {
            self.pos += 1;
        }

        while let Some(ch) = self.peek() {
            match ch {
                b'0'..=b'9' | b',' => {
                    self.pos += 1;
                }
                b'.' => {
                    if has_decimal_point || is_hex {
                        break;
                    }
                    if self.peek_at(1).map_or(false, |c| c.is_ascii_digit()) {
                        has_decimal_point = true;
                        self.pos += 1;
                    } else {
                        break;
                    }
                }
                b'A'..=b'F' | b'a'..=b'f' => {
                    if self.has_hex_suffix(self.pos) {
                        self.pos += 1;
                    } else {
                        break;
                    }
                }
                b'h' | b'H' => {
                    if has_decimal_point {
                        return Err("Hex suffix not allowed for floating point numbers".to_string());
                    }
                    self.pos += 1;
                    is_hex = true;
                    break;
                }
                _ => break,
            }
        }

        if start == self.pos {
            return Err("Expected number".to_string());
        }

        let num_str = &self.input[start..self.pos];
        Ok(Token::Number(num_str, is_hex))
    }

    pub fn next_token(&mut self) -> Result<Option<Token<'a>>, String> {
        self.skip_whitespace();

        match self.peek() {
            None => Ok(None),
            Some(ch) => match ch {
                b';' => {
                    self.advance();
                    Ok(Some(Token::Semicolon))
                }
                b':' => {
                    self.advance();
                    if self.peek() == Some(b':') {
                        self.advance();
                        Ok(Some(Token::DoubleColon))
                    } else {
                        Ok(Some(Token::Colon))
                    }
                }
                b'~' => {
                    self.advance();
                    if self.peek() == Some(b'~') {
                        self.advance();
                        Ok(Some(Token::DoubleTilde))
                    } else {
                        Ok(Some(Token::Tilde))
                    }
                }
                b'0'..=b'9' => self.read_number().map(Some),
                b'-' => {
                    // 检查下一个字符是否为数字（支持负数）
                    if self.peek_at(1).map_or(false, |c| c.is_ascii_digit()) {
                        self.read_number().map(Some)
                    } else {
                        Err(format!("Unexpected character: {}", ch as char))
                    }
                }
                b'A'..=b'Z' | b'a'..=b'z' => {
                    let start_pos = self.pos;
                    let result = self.read_number();

                    if result.is_ok() && self.pos > start_pos {
                        result.map(Some)
                    } else {
                        self.pos = start_pos;
                        let type_char = ch as char;
                        if let Some(value_type) = ValueType::from_char(type_char) {
                            self.advance();
                            Ok(Some(Token::Type(value_type)))
                        } else {
                            Err(format!("Invalid character: {}", type_char))
                        }
                    }
                }
                _ => Err(format!("Unexpected character: {}", ch as char)),
            },
        }
    }

    pub fn tokenize(&mut self) -> Result<Vec<Token<'a>>, String> {
        let mut tokens = Vec::with_capacity(32);
        while let Some(token) = self.next_token()? {
            tokens.push(token);
        }
        Ok(tokens)
    }
}

pub fn parse_number(s: &str, is_hex: bool) -> Result<i128, String> {
    let cleaned = s.replace(',', "");

    let cleaned = if cleaned.ends_with('h') || cleaned.ends_with('H') {
        &cleaned[..cleaned.len() - 1]
    } else {
        &cleaned
    };

    if is_hex {
        i128::from_str_radix(cleaned, 16)
            .map_err(|_| format!("Invalid hex number: {}", s))
    } else {
        cleaned.parse::<i128>()
            .map_err(|_| format!("Invalid decimal number: {}", s))
    }
}

pub fn parse_float(s: &str, is_hex: bool) -> Result<f64, String> {
    if is_hex {
        return Err("Hex notation not supported for floating point".to_string());
    }

    let cleaned = s.replace(',', "");
    cleaned.parse::<f64>()
        .map_err(|_| format!("Invalid float number: {}", s))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_tokenize_simple() {
        let mut lexer = Lexer::new("100D;200F");
        let tokens = lexer.tokenize().unwrap();
        assert_eq!(tokens.len(), 5);
    }

    #[test]
    fn test_tokenize_hex() {
        let mut lexer = Lexer::new("10h;FFh");
        let tokens = lexer.tokenize().unwrap();
        assert!(matches!(tokens[0], Token::Number(_, true)));
    }

    #[test]
    fn test_parse_number() {
        assert_eq!(parse_number("100", false).unwrap(), 100);
        assert_eq!(parse_number("1,000", false).unwrap(), 1000);
        assert_eq!(parse_number("10h", true).unwrap(), 16);
        assert_eq!(parse_number("FFh", true).unwrap(), 255);
    }

    #[test]
    fn test_parse_float() {
        assert_eq!(parse_float("1.0", false).unwrap(), 1.0);
        assert_eq!(parse_float("3.14", false).unwrap(), 3.14);
        assert_eq!(parse_float("1,234.56", false).unwrap(), 1234.56);
        assert!(parse_float("1.0h", true).is_err());
    }

    #[test]
    fn test_tokenize_float() {
        let mut lexer = Lexer::new("1.0F;3.14D");
        let tokens = lexer.tokenize().unwrap();
        assert_eq!(tokens.len(), 5);
        assert!(matches!(tokens[0], Token::Number("1.0", false)));
    }

    #[test]
    fn test_tokenize_float_with_range() {
        let mut lexer = Lexer::new("1.0~10.5F");
        let tokens = lexer.tokenize().unwrap();
        assert_eq!(tokens.len(), 4);
        assert!(matches!(tokens[0], Token::Number("1.0", false)));
        assert!(matches!(tokens[1], Token::Tilde));
        assert!(matches!(tokens[2], Token::Number("10.5", false)));
    }

    #[test]
    fn test_negative_numbers() {
        // 测试单个负数
        let mut lexer = Lexer::new("-100");
        let tokens = lexer.tokenize().unwrap();
        assert_eq!(tokens.len(), 1);
        assert!(matches!(tokens[0], Token::Number("-100", false)));

        // 测试负数解析
        assert_eq!(parse_number("-100", false).unwrap(), -100);
        assert_eq!(parse_number("-1,000", false).unwrap(), -1000);

        // 测试负浮点数
        assert_eq!(parse_float("-3.14", false).unwrap(), -3.14);
        assert_eq!(parse_float("-1,234.56", false).unwrap(), -1234.56);
    }

    #[test]
    fn test_negative_number_range() {
        // 测试负数范围搜索
        let mut lexer = Lexer::new("-100~100");
        let tokens = lexer.tokenize().unwrap();
        assert_eq!(tokens.len(), 3);
        assert!(matches!(tokens[0], Token::Number("-100", false)));
        assert!(matches!(tokens[1], Token::Tilde));
        assert!(matches!(tokens[2], Token::Number("100", false)));

        // 测试两边都是负数的范围搜索
        let mut lexer2 = Lexer::new("-100~-50");
        let tokens2 = lexer2.tokenize().unwrap();
        assert_eq!(tokens2.len(), 3);
        assert!(matches!(tokens2[0], Token::Number("-100", false)));
        assert!(matches!(tokens2[1], Token::Tilde));
        assert!(matches!(tokens2[2], Token::Number("-50", false)));
    }

    #[test]
    fn test_negative_number_group() {
        // 测试负数组搜索
        let mut lexer = Lexer::new("-10;0;10");
        let tokens = lexer.tokenize().unwrap();
        assert_eq!(tokens.len(), 5);
        assert!(matches!(tokens[0], Token::Number("-10", false)));
        assert!(matches!(tokens[1], Token::Semicolon));
        assert!(matches!(tokens[2], Token::Number("0", false)));
        assert!(matches!(tokens[3], Token::Semicolon));
        assert!(matches!(tokens[4], Token::Number("10", false)));
    }
}
