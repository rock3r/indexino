package dev.sebastiano.indexino.model

import java.util.Collections

public class Reference
@IndexinoInternalApi
public constructor(
    public val symbolId: SymbolId,
    public val referencedName: String,
    public val language: String,
    public val location: SourceLocation,
    public val qualifier: String?,
    candidateSymbolIds: List<SymbolId>,
    public val arity: Int?,
) {
    public val candidateSymbolIds: List<SymbolId> =
        Collections.unmodifiableList(ArrayList(candidateSymbolIds))

    init {
        require(referencedName.isNotBlank()) { "Referenced name must not be blank" }
        require(language.isNotBlank()) { "Reference language must not be blank" }
        require(arity == null || arity >= 0) { "Reference arity must not be negative" }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is Reference &&
                symbolId == other.symbolId &&
                referencedName == other.referencedName &&
                language == other.language &&
                location == other.location &&
                qualifier == other.qualifier &&
                candidateSymbolIds == other.candidateSymbolIds &&
                arity == other.arity

    override fun hashCode(): Int {
        var result = symbolId.hashCode()
        result = 31 * result + referencedName.hashCode()
        result = 31 * result + language.hashCode()
        result = 31 * result + location.hashCode()
        result = 31 * result + (qualifier?.hashCode() ?: 0)
        result = 31 * result + candidateSymbolIds.hashCode()
        result = 31 * result + (arity ?: 0)
        return result
    }

    override fun toString(): String =
        "Reference(symbolId=$symbolId, referencedName=$referencedName, language=$language, " +
            "location=$location, qualifier=$qualifier, candidateSymbolIds=$candidateSymbolIds, " +
            "arity=$arity)"
}
