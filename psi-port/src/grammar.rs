//! A Java grammar subset with the shape of IntelliJ's generated `PsiParser`
//! for `java-syntax` (`parseCompilationUnit`, `parseTypeDeclaration`,
//! `parseClassMember`, ...). It covers the constructs an extraction layer
//! needs: packages, imports, modifiers and annotations, classes, interfaces
//! and enums with members, methods, constructors, fields, parameters, and
//! statement/expression structure deep enough to walk method bodies. Where a
//! construct is out of the subset, error elements keep the parse going, as
//! IntelliJ's error recovery does.

use crate::kinds::*;
use crate::parser::{Marker, Parser};

pub fn parse_compilation_unit(p: &mut Parser<'_>) {
    let m = p.mark();
    if p.at(PackageKeyword) {
        let package = p.mark();
        p.bump();
        reference(p);
        p.expect(Semicolon);
        p.done(package, PackageStatement);
    }
    if p.at(ImportKeyword) {
        let list = p.mark();
        while p.at(ImportKeyword) {
            let import = p.mark();
            p.bump();
            p.eat(StaticKeyword);
            reference(p);
            p.expect(Semicolon);
            p.done(import, ImportStatement);
        }
        p.done(list, ImportList);
    }
    while !p.at_eof() {
        if p.at(Semicolon) {
            p.bump();
            continue;
        }
        type_declaration(p);
    }
    p.done(m, CompilationUnit);
}

fn is_modifier(kind: SyntaxKind) -> bool {
    matches!(
        kind,
        PublicKeyword
            | PrivateKeyword
            | ProtectedKeyword
            | StaticKeyword
            | FinalKeyword
            | AbstractKeyword
            | NativeKeyword
            | SynchronizedKeyword
            | TransientKeyword
            | VolatileKeyword
            | StrictfpKeyword
    )
}

fn modifier_list(p: &mut Parser<'_>) {
    let m = p.mark();
    loop {
        if is_modifier(p.current()) {
            p.bump();
        } else if p.at(At) {
            let annotation = p.mark();
            p.bump();
            name_reference(p);
            if p.at(Lpar) {
                let args = p.mark();
                p.bump();
                argument_expressions(p);
                p.expect(Rpar);
                p.done(args, AnnotationParameterList);
            }
            p.done(annotation, Annotation);
        } else {
            break;
        }
    }
    p.done(m, ModifierList);
}

fn type_declaration(p: &mut Parser<'_>) {
    let m = p.mark();
    modifier_list(p);
    type_declaration_body(p, m);
}

fn type_declaration_body(p: &mut Parser<'_>, m: Marker) {
    if !matches!(p.current(), ClassKeyword | InterfaceKeyword | EnumKeyword) {
        p.error("expected a type declaration");
        p.done(m, ErrorElement);
        return;
    }
    class_body(p);
    p.done(m, Class);
}

fn class_body(p: &mut Parser<'_>) {
    p.bump(); // class / interface / enum
    if p.at(Identifier) {
        p.bump();
    }
    if p.at(Lt) {
        type_parameter_list(p);
    }
    if p.at(ExtendsKeyword) {
        let m = p.mark();
        p.bump();
        type_element(p);
        while p.eat(Comma) {
            type_element(p);
        }
        p.done(m, ExtendsList);
    }
    if p.at(ImplementsKeyword) {
        let m = p.mark();
        p.bump();
        type_element(p);
        while p.eat(Comma) {
            type_element(p);
        }
        p.done(m, ImplementsList);
    }
    p.expect(Lbrace);
    while !p.at(Rbrace) && !p.at_eof() {
        if p.eat(Semicolon) {
            continue;
        }
        member(p);
    }
    p.expect(Rbrace);
}

fn member(p: &mut Parser<'_>) {
    let m = p.mark();
    modifier_list(p);
    if matches!(p.current(), ClassKeyword | InterfaceKeyword | EnumKeyword) {
        type_declaration_body(p, m);
        return;
    }
    if p.at(Lt) {
        type_parameter_list(p);
    }
    // A constructor declares no return type: at this point only `Name (` can
    // start one (every method has a type first).
    if p.at(Identifier) && p.nth(1) == Lpar {
        p.bump();
        parameter_list(p);
        throws_clause(p);
        body_or_semicolon(p);
        p.done(m, Constructor);
        return;
    }
    type_element(p);
    if !p.at(Identifier) {
        p.error("expected a member name");
        p.done(m, ErrorElement);
        return;
    }
    p.bump(); // member name
    if p.at(Lpar) {
        parameter_list(p);
        throws_clause(p);
        body_or_semicolon(p);
        p.done(m, Method);
        return;
    }
    // field declarators: `Type name [= expr] (, name [= expr])* ;`
    loop {
        if p.eat(Eq) {
            expression(p);
        }
        if p.eat(Comma) {
            p.bump(); // next declarator's name
            continue;
        }
        break;
    }
    p.expect(Semicolon);
    p.done(m, Field);
}

fn body_or_semicolon(p: &mut Parser<'_>) {
    if p.at(Lbrace) {
        code_block(p);
    } else {
        p.expect(Semicolon);
    }
}

fn type_parameter_list(p: &mut Parser<'_>) {
    let m = p.mark();
    p.bump(); // <
    while !p.at_gt() && !p.at_eof() {
        if p.at(Identifier) {
            p.bump();
            if p.eat(ExtendsKeyword) {
                type_element(p);
                while p.eat(Amp) {
                    type_element(p);
                }
            }
        } else {
            p.error("expected a type parameter");
        }
        if !p.eat(Comma) && !p.at_gt() {
            p.error("expected , or >");
        }
    }
    if !p.bump_gt() {
        p.error("expected >");
    }
    p.done(m, TypeParameterList);
}

fn throws_clause(p: &mut Parser<'_>) {
    if p.at(ThrowsKeyword) {
        let m = p.mark();
        p.bump();
        type_element(p);
        while p.eat(Comma) {
            type_element(p);
        }
        p.done(m, ExpressionList);
    }
}

fn parameter_list(p: &mut Parser<'_>) {
    let m = p.mark();
    p.bump(); // (
    while !p.at(Rpar) && !p.at_eof() {
        let param = p.mark();
        modifier_list(p); // `final`, annotations
        type_element(p);
        p.eat(Ellipsis);
        if p.at(Identifier) {
            p.bump();
        } else {
            p.error("expected a parameter name");
        }
        p.done(param, Parameter);
        if !p.eat(Comma) && !p.at(Rpar) {
            p.error("expected , or )");
        }
    }
    p.expect(Rpar);
    p.done(m, ParameterList);
}

/// A type reference: a dotted name with optional type arguments and array
/// dimensions — `java.util.List<String>[]`.
fn type_element(p: &mut Parser<'_>) {
    let m = p.mark();
    match p.current() {
        IntKeyword | LongKeyword | ShortKeyword | ByteKeyword | CharKeyword
        | FloatKeyword | DoubleKeyword | BooleanKeyword | VoidKeyword => p.bump(),
        Identifier => {
            name_reference(p);
            if p.at(Lt) {
                type_argument_list(p);
            }
            while p.at(Dot) && p.nth(1) == Identifier {
                p.bump();
                name_reference(p);
                if p.at(Lt) {
                    type_argument_list(p);
                }
            }
        }
        _ => {
            p.error("expected a type");
            p.done(m, ErrorElement);
            return;
        }
    }
    while p.at(Lbracket) && p.nth(1) == Rbracket {
        let dim = p.mark();
        p.bump();
        p.bump();
        p.done(dim, ArrayDimension);
    }
    p.done(m, TypeElement);
}

fn type_argument_list(p: &mut Parser<'_>) {
    let m = p.mark();
    p.bump(); // <
    while !p.at_gt() && !p.at_eof() {
        if p.eat(Question) {
            if p.eat(ExtendsKeyword) || p.eat(SuperKeyword) {
                type_element(p);
            }
        } else {
            type_element(p);
        }
        if !p.eat(Comma) && !p.at_gt() {
            break;
        }
    }
    if !p.bump_gt() {
        p.error("expected >");
    }
    p.done(m, TypeArgumentList);
}

/// A dotted name (packages, imports, annotations, simple references).
fn reference(p: &mut Parser<'_>) {
    let m = p.mark();
    name_reference(p);
    while p.at(Dot) && matches!(p.nth(1), Identifier | Star) {
        p.bump();
        if p.at(Identifier) {
            name_reference(p);
        } else {
            p.bump(); // the star of `import a.b.*;`
        }
    }
    p.done(m, ReferenceElement);
}

/// A reference with its own node; returns the marker so callers can wrap it.
fn name_reference(p: &mut Parser<'_>) -> Marker {
    let m = p.mark();
    if p.at(Identifier) {
        p.bump();
    } else {
        p.error("expected an identifier");
    }
    p.done(m, ReferenceExpression);
    m
}

fn code_block(p: &mut Parser<'_>) {
    let m = p.mark();
    p.bump(); // {
    while !p.at(Rbrace) && !p.at_eof() {
        block_statement(p);
    }
    p.expect(Rbrace);
    p.done(m, CodeBlock);
}

fn block_statement(p: &mut Parser<'_>) {
    let m = p.mark();
    match p.current() {
        Lbrace => code_block_with_marker(p, m),
        IfKeyword => {
            p.bump();
            parens_expression(p);
            block_statement(p);
            if p.eat(ElseKeyword) {
                block_statement(p);
            }
            p.done(m, IfStatement);
        }
        WhileKeyword => {
            p.bump();
            parens_expression(p);
            block_statement(p);
            p.done(m, WhileStatement);
        }
        ForKeyword => {
            p.bump();
            let parens = p.mark();
            p.expect(Lpar);
            while !p.at(Rpar) && !p.at_eof() {
                p.bump();
            }
            p.expect(Rpar);
            p.done(parens, ExpressionList);
            block_statement(p);
            p.done(m, ForStatement);
        }
        DoKeyword => {
            p.bump();
            block_statement(p);
            p.expect(WhileKeyword);
            parens_expression(p);
            p.expect(Semicolon);
            p.done(m, WhileStatement);
        }
        SwitchKeyword => {
            p.bump();
            parens_expression(p);
            p.expect(Lbrace);
            while !p.at(Rbrace) && !p.at_eof() {
                block_statement(p);
            }
            p.expect(Rbrace);
            p.done(m, SwitchStatement);
        }
        TryKeyword => {
            p.bump();
            if p.at(Lpar) {
                parens_expression(p); // try-with-resources, parsed shallowly
            }
            code_block(p);
            while p.at(CatchKeyword) || p.at(FinallyKeyword) {
                if p.eat(CatchKeyword) {
                    parens_expression(p);
                    code_block(p);
                } else {
                    p.bump();
                    code_block(p);
                }
            }
            p.done(m, TryStatement);
        }
        ReturnKeyword | ThrowKeyword => {
            let kind = if p.at(ReturnKeyword) { ReturnStatement } else { ThrowStatement };
            p.bump();
            if !p.at(Semicolon) {
                expression(p);
            }
            p.expect(Semicolon);
            p.done(m, kind);
        }
        BreakKeyword | ContinueKeyword => {
            p.bump();
            p.eat(Identifier); // label
            p.expect(Semicolon);
            p.done(m, ExpressionStatement);
        }
        Semicolon => {
            p.bump();
            p.done(m, ExpressionStatement);
        }
        _ => {
            if looks_like_local_variable(p) {
                type_element(p);
                p.bump(); // name
                loop {
                    if p.eat(Eq) {
                        expression(p);
                    }
                    if p.eat(Comma) {
                        p.bump(); // extra declarator name
                        continue;
                    }
                    break;
                }
                p.expect(Semicolon);
                p.done(m, LocalVariable);
            } else {
                expression(p);
                p.expect(Semicolon);
                p.done(m, ExpressionStatement);
            }
        }
    }
}

fn code_block_with_marker(p: &mut Parser<'_>, m: Marker) {
    p.bump(); // {
    while !p.at(Rbrace) && !p.at_eof() {
        block_statement(p);
    }
    p.expect(Rbrace);
    p.done(m, CodeBlock);
}

/// `Type name` (with the type possibly generic, dotted or array) starts a
/// local variable declaration; so does a primitive type before a name.
fn looks_like_local_variable(p: &Parser<'_>) -> bool {
    if matches!(
        p.current(),
        IntKeyword | LongKeyword | ShortKeyword | ByteKeyword | CharKeyword
            | FloatKeyword | DoubleKeyword | BooleanKeyword
    ) {
        return p.nth(1) == Identifier;
    }
    if !p.at(Identifier) {
        return false;
    }
    if p.nth(1) == Identifier {
        return true;
    }
    let mut n = 1;
    if p.nth(n) == Lt {
        let mut depth: i32 = 0;
        loop {
            match p.nth(n) {
                Lt => depth += 1,
                Gt => depth -= 1,
                Shr => depth -= 2,
                Ushr => depth -= 3,
                Unknown => return false,
                _ => {}
            }
            if depth <= 0 {
                n += 1;
                break;
            }
            n += 1;
            if n > 16 {
                return false;
            }
        }
    }
    while p.nth(n) == Dot && p.nth(n + 1) == Identifier {
        n += 2;
    }
    while p.nth(n) == Lbracket && p.nth(n + 1) == Rbracket {
        n += 2;
    }
    p.nth(n) == Identifier
}

fn parens_expression(p: &mut Parser<'_>) {
    p.expect(Lpar);
    if !p.at(Rpar) {
        expression(p);
    }
    p.expect(Rpar);
}

fn argument_expressions(p: &mut Parser<'_>) {
    let m = p.mark();
    if !p.at(Rpar) {
        expression(p);
        while p.eat(Comma) {
            expression(p);
        }
    }
    p.done(m, ExpressionList);
}

/// Operator precedence climbing, from assignment/ternary (lowest) to
/// multiplicative. Returns the marker of the expression's node so callers
/// and the binary loop can wrap it with `precede`.
fn expression(p: &mut Parser<'_>) {
    expression_prec(p, 1);
}

fn binary_binding(kind: SyntaxKind) -> u8 {
    match kind {
        OrOr => 3,
        AmpAmp => 4,
        Or => 5,
        Caret => 6,
        Amp => 7,
        EqEq | NotEq => 8,
        Lt | Gt | Le | Ge | InstanceofKeyword => 9,
        Shl | Shr | Ushr => 10,
        Plus | Minus => 11,
        Star | Slash | Percent => 12,
        _ => 0,
    }
}

fn is_assignment(kind: SyntaxKind) -> bool {
    matches!(
        kind,
        Eq | PlusEq | MinusEq | StarEq | SlashEq | PercentEq | AmpEq | OrEq | CaretEq | ShlEq
            | ShrEq | UshrEq
    )
}

fn expression_prec(p: &mut Parser<'_>, min: u8) {
    let mut lhs = unary_expression(p);
    loop {
        if is_assignment(p.current()) {
            if min > 1 {
                break;
            }
            let m = p.precede(lhs);
            p.bump(); // assignment operator
            expression_prec(p, 1); // right-associative
            lhs = m;
            p.done(m, AssignmentExpression);
            continue;
        }
        if p.at(Question) {
            if min > 2 {
                break;
            }
            let m = p.precede(lhs);
            p.bump();
            expression_prec(p, 3);
            p.expect(Colon);
            expression_prec(p, 3);
            lhs = m;
            p.done(m, ConditionalExpression);
            continue;
        }
        let op = p.current();
        let binding = binary_binding(op);
        if binding == 0 || binding < min {
            break;
        }
        let m = p.precede(lhs);
        p.bump();
        if op == InstanceofKeyword {
            type_element(p);
        } else {
            expression_prec(p, binding + 1);
        }
        lhs = m;
        p.done(m, if op == InstanceofKeyword { InstanceOfExpression } else { BinaryExpression });
    }
}

fn unary_expression(p: &mut Parser<'_>) -> Marker {
    match p.current() {
        Plus | Minus | Not | Tilde | PlusPlus | MinusMinus => {
            let m = p.mark();
            p.bump();
            unary_expression(p);
            p.done(m, UnaryExpression);
            postfix_after(p, m)
        }
        Lpar if cast_lookahead(p) => {
            let m = p.mark();
            p.bump(); // (
            type_element(p);
            p.expect(Rpar);
            unary_expression(p);
            p.done(m, CastExpression);
            postfix_after(p, m)
        }
        _ => {
            let m = primary(p);
            if matches!(p.current(), PlusPlus | MinusMinus) {
                let wrap = p.precede(m);
                p.bump();
                p.done(wrap, UnaryExpression);
                wrap
            } else {
                postfix_after(p, m)
            }
        }
    }
}

/// `( Name )` (with a possibly dotted or generic name) starts a cast in every
/// construct this grammar meets; a parenthesized expression otherwise.
fn cast_lookahead(p: &Parser<'_>) -> bool {
    if matches!(
        p.nth(1),
        IntKeyword | LongKeyword | ShortKeyword | ByteKeyword | CharKeyword | FloatKeyword
            | DoubleKeyword | BooleanKeyword
    ) && p.nth(2) == Rpar
    {
        return true;
    }
    p.nth(1) == Identifier && p.nth(2) == Rpar
}

fn primary(p: &mut Parser<'_>) -> Marker {
    match p.current() {
        IntegerLiteral | FloatingPointLiteral | StringLiteral | CharLiteral | TrueKeyword
        | FalseKeyword | NullKeyword => {
            let m = p.mark();
            p.bump();
            p.done(m, LiteralExpression);
            m
        }
        ThisKeyword | SuperKeyword => {
            let m = p.mark();
            p.bump();
            p.done(m, ReferenceExpression);
            m
        }
        NewKeyword => {
            let m = p.mark();
            p.bump();
            type_element(p);
            if p.at(Lpar) {
                p.bump();
                argument_expressions(p);
                p.expect(Rpar);
                if p.at(Lbrace) {
                    code_block(p); // anonymous class body
                }
            } else if p.at(Lbracket) {
                p.bump();
                expression(p);
                p.expect(Rbracket);
                while p.at(Lbracket) && p.nth(1) == Rbracket {
                    p.bump();
                    p.bump();
                }
            }
            p.done(m, NewExpression);
            m
        }
        Lpar => {
            let m = p.mark();
            p.bump();
            expression(p);
            p.expect(Rpar);
            p.done(m, ParenthesizedExpression);
            m
        }
        Identifier if p.nth(1) == Lpar => {
            let m = p.mark();
            name_reference(p);
            p.bump(); // (
            argument_expressions(p);
            p.expect(Rpar);
            p.done(m, MethodCallExpression);
            m
        }
        Identifier => name_reference(p),
        _ => {
            let m = p.mark();
            p.error("expected an expression");
            p.done(m, ErrorElement);
            m
        }
    }
}

/// Consume `.member`, `(args)`, `[index]`, `::method` suffixes. They attach
/// to the open node rather than getting wrapper nodes of their own — the one
/// simplification this miniature allows itself inside expressions.
fn postfix_after(p: &mut Parser<'_>, m: Marker) -> Marker {
    loop {
        match p.current() {
            Dot => {
                p.bump();
                match p.current() {
                    Identifier if p.nth(1) == Lpar => {
                        name_reference(p);
                        p.bump();
                        argument_expressions(p);
                        p.expect(Rpar);
                    }
                    Identifier | ThisKeyword | SuperKeyword => {
                        name_reference(p);
                    }
                    NewKeyword => {
                        p.bump();
                        type_element(p);
                        if p.at(Lpar) {
                            p.bump();
                            argument_expressions(p);
                            p.expect(Rpar);
                        }
                    }
                    _ => p.error("expected a member name"),
                }
            }
            Lpar => {
                p.bump();
                argument_expressions(p);
                p.expect(Rpar);
            }
            Lbracket => {
                p.bump();
                expression(p);
                p.expect(Rbracket);
            }
            ColonColon => {
                p.bump();
                if p.at(NewKeyword) {
                    p.bump();
                } else if p.at(Identifier) {
                    name_reference(p);
                } else {
                    p.error("expected a method reference");
                }
            }
            _ => break,
        }
    }
    m
}
