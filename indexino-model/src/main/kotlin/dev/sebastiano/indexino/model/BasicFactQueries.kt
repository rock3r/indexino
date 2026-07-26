package dev.sebastiano.indexino.model

public interface BasicFactQueries {
    public val generation: WorkspaceGenerationId
    public val basicFactSchemaVersion: BasicFactSchemaVersion

    public suspend fun findSymbols(query: SymbolQuery, options: QueryOptions): QueryPage<Symbol>

    public suspend fun findReferences(
        query: ReferenceQuery,
        options: QueryOptions,
    ): QueryPage<Reference>

    public suspend fun findCalls(query: CallQuery, options: QueryOptions): QueryPage<CallSite>
}
