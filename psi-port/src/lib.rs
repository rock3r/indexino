//! psi-port: a miniature Rust port of the IntelliJ PSI architecture
//! (lexer → PsiBuilder markers → lossless green tree → red tree), with a
//! Java grammar subset and a declaration extractor, the way accio's
//! `psi-syntax` ports the Kotlin, Java and C# parsers.
//!
//! Not a port of the full generated grammars: see the repository notes for
//! what a production port of `PsiParser.java` (IntelliJ), `KotlinParsing`
//! and Roslyn's `LanguageParser` involves.

pub mod extract;
pub mod grammar;
pub mod kinds;
pub mod lexer;
pub mod parser;
