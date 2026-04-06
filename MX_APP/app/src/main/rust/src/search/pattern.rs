//! 特征码解析和搜索模块
//! 
//! 支持的格式: "1A 2B ?C D? ?? FF"
//! - 完整字节: "1A", "FF"
//! - 高半字节通配: "1?", "A?"
//! - 低半字节通配: "?A", "?F"
//! - 完全通配: "??"

use super::types::SearchValue;

/// 解析特征码字符串
/// 
/// # 参数
/// * `input` - 特征码字符串，如 "1A 2B ?C D? ?? FF"
/// 
/// # 返回
/// * `Ok(Vec<(u8, u8)>)` - 解析成功，返回 (value, mask) 数组
/// * `Err(String)` - 解析失败，返回错误信息
pub fn parse_pattern(input: &str) -> Result<Vec<(u8, u8)>, String> {
    let input = input.trim();
    if input.is_empty() {
        return Err("Empty pattern".to_string());
    }

    let mut result = Vec::new();

    for part in input.split_whitespace() {
        if part.len() != 2 {
            return Err(format!("Invalid byte '{}': expected 2 characters", part));
        }

        let chars: Vec<char> = part.chars().collect();
        let (value, mask) = parse_byte(chars[0], chars[1])?;
        result.push((value, mask));
    }

    if result.is_empty() {
        return Err("Empty pattern after parsing".to_string());
    }

    Ok(result)
}

/// 解析单个字节（两个十六进制字符）
fn parse_byte(high: char, low: char) -> Result<(u8, u8), String> {
    let (high_val, high_mask) = parse_nibble(high)?;
    let (low_val, low_mask) = parse_nibble(low)?;

    let value = (high_val << 4) | low_val;
    let mask = (high_mask << 4) | low_mask;

    Ok((value, mask))
}

/// 解析单个半字节（一个十六进制字符）
fn parse_nibble(c: char) -> Result<(u8, u8), String> {
    match c {
        '?' => Ok((0, 0)),  // 通配符，mask=0 表示不检查
        '0'..='9' => Ok((c as u8 - b'0', 0xF)),
        'A'..='F' => Ok((c as u8 - b'A' + 10, 0xF)),
        'a'..='f' => Ok((c as u8 - b'a' + 10, 0xF)),
        _ => Err(format!("Invalid hex character: '{}'", c)),
    }
}

/// 从特征码字符串创建 SearchValue
pub fn create_pattern_search_value(input: &str) -> Result<SearchValue, String> {
    let pattern = parse_pattern(input)?;
    Ok(SearchValue::Pattern { pattern })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_full_bytes() {
        let result = parse_pattern("1A 2B FF 00").unwrap();
        assert_eq!(result.len(), 4);
        assert_eq!(result[0], (0x1A, 0xFF));
        assert_eq!(result[1], (0x2B, 0xFF));
        assert_eq!(result[2], (0xFF, 0xFF));
        assert_eq!(result[3], (0x00, 0xFF));
    }

    #[test]
    fn test_parse_wildcards() {
        let result = parse_pattern("?? 1? ?A").unwrap();
        assert_eq!(result.len(), 3);
        // ?? -> value=0, mask=0
        assert_eq!(result[0], (0x00, 0x00));
        // 1? -> value=0x10, mask=0xF0
        assert_eq!(result[1], (0x10, 0xF0));
        // ?A -> value=0x0A, mask=0x0F
        assert_eq!(result[2], (0x0A, 0x0F));
    }

    #[test]
    fn test_parse_mixed() {
        let result = parse_pattern("1A ?B C? ??").unwrap();
        assert_eq!(result.len(), 4);
        assert_eq!(result[0], (0x1A, 0xFF)); // 完全匹配
        assert_eq!(result[1], (0x0B, 0x0F)); // 低半字节匹配
        assert_eq!(result[2], (0xC0, 0xF0)); // 高半字节匹配
        assert_eq!(result[3], (0x00, 0x00)); // 完全通配
    }

    #[test]
    fn test_parse_lowercase() {
        let result = parse_pattern("ab cd ef").unwrap();
        assert_eq!(result.len(), 3);
        assert_eq!(result[0], (0xAB, 0xFF));
        assert_eq!(result[1], (0xCD, 0xFF));
        assert_eq!(result[2], (0xEF, 0xFF));
    }

    #[test]
    fn test_parse_empty() {
        assert!(parse_pattern("").is_err());
        assert!(parse_pattern("   ").is_err());
    }

    #[test]
    fn test_parse_invalid() {
        assert!(parse_pattern("1").is_err());      // 单字符
        assert!(parse_pattern("1AG").is_err());    // 三字符
        assert!(parse_pattern("GG").is_err());     // 无效字符
        assert!(parse_pattern("1A 2").is_err());   // 混合有效无效
    }

    #[test]
    fn test_match_pattern() {
        let sv = create_pattern_search_value("1A ?B C? ??").unwrap();
        
        // 完全匹配
        assert!(sv.match_pattern(&[0x1A, 0x0B, 0xC0, 0x00]));
        assert!(sv.match_pattern(&[0x1A, 0x1B, 0xC5, 0xFF]));
        assert!(sv.match_pattern(&[0x1A, 0xFB, 0xCF, 0x12]));
        
        // 不匹配
        assert!(!sv.match_pattern(&[0x2A, 0x0B, 0xC0, 0x00])); // 第一字节不匹配
        assert!(!sv.match_pattern(&[0x1A, 0x0C, 0xC0, 0x00])); // 第二字节低半字节不匹配
        assert!(!sv.match_pattern(&[0x1A, 0x0B, 0xD0, 0x00])); // 第三字节高半字节不匹配
        
        // 长度不足
        assert!(!sv.match_pattern(&[0x1A, 0x0B, 0xC0]));
    }
}
