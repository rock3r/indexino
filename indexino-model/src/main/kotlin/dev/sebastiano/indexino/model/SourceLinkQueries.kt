package dev.sebastiano.indexino.model

public class LinkedSourceQuery
private constructor(
    public val symbolName: String,
    public val componentCoordinate: ResolvedComponentCoordinate?,
) {
    public companion object {
        @JvmStatic
        public fun forSymbol(symbolName: String): LinkedSourceQuery {
            require(symbolName.isNotBlank()) { "Linked source symbol name must not be blank" }
            return LinkedSourceQuery(symbolName, null)
        }

        @JvmStatic
        public fun forComponentSymbol(
            symbolName: String,
            componentCoordinate: ResolvedComponentCoordinate,
        ): LinkedSourceQuery {
            require(symbolName.isNotBlank()) { "Linked source symbol name must not be blank" }
            return LinkedSourceQuery(symbolName, componentCoordinate)
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is LinkedSourceQuery &&
                symbolName == other.symbolName &&
                componentCoordinate == other.componentCoordinate

    override fun hashCode(): Int {
        var result = symbolName.hashCode()
        result = 31 * result + (componentCoordinate?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "LinkedSourceQuery(symbolName=$symbolName, componentCoordinate=$componentCoordinate)"
}

public interface SourceLinkQueries {
    public val linkGeneration: LinkGenerationId?

    public suspend fun findLinkedSources(
        query: LinkedSourceQuery,
        options: QueryOptions,
    ): QueryPage<LinkedSourceResult>
}
