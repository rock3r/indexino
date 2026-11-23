//! A hand-written Java lexer with the shape of IntelliJ's `Lexer` API:
//! `start`, `advance`, `token_type`, `token_start`, `token_end`, `state`.
//!
//! Like the Kotlin/Java lexers accio ports, tokens never overlap or skip text:
//! whitespace and comments are real tokens, and the whole input is covered.

use crate::kinds::*;

pub struct JavaLexer<'a> {
    buffer: &'a str,
    /// Byte offset of the token currently exposed.
    token_start: usize,
    token_end: usize,
    kind: Option<SyntaxKind>,
    /// IntelliJ lexers keep a state; ours only remembers we are inside a
    /// `/**` doc comment (state 1) so a re-`start` can resume.
    state: i32,
    done: bool,
}

impl<'a> JavaLexer<'a> {
    pub fn new(buffer: &'a str) -> Self {
        let mut lexer = JavaLexer {
            buffer,
            token_start: 0,
            token_end: 0,
            kind: None,
            state: 0,
            done: false,
        };
        lexer.advance();
        lexer
    }

    pub fn token_type(&self) -> Option<SyntaxKind> {
        self.kind
    }

    pub fn token_start(&self) -> usize {
        self.token_start
    }

    pub fn token_end(&self) -> usize {
        self.token_end
    }

    pub fn state(&self) -> i32 {
        self.state
    }

    /// Consume one token; a `None` kind afterwards means end of buffer.
    pub fn advance(&mut self) {
        if self.done {
            self.kind = None;
            self.token_start = self.buffer.len();
            self.token_end = self.buffer.len();
            return;
        }
        self.token_start = self.token_end;
        let bytes = self.buffer.as_bytes();
        let rest = &bytes[self.token_start..];
        if rest.is_empty() {
            self.done = true;
            self.kind = None;
            return;
        }
        let text = self.buffer[self.token_start..].as_bytes();
        let (kind, len) = scan_token(text);
        self.token_end = self.token_start + len;
        self.state = match kind {
            Some(DocComment) => 1,
            _ => 0,
        };
        self.kind = kind;
        debug_assert!(self.token_end <= self.buffer.len());
    }

    pub fn text(&self) -> &'a str {
        &self.buffer[self.token_start..self.token_end]
    }
}

fn is_ident_start(b: u8) -> bool {
    b.is_ascii_alphabetic() || b == b'_' || b == b'$' || b >= 0x80
}

fn is_ident_part(b: u8) -> bool {
    is_ident_start(b) || b.is_ascii_digit()
}

fn scan_token(t: &[u8]) -> (Option<SyntaxKind>, usize) {
    let first = t[0];
    match first {
        b' ' | b'\t' | b'\r' | b'\n' | 0x0c => {
            let mut n = 1;
            while n < t.len() && matches!(t[n], b' ' | b'\t' | b'\r' | b'\n' | 0x0c) {
                n += 1;
            }
            (Some(WhiteSpace), n)
        }
        b'/' if t.len() > 1 && t[1] == b'/' => {
            let mut n = 2;
            while n < t.len() && t[n] != b'\n' && t[n] != b'\r' {
                n += 1;
            }
            (Some(LineComment), n)
        }
        b'/' if t.len() > 1 && t[1] == b'*' => {
            let doc = t.len() > 2 && t[2] == b'*' && !(t.len() > 3 && t[3] == b'/');
            let mut n = 2;
            loop {
                if n + 1 >= t.len() {
                    n = t.len();
                    break;
                }
                if t[n] == b'*' && t[n + 1] == b'/' {
                    n += 2;
                    break;
                }
                n += 1;
            }
            (Some(if doc { DocComment } else { BlockComment }), n)
        }
        b'"' => scan_string(t),
        b'\'' => scan_char(t),
        b'0'..=b'9' => scan_number(t),
        b'.' if t.len() > 1 && t[1].is_ascii_digit() => scan_number(t),
        _ if is_ident_start(first) => {
            let mut n = 1;
            while n < t.len() && is_ident_part(t[n]) {
                n += 1;
            }
            let word = std::str::from_utf8(&t[..n]).unwrap_or("");
            (SyntaxKind::keyword_of(word).map_or(Some(Identifier), Some), n)
        }
        _ => scan_operator(t),
    }
}

fn scan_string(t: &[u8]) -> (Option<SyntaxKind>, usize) {
    // text blocks (""") are lexed as one string literal as well
    if t.starts_with(b"\"\"\"") {
        let mut n = 3;
        while n < t.len() {
            if t[n] == b'\\' {
                n += 2;
                continue;
            }
            if t[n..].starts_with(b"\"\"\"") {
                return (Some(StringLiteral), n + 3);
            }
            n += 1;
        }
        return (Some(StringLiteral), t.len());
    }
    let mut n = 1;
    while n < t.len() {
        match t[n] {
            b'\\' => n += 2,
            b'"' => return (Some(StringLiteral), n + 1),
            b'\n' | b'\r' => return (Some(Unknown), n), // unterminated
            _ => n += 1,
        }
    }
    (Some(Unknown), t.len())
}

fn scan_char(t: &[u8]) -> (Option<SyntaxKind>, usize) {
    let mut n = 1;
    while n < t.len() {
        match t[n] {
            b'\\' => n += 2,
            b'\'' => return (Some(CharLiteral), n + 1),
            b'\n' | b'\r' => return (Some(Unknown), n),
            _ => n += 1,
        }
    }
    (Some(Unknown), t.len())
}

fn scan_number(t: &[u8]) -> (Option<SyntaxKind>, usize) {
    let mut n = 0;
    let mut float = false;
    if t.starts_with(b"0x") || t.starts_with(b"0X") {
        n = 2;
        while n < t.len() && (t[n].is_ascii_hexdigit() || t[n] == b'_') {
            n += 1;
        }
    } else if t.starts_with(b"0b") || t.starts_with(b"0B") {
        n = 2;
        while n < t.len() && matches!(t[n], b'0' | b'1' | b'_') {
            n += 1;
        }
    } else {
        while n < t.len() && (t[n].is_ascii_digit() || t[n] == b'_') {
            n += 1;
        }
        if n < t.len() && t[n] == b'.' && n + 1 < t.len() && t[n + 1].is_ascii_digit() {
            float = true;
            n += 1;
            while n < t.len() && (t[n].is_ascii_digit() || t[n] == b'_') {
                n += 1;
            }
        }
        if n < t.len() && (t[n] == b'e' || t[n] == b'E') {
            let mut m = n + 1;
            if m < t.len() && (t[m] == b'+' || t[m] == b'-') {
                m += 1;
            }
            if m < t.len() && t[m].is_ascii_digit() {
                float = true;
                n = m;
                while n < t.len() && t[n].is_ascii_digit() {
                    n += 1;
                }
            }
        }
    }
    // suffix
    if n < t.len() && matches!(t[n], b'l' | b'L' | b'f' | b'F' | b'd' | b'D') {
        float = float || matches!(t[n], b'f' | b'F' | b'd' | b'D');
        n += 1;
    }
    (Some(if float { FloatingPointLiteral } else { IntegerLiteral }), n)
}

fn scan_operator(t: &[u8]) -> (Option<SyntaxKind>, usize) {
    use SyntaxKind::*;
    let two = |a: u8, b: u8| t.len() > 1 && t[0] == a && t[1] == b;
    let three = |a: u8, b: u8, c: u8| t.len() > 2 && t[0] == a && t[1] == b && t[2] == c;
    let four = |a: u8, b: u8, c: u8, d: u8| t.len() > 3 && t[0] == a && t[1] == b && t[2] == c && t[3] == d;
    let kind = if four(b'>', b'>', b'>', b'=') {
        UshrEq
    } else if three(b'>', b'>', b'=') {
        ShrEq
    } else if three(b'<', b'<', b'=') {
        ShlEq
    } else if three(b'>', b'>', b'>') {
        Ushr
    } else if two(b'>', b'>') {
        Shr
    } else if two(b'<', b'<') {
        Shl
    } else if two(b'=', b'=') {
        EqEq
    } else if two(b'!', b'=') {
        NotEq
    } else if two(b'&', b'&') {
        AmpAmp
    } else if two(b'|', b'|') {
        OrOr
    } else if two(b'+', b'+') {
        PlusPlus
    } else if two(b'-', b'-') {
        MinusMinus
    } else if two(b'-', b'>') {
        Arrow
    } else if two(b':', b':') {
        ColonColon
    } else if two(b'.', b'.') && t.len() > 2 && t[2] == b'.' {
        Ellipsis
    } else if two(b'+', b'=') {
        PlusEq
    } else if two(b'-', b'=') {
        MinusEq
    } else if two(b'*', b'=') {
        StarEq
    } else if two(b'/', b'=') {
        SlashEq
    } else if two(b'%', b'=') {
        PercentEq
    } else if two(b'&', b'=') {
        AmpEq
    } else if two(b'|', b'=') {
        OrEq
    } else if two(b'^', b'=') {
        CaretEq
    } else if two(b'>', b'=') {
        Ge
    } else if two(b'<', b'=') {
        Le
    } else {
        match first_of(t) {
            '(' => Lpar,
            ')' => Rpar,
            '{' => Lbrace,
            '}' => Rbrace,
            '[' => Lbracket,
            ']' => Rbracket,
            ';' => Semicolon,
            ',' => Comma,
            '.' => Dot,
            '@' => At,
            '=' => Eq,
            '>' => Gt,
            '<' => Lt,
            '!' => Not,
            '~' => Tilde,
            '?' => Question,
            '+' => Plus,
            '-' => Minus,
            '*' => Star,
            '/' => Slash,
            '%' => Percent,
            '&' => Amp,
            '|' => Or,
            '^' => Caret,
            _ => Unknown,
        }
    };
    let len = match kind {
        UshrEq => 4,
        Ushr | ShrEq | ShlEq | Ellipsis => 3,
        _ if is_two_char(kind) => 2,
        _ => 1,
    };
    (Some(kind), len)
}

fn first_of(t: &[u8]) -> char {
    t[0] as char
}

fn is_two_char(kind: SyntaxKind) -> bool {
    use SyntaxKind::*;
    matches!(
        kind,
        Shr | Shl | EqEq | NotEq | AmpAmp | OrOr | PlusPlus | MinusMinus | Arrow | ColonColon
            | PlusEq | MinusEq | StarEq | SlashEq | PercentEq | AmpEq | OrEq | CaretEq | Ge | Le
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn kinds(input: &str) -> Vec<(SyntaxKind, &str)> {
        let mut lexer = JavaLexer::new(input);
        let mut out = vec![];
        while let Some(kind) = lexer.token_type() {
            out.push((kind, lexer.text()));
            lexer.advance();
        }
        out
    }

    fn non_trivia(input: &str) -> Vec<SyntaxKind> {
        kinds(input).into_iter().filter(|(k, _)| *k != WhiteSpace).map(|(k, _)| k).collect()
    }

    #[test]
    fn covers_simple_input() {
        let tokens = kinds("public class A { int x = 1; }");
        assert_eq!(tokens[0], (PublicKeyword, "public"));
        assert!(tokens.iter().any(|(k, t)| *k == Identifier && *t == "A"));
        // the lexer covers every byte: joined token texts rebuild the input
        let joined: String = tokens.iter().map(|(_, t)| *t).collect();
        assert_eq!(joined, "public class A { int x = 1; }");
    }

    #[test]
    fn keywords_identifiers_literals() {
        let kinds_only = non_trivia("class Foo42 return 'a' \"hi\\n\" 0x1F 1.5e3 10L");
        assert_eq!(
            kinds_only,
            vec![
                ClassKeyword,
                Identifier,
                ReturnKeyword,
                CharLiteral,
                StringLiteral,
                IntegerLiteral,
                FloatingPointLiteral,
                IntegerLiteral
            ]
        );
    }

    #[test]
    fn comments_and_operators() {
        let kinds_only = non_trivia("// c\n/* b */ /** d */ a >>>= >>= >= :: ->");
        assert_eq!(
            kinds_only,
            vec![
                LineComment,
                BlockComment,
                DocComment,
                Identifier,
                UshrEq,
                ShrEq,
                Ge,
                ColonColon,
                Arrow
            ]
        );
    }
}
