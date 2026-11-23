//! The parser: a `PsiBuilder`-style marker API over a rowan green tree.
//!
//! IntelliJ's `PsiBuilder` hands out markers (`mark`, `done`, `precede`,
//! `error`); the parser composes them into composite nodes over the token
//! stream. We follow the event-buffer formulation of that contract (the same
//! one rust-analyzer uses): parse into `Event`s first, then replay them, with
//! the original text, into a `GreenNodeBuilder`. Whitespace and comments land
//! in the tree as trivia, so the green tree is lossless — every byte of input
//! is a token in the tree, as in a PSI green tree.

use crate::kinds::*;
use crate::lexer::JavaLexer;
use std::collections::VecDeque;

pub enum Event {
    /// A `Start` that is already consumed (while walking a `precede` chain).
    Tombstone,
    Start { kind: SyntaxKind, forward_parent: Option<u16> },
    Finish,
    Token { kind: SyntaxKind, len: usize },
}

/// How many tokens of lookahead `nth` can serve. The grammar below uses two.
const LOOKAHEAD: usize = 4;

#[derive(Copy, Clone, PartialEq)]
pub struct Marker {
    pos: usize,
}

pub struct Parser<'a> {
    text: &'a str,
    lexer: JavaLexer<'a>,
    pub(crate) events: Vec<Event>,
    /// Tokens lexed ahead: trivia before the current token, then it, then
    /// lookahead. `current_idx` points at the current non-trivia token.
    queue: VecDeque<(SyntaxKind, usize)>,
    front_offset: usize,
    current_idx: usize,
    eof_seen: bool,
}

impl<'a> Parser<'a> {
    pub fn new(text: &'a str) -> Parser<'a> {
        let mut parser = Parser {
            text,
            lexer: JavaLexer::new(text),
            events: Vec::new(),
            queue: VecDeque::new(),
            front_offset: 0,
            current_idx: 0,
            eof_seen: false,
        };
        parser.fill();
        parser.skip_trivia();
        parser
    }

    fn fill(&mut self) {
        while !self.eof_seen && self.queue.len() <= LOOKAHEAD {
            match self.lexer.token_type() {
                Some(kind) => {
                    self.queue.push_back((kind, self.lexer.token_end() - self.lexer.token_start()));
                    self.lexer.advance();
                }
                None => self.eof_seen = true,
            }
        }
    }

    fn skip_trivia(&mut self) {
        self.current_idx = 0;
        while self
            .queue
            .get(self.current_idx)
            .is_some_and(|(kind, _)| *kind == WhiteSpace || kind.is_comment())
        {
            self.current_idx += 1;
        }
    }

    /// The current token's kind; past the end it stays `Unknown`.
    pub fn current(&self) -> SyntaxKind {
        self.nth(0)
    }

    /// Lookahead over non-trivia tokens: `nth(1)` is the token after the
    /// current one (PsiBuilder's `rawTokenLookup`-free equivalent).
    pub fn nth(&self, n: usize) -> SyntaxKind {
        let mut seen = 0;
        for (idx, (kind, _)) in self.queue.iter().enumerate() {
            if idx < self.current_idx {
                continue;
            }
            if kind == &WhiteSpace || kind.is_comment() {
                continue;
            }
            if seen == n {
                return *kind;
            }
            seen += 1;
        }
        Unknown
    }

    /// Text of the `n`-th non-trivia token ahead.
    pub fn nth_text(&self, n: usize) -> &str {
        let mut seen = 0;
        let mut offset = self.front_offset;
        for (idx, (kind, len)) in self.queue.iter().enumerate() {
            if idx >= self.current_idx && kind != &WhiteSpace && !kind.is_comment() {
                if seen == n {
                    return &self.text[offset..offset + len];
                }
                seen += 1;
            }
            offset += len;
        }
        ""
    }

    pub fn at(&self, kind: SyntaxKind) -> bool {
        self.current() == kind
    }

    pub fn at_eof(&self) -> bool {
        self.current_idx >= self.queue.len() && self.eof_seen
            || self.queue.iter().skip(self.current_idx).all(|(k, _)| *k == WhiteSpace || k.is_comment())
    }

    /// Open a node that starts at the current token: `PsiBuilder.mark()`.
    pub fn mark(&mut self) -> Marker {
        self.flush_trivia();
        let pos = self.events.len();
        self.events.push(Event::Start { kind: Unknown, forward_parent: None });
        Marker { pos }
    }

    /// Close `marker` as `kind`, ending just before the current token.
    pub fn done(&mut self, marker: Marker, kind: SyntaxKind) {
        if let Event::Start { kind: slot, .. } = &mut self.events[marker.pos] {
            *slot = kind;
        }
        self.events.push(Event::Finish);
    }

    /// Wrap an already-closed node again, starting where it started:
    /// `completed.precede()`.
    pub fn precede(&mut self, marker: Marker) -> Marker {
        let pos = self.events.len();
        self.events.push(Event::Start { kind: Unknown, forward_parent: None });
        if let Event::Start { forward_parent, .. } = &mut self.events[marker.pos] {
            *forward_parent = Some((pos - marker.pos - 1) as u16);
        }
        Marker { pos }
    }

    /// Wrap the current token in an error element and skip it: `error()`.
    /// Green trees carry no error text, so the message only reaches the debug
    /// log — the trade-off every rowan-based PSI port makes.
    pub fn error(&mut self, message: &str) {
        let m = self.mark();
        self.bump();
        self.done(m, ErrorElement);
        let _ = message;
    }

    /// Consume the current token together with the trivia before it.
    pub fn bump(&mut self) {
        for _ in 0..=self.current_idx {
            if let Some((kind, len)) = self.queue.pop_front() {
                self.events.push(Event::Token { kind, len });
                self.front_offset += len;
            }
        }
        self.fill();
        self.skip_trivia();
    }

    pub fn expect(&mut self, kind: SyntaxKind) -> bool {
        if self.at(kind) {
            self.bump();
            true
        } else {
            self.error(&format!("expected {:?}", kind));
            false
        }
    }

    /// Consume a `>` — or the first `>` of a `>>`/`>>>` run that closes
    /// several nested generic lists — keeping the run's remainder queued as
    /// another `>`. The generated Java parser sees already-split `>` tokens;
    /// a rowan parser must split the operator itself, staying lossless.
    pub fn bump_gt(&mut self) -> bool {
        match self.current() {
            Gt => {
                self.bump();
                true
            }
            Shr | Ushr => {
                for _ in 0..self.current_idx {
                    if let Some((kind, len)) = self.queue.pop_front() {
                        self.events.push(Event::Token { kind, len });
                        self.front_offset += len;
                    }
                }
                self.current_idx = 0;
                if let Some(entry) = self.queue.front_mut() {
                    let run_len = entry.1;
                    self.events.push(Event::Token { kind: Gt, len: 1 });
                    self.front_offset += 1;
                    *entry = (Gt, run_len - 1);
                }
                self.fill();
                self.skip_trivia();
                true
            }
            _ => false,
        }
    }

    /// True while the current token can close a generic list.
    pub fn at_gt(&self) -> bool {
        matches!(self.current(), Gt | Shr | Ushr)
    }

    pub fn eat(&mut self, kind: SyntaxKind) -> bool {
        if self.at(kind) {
            self.bump();
            true
        } else {
            false
        }
    }

    fn flush_trivia(&mut self) {
        for _ in 0..self.current_idx {
            if let Some((kind, len)) = self.queue.pop_front() {
                self.events.push(Event::Token { kind, len });
                self.front_offset += len;
            }
        }
        self.fill();
        self.skip_trivia();
    }

    /// Emit everything left — the trailing trivia after the last token, so
    /// the tree covers the whole input. Only the root's Finish is reopened,
    /// so the trivia lands beside the last node, inside the compilation unit.
    fn flush_all(&mut self) {
        let root_finish = matches!(self.events.last(), Some(Event::Finish)).then(|| self.events.pop());
        while let Some((kind, len)) = self.queue.pop_front() {
            self.events.push(Event::Token { kind, len });
            self.front_offset += len;
        }
        if root_finish.is_some() {
            self.events.push(Event::Finish);
        }
    }
}

/// Parse `text` with `grammar` and return the lossless green tree.
pub fn parse_green(text: &str, grammar: fn(&mut Parser<'_>)) -> rowan::GreenNode {
    let mut parser = Parser::new(text);
    grammar(&mut parser);
    parser.flush_all();
    lower(parser.events, text)
}

fn lower(mut events: Vec<Event>, text: &str) -> rowan::GreenNode {
    let bytes = text.as_bytes();
    let mut builder = rowan::GreenNodeBuilder::new();
    let mut pos = 0usize;
    let mut i = 0usize;
    while i < events.len() {
        let event = std::mem::replace(&mut events[i], Event::Tombstone);
        match event {
            Event::Tombstone => {}
            Event::Start { kind, forward_parent } => {
                // A precede() chain: `kind` is the innermost node, the forward
                // parents (started later in the event list) wrap it. Start the
                // outermost first and mark the parents consumed so the main
                // walk skips them.
                let mut kinds = vec![kind];
                let mut fp = forward_parent;
                let mut j = i;
                while let Some(distance) = fp {
                    j += distance as usize + 1;
                    match std::mem::replace(&mut events[j], Event::Tombstone) {
                        Event::Start { kind, forward_parent } => {
                            kinds.push(kind);
                            fp = forward_parent;
                        }
                        _ => panic!("forward parent must point at a Start event"),
                    }
                }
                for kind in kinds.into_iter().rev() {
                    debug_assert_ne!(kind, Unknown, "marker was never done()");
                    builder.start_node(rowan::SyntaxKind(kind as u16));
                }
            }
            Event::Finish => builder.finish_node(),
            Event::Token { kind, len } => {
                let chunk = std::str::from_utf8(&bytes[pos..pos + len]).unwrap_or("");
                builder.token(rowan::SyntaxKind(kind as u16), chunk);
                pos += len;
            }
        }
        i += 1;
    }
    debug_assert_eq!(pos, text.len(), "the green tree must cover the input");
    builder.finish()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn grammar(p: &mut Parser<'_>) {
        let m = p.mark();
        while !p.at_eof() {
            p.bump();
        }
        p.done(m, CompilationUnit);
    }

    #[test]
    fn green_tree_is_lossless() {
        let text = "package a.b; // hi\nclass A {}\n";
        let green = parse_green(text, grammar);
        let node = rowan::SyntaxNode::<crate::kinds::JavaLanguage>::new_root(green);
        assert_eq!(node.text().to_string(), text);
        assert_eq!(node.kind(), CompilationUnit);
    }
}
