package dev.sebastiano.indexino.model

import java.util.Collections

public class Symbol
@IndexinoInternalApi
public constructor(
    public val id: SymbolId,
    public val name: String,
    public val kind: String,
    public val language: String,
    public val location: SourceLocation,
    public val range: SourceRange?,
    public val ownerId: SymbolId?,
    public val signature: String?,
    public val arity: Int?,
    aliases: List<String>,
) {
    public val aliases: List<String> = Collections.unmodifiableList(ArrayList(aliases))

    init {
        require(name.isNotBlank()) { "Symbol name must not be blank" }
        require(kind.isNotBlank()) { "Symbol kind must not be blank" }
        require(language.isNotBlank()) { "Symbol language must not be blank" }
        require(arity == null || arity >= 0) { "Symbol arity must not be negative" }
        require(aliases.none(String::isBlank)) { "Symbol aliases must not contain blank values" }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is Symbol &&
                id == other.id &&
                name == other.name &&
                kind == other.kind &&
                language == other.language &&
                location == other.location &&
                range == other.range &&
                ownerId == other.ownerId &&
                signature == other.signature &&
                arity == other.arity &&
                aliases == other.aliases

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + language.hashCode()
        result = 31 * result + location.hashCode()
        result = 31 * result + (range?.hashCode() ?: 0)
        result = 31 * result + (ownerId?.hashCode() ?: 0)
        result = 31 * result + (signature?.hashCode() ?: 0)
        result = 31 * result + (arity ?: 0)
        result = 31 * result + aliases.hashCode()
        return result
    }

    override fun toString(): String =
        "Symbol(id=$id, name=$name, kind=$kind, language=$language, location=$location, " +
            "range=$range, ownerId=$ownerId, signature=$signature, arity=$arity, aliases=$aliases)"
}
