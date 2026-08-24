package dev.sebastiano.indexino.plugin.compose.decoration

import dev.sebastiano.indexino.model.CallQuery
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.plugin.api.PostProcessContextV1
import dev.sebastiano.indexino.plugin.api.PostProcessLevelV1
import dev.sebastiano.indexino.plugin.api.PostProcessorV1

@OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)
internal class ModifierChainPostProcessor : PostProcessorV1 {
    override val id: String = "modifier-chains"

    override val level: PostProcessLevelV1 = PostProcessLevelV1.COMPOSITE

    override val readsBasicFactFamilies: Set<String> = setOf("calls")

    override val readsPluginNamespaces: Set<String> = emptySet()

    override suspend fun process(context: PostProcessContextV1) {
        context.ensureActive()
        val callsById =
            mutableMapOf<
                dev.sebastiano.indexino.model.CallSiteId,
                dev.sebastiano.indexino.model.CallSite,
            >()
        var offset = 0
        while (true) {
            val page =
                context.queries.findCalls(
                    CallQuery.any(),
                    QueryOptions.page(limit = 500, offset = offset),
                )
            page.items.forEach { call -> callsById[call.id] = call }
            if (page.items.isEmpty() || !page.hasMore) break
            offset += page.items.size
        }

        val originFilter = context.originId?.value
        val candidateCalls =
            callsById.values.filter { call ->
                originFilter == null || call.range.start.file.originId.value == originFilter
            }
        for (call in candidateCalls) {
            if (
                ModifierChainBuilder.findModifierArgument(call) == null &&
                    !looksLikeComposable(call.calleeName)
            ) {
                continue
            }
            val site = ModifierChainBuilder.buildDecorationSite(call, callsById)
            context.facts.putAt(
                key = "${ComposeDecorationQueries.FACT_PREFIX}${call.id.value}",
                range = site.composableRange,
                value = ComposeDecorationFacts.encode(site),
            )
        }
    }

    private fun looksLikeComposable(calleeName: String): Boolean =
        calleeName.firstOrNull()?.isUpperCase() == true
}
