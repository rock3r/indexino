package dev.sebastiano.indexino.plugin.api

import dev.sebastiano.indexino.model.BasicFactQueries
import dev.sebastiano.indexino.model.BasicFactSchemaVersion
import dev.sebastiano.indexino.model.CallQuery
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.QueryPage
import dev.sebastiano.indexino.model.ReferenceQuery
import dev.sebastiano.indexino.model.ResourceQuery
import dev.sebastiano.indexino.model.SymbolQuery
import dev.sebastiano.indexino.model.WorkspaceGenerationId

@OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)
internal object UnavailableBasicFactQueries : BasicFactQueries {
    override val generation: WorkspaceGenerationId = WorkspaceGenerationId.of("unavailable")

    override val basicFactSchemaVersion: BasicFactSchemaVersion = BasicFactSchemaVersion.of(1)

    override suspend fun findSymbols(
        query: SymbolQuery,
        options: QueryOptions,
    ): QueryPage<dev.sebastiano.indexino.model.Symbol> = unavailable()

    override suspend fun findReferences(
        query: ReferenceQuery,
        options: QueryOptions,
    ): QueryPage<dev.sebastiano.indexino.model.Reference> = unavailable()

    override suspend fun findCalls(
        query: CallQuery,
        options: QueryOptions,
    ): QueryPage<dev.sebastiano.indexino.model.CallSite> = unavailable()

    override suspend fun findResources(
        query: ResourceQuery,
        options: QueryOptions,
    ): QueryPage<dev.sebastiano.indexino.model.ResourceDefinition> = unavailable()

    override suspend fun findResourceUsages(
        query: ResourceQuery,
        options: QueryOptions,
    ): QueryPage<dev.sebastiano.indexino.model.ResourceUsage> = unavailable()

    private fun unavailable(): Nothing =
        error(
            "PostProcessContextV1.queries is unavailable; construct the context with BasicFactQueries"
        )
}
