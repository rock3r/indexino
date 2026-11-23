//! Syntax kinds, the Rust stand-in for IntelliJ's `IElementType` instances.
//!
//! IntelliJ splits kinds into `IElementType` tokens (from the lexer) and
//! `IElementType`/composite nodes (from the parser). We mirror that with one
//! enum: token kinds first, then composite node kinds, mirroring the naming of
//! `com.intellij.psi.tree.IElementType` constants of the Java PSI
//! (`CLASS`, `METHOD`, `MODIFIER_LIST`, `REFERENCE_EXPRESSION`, ...).

use rowan::SyntaxKind as RowanKind;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
#[repr(u16)]
pub enum SyntaxKind {
    // ---- tokens (lexer output) ----
    WhiteSpace,
    LineComment,
    BlockComment,
    DocComment,
    Identifier,
    IntegerLiteral,
    FloatingPointLiteral,
    StringLiteral,
    CharLiteral,
    // literals/keywords carry their own kind in IntelliJ's Java PSI
    TrueKeyword,
    FalseKeyword,
    NullKeyword,
    IntKeyword,
    LongKeyword,
    ShortKeyword,
    ByteKeyword,
    CharKeyword,
    FloatKeyword,
    DoubleKeyword,
    BooleanKeyword,
    VoidKeyword,
    PublicKeyword,
    PrivateKeyword,
    ProtectedKeyword,
    StaticKeyword,
    FinalKeyword,
    AbstractKeyword,
    NativeKeyword,
    SynchronizedKeyword,
    TransientKeyword,
    VolatileKeyword,
    StrictfpKeyword,
    ClassKeyword,
    InterfaceKeyword,
    EnumKeyword,
    RecordKeyword,
    ExtendsKeyword,
    ImplementsKeyword,
    NewKeyword,
    ThisKeyword,
    SuperKeyword,
    InstanceofKeyword,
    ReturnKeyword,
    IfKeyword,
    ElseKeyword,
    ForKeyword,
    WhileKeyword,
    DoKeyword,
    SwitchKeyword,
    CaseKeyword,
    DefaultKeyword,
    BreakKeyword,
    ContinueKeyword,
    ThrowKeyword,
    ThrowsKeyword,
    TryKeyword,
    CatchKeyword,
    FinallyKeyword,
    AssertKeyword,
    PackageKeyword,
    ImportKeyword,
    // punctuation and operators (subset that a Java lexer must know)
    Lpar,
    Rpar,
    Lbrace,
    Rbrace,
    Lbracket,
    Rbracket,
    Semicolon,
    Comma,
    Dot,
    Ellipsis,
    At,
    Colon,
    ColonColon,
    Arrow,
    Eq,
    Gt,
    Lt,
    Not,
    Tilde,
    Question,
    AmpAmp,
    OrOr,
    Plus,
    Minus,
    Star,
    Slash,
    Percent,
    Amp,
    Or,
    Caret,
    Shl,
    Shr,
    Ushr,
    EqEq,
    NotEq,
    Le,
    Ge,
    PlusEq,
    MinusEq,
    StarEq,
    SlashEq,
    PercentEq,
    AmpEq,
    OrEq,
    CaretEq,
    ShlEq,
    ShrEq,
    UshrEq,
    PlusPlus,
    MinusMinus,
    Unknown,

    // ---- composite nodes (parser output) ----
    CompilationUnit,
    PackageStatement,
    ReferenceElement, // dotted name in package/extends/implements/import
    ImportList,
    ImportStatement,
    ModifierList,
    Annotation,
    AnnotationParameterList,
    Class,     // class or interface declaration (`CLASS` in IntelliJ terms)
    EnumClass, // parsed shallowly
    Field,
    Method,
    Constructor,
    ParameterList,
    Parameter,
    TypeParameterList,
    TypeArgumentList,
    ExtendsList,
    ImplementsList,
    TypeElement, // a type reference in a declaration
    ArrayDimension,
    BlockStatement,
    CodeBlock,
    LocalVariable,
    IfStatement,
    WhileStatement,
    ForStatement,
    ReturnStatement,
    ThrowStatement,
    TryStatement,
    SwitchStatement,
    ExpressionStatement,
    ExpressionList,
    ReferenceExpression,
    LiteralExpression,
    MethodCallExpression, // in IntelliJ: METHOD_CALL_EXPRESSION wrapping a ref + arg list
    ArgumentList,
    NewExpression,
    BinaryExpression,
    UnaryExpression, // prefix or postfix
    AssignmentExpression,
    ConditionalExpression, // ternary
    ParenthesizedExpression,
    ArrayAccessExpression,
    FieldAccessExpression, // a.b where a is not resolved by the parser
    CastExpression,
    InstanceOfExpression,
    ErrorElement,

    /// Sentinel: the number of variants, for bounds checks.
    #[doc(hidden)]
    __Last,
}

pub use SyntaxKind::*;

impl SyntaxKind {
    pub fn is_token(self) -> bool {
        self <= Unknown
    }

    pub fn is_comment(self) -> bool {
        matches!(self, LineComment | BlockComment | DocComment)
    }

    pub fn keyword_of(word: &str) -> Option<SyntaxKind> {
        Some(match word {
            "true" => TrueKeyword,
            "false" => FalseKeyword,
            "null" => NullKeyword,
            "int" => IntKeyword,
            "long" => LongKeyword,
            "short" => ShortKeyword,
            "byte" => ByteKeyword,
            "char" => CharKeyword,
            "float" => FloatKeyword,
            "double" => DoubleKeyword,
            "boolean" => BooleanKeyword,
            "void" => VoidKeyword,
            "public" => PublicKeyword,
            "private" => PrivateKeyword,
            "protected" => ProtectedKeyword,
            "static" => StaticKeyword,
            "final" => FinalKeyword,
            "abstract" => AbstractKeyword,
            "native" => NativeKeyword,
            "synchronized" => SynchronizedKeyword,
            "transient" => TransientKeyword,
            "volatile" => VolatileKeyword,
            "strictfp" => StrictfpKeyword,
            "class" => ClassKeyword,
            "interface" => InterfaceKeyword,
            "enum" => EnumKeyword,
            "record" => RecordKeyword,
            "extends" => ExtendsKeyword,
            "implements" => ImplementsKeyword,
            "new" => NewKeyword,
            "this" => ThisKeyword,
            "super" => SuperKeyword,
            "instanceof" => InstanceofKeyword,
            "return" => ReturnKeyword,
            "if" => IfKeyword,
            "else" => ElseKeyword,
            "for" => ForKeyword,
            "while" => WhileKeyword,
            "do" => DoKeyword,
            "switch" => SwitchKeyword,
            "case" => CaseKeyword,
            "default" => DefaultKeyword,
            "break" => BreakKeyword,
            "continue" => ContinueKeyword,
            "throw" => ThrowKeyword,
            "throws" => ThrowsKeyword,
            "try" => TryKeyword,
            "catch" => CatchKeyword,
            "finally" => FinallyKeyword,
            "assert" => AssertKeyword,
            "package" => PackageKeyword,
            "import" => ImportKeyword,
            _ => return None,
        })
    }
}

/// `rowan::Language` glue: the red-tree view of the green tree.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct JavaLanguage;

impl rowan::Language for JavaLanguage {
    type Kind = SyntaxKind;
    fn kind_from_raw(raw: RowanKind) -> SyntaxKind {
        assert!(raw.0 < __Last as u16, "unknown raw syntax kind: {}", raw.0);
        unsafe { std::mem::transmute::<u16, SyntaxKind>(raw.0) }
    }
    fn kind_to_raw(kind: SyntaxKind) -> RowanKind {
        RowanKind(kind as u16)
    }
}

pub type SyntaxNode = rowan::SyntaxNode<JavaLanguage>;
pub type SyntaxToken = rowan::SyntaxToken<JavaLanguage>;
