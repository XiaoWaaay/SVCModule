use super::lexer::{Lexer, Token, parse_number, parse_float};
use super::types::{SearchMode, SearchQuery, SearchValue, ValueType};

pub struct Parser<'a> {
    tokens: Vec<Token<'a>>,
    pos: usize,
    default_type: ValueType,
}

impl<'a> Parser<'a> {
    pub fn new(input: &'a str, default_type: ValueType) -> Result<Self, String> {
        let mut lexer = Lexer::new(input);
        let tokens = lexer.tokenize()?;
        Ok(Parser {
            tokens,
            pos: 0,
            default_type,
        })
    }

    #[inline]
    fn peek(&self) -> Option<&Token<'a>> {
        self.tokens.get(self.pos)
    }

    #[inline]
    fn peek_at(&self, offset: usize) -> Option<&Token<'a>> {
        self.tokens.get(self.pos + offset)
    }

    #[inline]
    fn advance(&mut self) -> Option<&Token<'a>> {
        if self.pos < self.tokens.len() {
            let token = &self.tokens[self.pos];
            self.pos += 1;
            Some(token)
        } else {
            None
        }
    }

    #[inline]
    fn expect(&mut self, expected: Token) -> Result<(), String> {
        match self.advance() {
            Some(token) if std::mem::discriminant(token) == std::mem::discriminant(&expected) => Ok(()),
            Some(token) => Err(format!("Expected {:?}, got {:?}", expected, token)),
            None => Err(format!("Expected {:?}, got EOF", expected)),
        }
    }

    fn parse_value(&mut self) -> Result<SearchValue, String> {
        let num_token = match self.advance() {
            Some(Token::Number(s, is_hex)) => (*s, *is_hex),
            Some(token) => return Err(format!("Expected number, got {:?}", token)),
            None => return Err("Expected number, got EOF".to_string()),
        };

        let next_token = self.peek();

        match next_token {
            Some(Token::Tilde) | Some(Token::DoubleTilde) => {
                let exclude = matches!(next_token, Some(Token::DoubleTilde));
                self.advance();

                self.parse_range(num_token, exclude)
            }
            Some(Token::Type(value_type)) => {
                let value_type = *value_type;
                self.advance();

                if matches!(self.peek(), Some(Token::Tilde) | Some(Token::DoubleTilde)) {
                    let exclude = matches!(self.peek(), Some(Token::DoubleTilde));
                    self.advance();
                    self.parse_range_with_type(num_token, value_type, exclude)
                } else {
                    self.create_fixed_value(num_token, value_type)
                }
            }
            _ => {
                self.create_fixed_value(num_token, self.default_type)
            }
        }
    }

    fn parse_range(&mut self, start_token: (&'a str, bool), exclude: bool) -> Result<SearchValue, String> {
        let end_token = match self.advance() {
            Some(Token::Number(s, is_hex)) => (*s, *is_hex),
            Some(token) => return Err(format!("Expected number after range operator, got {:?}", token)),
            None => return Err("Expected number after range operator, got EOF".to_string()),
        };

        let value_type = match self.peek() {
            Some(Token::Type(vt)) => {
                let vt = *vt;
                self.advance();
                vt
            }
            _ => self.default_type,
        };

        self.create_range_value(start_token, end_token, value_type, exclude)
    }

    fn parse_range_with_type(
        &mut self,
        start_token: (&'a str, bool),
        value_type: ValueType,
        exclude: bool,
    ) -> Result<SearchValue, String> {
        let end_token = match self.advance() {
            Some(Token::Number(s, is_hex)) => (*s, *is_hex),
            Some(token) => return Err(format!("Expected number after range operator, got {:?}", token)),
            None => return Err("Expected number after range operator, got EOF".to_string()),
        };

        if let Some(Token::Type(end_type)) = self.peek() {
            if *end_type != value_type {
                return Err(format!(
                    "Range type mismatch: start is {}, end is {}",
                    value_type, end_type
                ));
            }
            self.advance();
        }

        self.create_range_value(start_token, end_token, value_type, exclude)
    }

    fn create_fixed_value(&self, num_token: (&'a str, bool), value_type: ValueType) -> Result<SearchValue, String> {
        let (num_str, is_hex) = num_token;

        if value_type.is_float_type() {
            let value = parse_float(num_str, is_hex)?;
            Ok(SearchValue::fixed_float(value, value_type))
        } else {
            let value = parse_number(num_str, is_hex)?;
            if value > u64::MAX as i128  {
                return Err(format!("Value {} exceeds maximum for fixed search", value));
            }
            if value < i64::MIN as i128 {
                return Err(format!("Value {} is below minimum for fixed search", value));
            }
            Ok(SearchValue::fixed(value, value_type))
        }
    }

    fn create_range_value(
        &self,
        start_token: (&'a str, bool),
        end_token: (&'a str, bool),
        value_type: ValueType,
        exclude: bool,
    ) -> Result<SearchValue, String> {
        let (start_str, start_is_hex) = start_token;
        let (end_str, end_is_hex) = end_token;

        if value_type.is_float_type() {
            let start = parse_float(start_str, start_is_hex)?;
            let end = parse_float(end_str, end_is_hex)?;

            if start > end {
                return Err(format!("Range start ({}) must be <= end ({})", start, end));
            }

            Ok(SearchValue::range_float(start, end, value_type, exclude))
        } else {
            let start = parse_number(start_str, start_is_hex)?;
            let end = parse_number(end_str, end_is_hex)?;

            if start > i64::MAX as i128  || end > i64::MAX as i128  {
                return Err(format!("Range values exceed maximum for integer range search: start={}, end={}", start, end));
            }

            if start < i64::MIN as i128 || end < i64::MIN as i128 {
                return Err(format!("Range values are below minimum for integer range search: start={}, end={}", start, end));
            }

            if start > end {
                return Err(format!("Range start ({}) must be <= end ({})", start, end));
            }

            Ok(SearchValue::range(start, end, value_type, exclude))
        }
    }

    fn parse_values(&mut self) -> Result<Vec<SearchValue>, String> {
        let mut values = Vec::new();

        values.push(self.parse_value()?);

        while matches!(self.peek(), Some(Token::Semicolon)) {
            self.advance();
            values.push(self.parse_value()?);
        }

        Ok(values)
    }

    fn parse_range_specifier(&mut self) -> Result<(SearchMode, u16), String> {
        match self.peek() {
            Some(Token::Colon) => {
                self.advance();
                let range = self.parse_range_size()?;
                Ok((SearchMode::Unordered, range))
            }
            Some(Token::DoubleColon) => {
                self.advance();
                let range = self.parse_range_size()?;
                Ok((SearchMode::Ordered, range))
            }
            None => {
                Ok((SearchMode::Unordered, 512))
            }
            Some(token) => Err(format!("Expected colon or end of input, got {:?}", token)),
        }
    }

    fn parse_range_size(&mut self) -> Result<u16, String> {
        match self.advance() {
            Some(Token::Number(s, is_hex)) => {
                let size = parse_number(s, *is_hex)?;
                if size < 2 || size > 65536 {
                    return Err(format!("Range size must be between 2 and 65536, got {}", size));
                }
                Ok(size as u16)
            }
            Some(token) => Err(format!("Expected number for range size, got {:?}", token)),
            None => Ok(512),
        }
    }

    pub fn parse(&mut self) -> Result<SearchQuery, String> {
        let values = self.parse_values()?;
        let (mode, range) = self.parse_range_specifier()?;

        if self.pos < self.tokens.len() {
            return Err(format!("Unexpected tokens after query: {:?}", &self.tokens[self.pos..]));
        }

        let query = SearchQuery::new(values, mode, range);
        query.validate()?;

        Ok(query)
    }
}

pub fn parse_search_query(input: &str, default_type: ValueType) -> Result<SearchQuery, String> {
    let mut parser = Parser::new(input, default_type)?;
    parser.parse()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_simple() {
        let query = parse_search_query("100D;200F", ValueType::Dword).unwrap();
        assert_eq!(query.values.len(), 2);
        assert_eq!(query.mode, SearchMode::Unordered);
        assert_eq!(query.range, 512);
    }

    #[test]
    fn test_parse_with_range() {
        let query = parse_search_query("100D;200F:1024", ValueType::Dword).unwrap();
        assert_eq!(query.range, 1024);
        assert_eq!(query.mode, SearchMode::Unordered);
    }

    #[test]
    fn test_parse_ordered() {
        let query = parse_search_query("100D;200F::256", ValueType::Dword).unwrap();
        assert_eq!(query.mode, SearchMode::Ordered);
        assert_eq!(query.range, 256);
    }

    #[test]
    fn test_parse_hex() {
        let query = parse_search_query("10h;FFh", ValueType::Dword).unwrap();
        assert_eq!(query.values.len(), 2);
    }

    #[test]
    fn test_parse_comma_separator() {
        let query = parse_search_query("1,000D;2,000D", ValueType::Dword).unwrap();
        assert_eq!(query.values.len(), 2);
    }

    #[test]
    fn test_parse_mixed() {
        let query = parse_search_query("BAADh;1,77D;100~1,000F::512", ValueType::Dword).unwrap();
        assert_eq!(query.values.len(), 3);
        assert_eq!(query.mode, SearchMode::Ordered);
        assert_eq!(query.range, 512);
    }

    #[test]
    fn test_single_value_search() {
        let query = parse_search_query("100D", ValueType::Dword).unwrap();
        assert_eq!(query.values.len(), 1);
    }

    #[test]
    fn test_validation_invalid_range() {
        let result = parse_search_query("100D;200D:1", ValueType::Dword);
        assert!(result.is_err());
    }

    #[test]
    fn test_parse_float_values() {
        let query = parse_search_query("1.0F", ValueType::Float).unwrap();
        assert_eq!(query.values.len(), 1);
        assert!(matches!(query.values[0], SearchValue::FixedFloat { .. }));
        assert_eq!(query.values[0].value_type(), ValueType::Float);
    }

    #[test]
    fn test_parse_float_with_type() {
        let query = parse_search_query("3.14F;2.718E", ValueType::Float).unwrap();
        assert_eq!(query.values.len(), 2);
        assert!(matches!(query.values[0], SearchValue::FixedFloat { .. }));
        assert_eq!(query.values[0].value_type(), ValueType::Float);
        assert_eq!(query.values[1].value_type(), ValueType::Double);
    }

    #[test]
    fn test_parse_float_range() {
        let query = parse_search_query("1.0~10.5F", ValueType::Float).unwrap();
        assert_eq!(query.values.len(), 1);
        assert!(matches!(query.values[0], SearchValue::RangeFloat { .. }));
    }

    #[test]
    fn test_parse_float_with_comma_separator() {
        let query = parse_search_query("1,234.56F", ValueType::Float).unwrap();
        assert_eq!(query.values.len(), 1);
        assert!(matches!(query.values[0], SearchValue::FixedFloat { .. }));
    }
}
