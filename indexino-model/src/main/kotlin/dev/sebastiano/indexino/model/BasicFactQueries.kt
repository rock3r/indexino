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

    public suspend fun findResources(
        query: ResourceQuery,
        options: QueryOptions,
    ): QueryPage<ResourceDefinition>

    public suspend fun findResourceUsages(
        query: ResourceQuery,
        options: QueryOptions,
    ): QueryPage<ResourceUsage>
}
