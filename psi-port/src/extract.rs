//! Declaration extraction over the red tree — the role of accio's extract
//! layer: walk the symbol-aligned tree and produce one record per
//! declaration, `path:line-end kind name parent`, the same shape `accio
//! dump` prints (without signature facts, which this miniature does not
//! record).

use crate::kinds::*;
use crate::parser::parse_green;
use crate::grammar::parse_compilation_unit;
use rowan::NodeOrToken;

#[derive(Debug, Clone, PartialEq)]
pub struct Declaration {
    pub kind: &'static str,
    pub name: String,
    pub parent: String,
    pub start_line: usize,
    pub end_line: usize,
}

/// Parse `text` as Java and list its declarations.
pub fn declarations(text: &str) -> Vec<Declaration> {
    let green = parse_green(text, parse_compilation_unit);
    let root = SyntaxNode::new_root(green);
    let mut out = vec![];
    walk(&root, "", text, &mut out);
    out
}

fn walk(node: &SyntaxNode, parent: &str, text: &str, out: &mut Vec<Declaration>) {
    for child in node.children() {
        match child.kind() {
            Class | EnumClass => {
                if let Some(name) = identifier_text(&child) {
                    let (start, end) = line_range(&child, text);
                    out.push(Declaration {
                        kind: if child.kind() == EnumClass { "enum" } else { "class" },
                        name: name.clone(),
                        parent: parent.to_string(),
                        start_line: start,
                        end_line: end,
                    });
                    walk(&child, &name, text, out);
                }
            }
            Method | Constructor | Field => {
                if let Some(name) = identifier_text(&child) {
                    let (start, end) = line_range(&child, text);
                    out.push(Declaration {
                        kind: match child.kind() {
                            Method => "method",
                            Constructor => "constructor",
                            _ => "field",
                        },
                        name,
                        parent: parent.to_string(),
                        start_line: start,
                        end_line: end,
                    });
                }
                // nested declarations (anonymous classes) — not in this subset
            }
            _ => walk(&child, parent, text, out),
        }
    }
}

/// The declaration's own name identifier: the first free identifier that is
/// not part of the modifier list, annotations or the type. For a method or
/// field that is the identifier between the type and the parameter list; for
/// a class, the identifier after `class`.
fn identifier_text(node: &SyntaxNode) -> Option<String> {
    let mut previous_kind = None;
    for child in node.children_with_tokens() {
        let kind = match &child {
            NodeOrToken::Token(token) => token.kind(),
            NodeOrToken::Node(n) => n.kind(),
        };
        // trivia between the type and the name must not break the chain
        if kind == WhiteSpace || kind.is_comment() {
            continue;
        }
        if kind == Identifier {
            if let NodeOrToken::Token(token) = &child {
                match node.kind() {
                    // `class Name` — the identifier follows the keyword
                    Class | EnumClass => return Some(token.text().to_string()),
                    // `Type name` — the identifier after a TypeElement
                    Method | Field => {
                        if matches!(previous_kind, Some(TypeElement)) {
                            return Some(token.text().to_string());
                        }
                    }
                    // `Name (` — the identifier before the parameter list
                    Constructor => return Some(token.text().to_string()),
                    _ => {}
                }
            }
        }
        previous_kind = Some(kind);
    }
    None
}

/// 1-based inclusive line range of a node.
fn line_range(node: &SyntaxNode, text: &str) -> (usize, usize) {
    let start = text[..node.text_range().start().into()].matches('\n').count() + 1;
    let end = text[..node.text_range().end().into()].matches('\n').count() + 1;
    (start, end)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extracts_classes_and_members() {
        let text = "package com.example;\n\npublic class OrderService {\n    private final PriceCalculator calculator = new PriceCalculator();\n\n    public int total(Order order) {\n        return 1;\n    }\n}\n";
        let decls = declarations(text);
        let summary: Vec<&str> = decls.iter().map(|d| d.kind).collect();
        assert_eq!(summary, vec!["class", "field", "method"]);
        assert_eq!(decls[0].name, "OrderService");
        assert_eq!((decls[0].start_line, decls[0].end_line), (3, 9));
        assert_eq!(decls[1].name, "calculator");
        assert_eq!(decls[1].parent, "OrderService");
        assert_eq!(decls[2].name, "total");
        assert_eq!(decls[2].parent, "OrderService");
    }

    #[test]
    fn constructors_and_generics() {
        let text = "import java.util.List;\nclass A<T extends B> {\n    A(List<T> xs) { }\n    List<List<T>> pairs() { return null; }\n}\n";
        let decls = declarations(text);
        let names: Vec<&str> = decls.iter().map(|d| d.name.as_str()).collect();
        assert_eq!(names, vec!["A", "A", "pairs"]);
        assert_eq!(decls[1].kind, "constructor");
        assert_eq!(decls[2].kind, "method");
    }
}
