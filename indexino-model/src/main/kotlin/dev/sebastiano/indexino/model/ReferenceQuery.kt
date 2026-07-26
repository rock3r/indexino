package dev.sebastiano.indexino.model

public class ReferenceQuery private constructor(public val symbolId: SymbolId) {
    public companion object {
        @JvmStatic public fun to(symbolId: SymbolId): ReferenceQuery = ReferenceQuery(symbolId)
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is ReferenceQuery && symbolId == other.symbolId

    override fun hashCode(): Int = symbolId.hashCode()

    override fun toString(): String = "ReferenceQuery(symbolId=$symbolId)"
}
