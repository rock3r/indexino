package dev.sebastiano.indexino.model

public class SymbolQuery
private constructor(
    public val name: String?,
    public val file: SourceFile?,
    public val kind: String?,
    public val language: String?,
    public val match: NameMatchMode,
) {
    public companion object {
        @JvmStatic
        public fun named(name: String): SymbolQuery {
            require(name.isNotBlank()) { "Symbol query name must not be blank" }
            return SymbolQuery(name, null, null, null, NameMatchMode.EXACT)
        }

        @JvmStatic
        public fun inFile(file: SourceFile): SymbolQuery =
            SymbolQuery(null, file, null, null, NameMatchMode.EXACT)
    }

    public fun withKind(kind: String): SymbolQuery {
        require(kind.isNotBlank()) { "Symbol kind must not be blank" }
        return SymbolQuery(name, file, kind, language, match)
    }

    public fun withLanguage(language: String): SymbolQuery {
        require(language.isNotBlank()) { "Symbol language must not be blank" }
        return SymbolQuery(name, file, kind, language, match)
    }

    public fun withMatch(mode: NameMatchMode): SymbolQuery =
        SymbolQuery(name, file, kind, language, mode)

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SymbolQuery &&
                name == other.name &&
                file == other.file &&
                kind == other.kind &&
                language == other.language &&
                match == other.match

    override fun hashCode(): Int {
        var result = name?.hashCode() ?: 0
        result = 31 * result + (file?.hashCode() ?: 0)
        result = 31 * result + (kind?.hashCode() ?: 0)
        result = 31 * result + (language?.hashCode() ?: 0)
        result = 31 * result + match.hashCode()
        return result
    }

    override fun toString(): String =
        "SymbolQuery(name=$name, file=$file, kind=$kind, language=$language, match=$match)"
}
