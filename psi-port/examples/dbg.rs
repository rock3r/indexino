fn main() {
    let text = std::env::args().nth(1).unwrap_or_else(|| "class A { int x = 1; }".into());
    let green = psi_port::parser::parse_green(&text, psi_port::grammar::parse_compilation_unit);
    let root = rowan::SyntaxNode::<psi_port::kinds::JavaLanguage>::new_root(green);
    fn dump(node: &psi_port::kinds::SyntaxNode, depth: usize) {
        println!("{}{:?}", "  ".repeat(depth), node.kind());
        for child in node.children_with_tokens() {
            match child {
                rowan::NodeOrToken::Node(n) => dump(&n, depth + 1),
                rowan::NodeOrToken::Token(t) => println!("{}{:?} {:?}", "  ".repeat(depth + 1), t.kind(), t.text()),
            }
        }
    }
    dump(&root, 0);
}
