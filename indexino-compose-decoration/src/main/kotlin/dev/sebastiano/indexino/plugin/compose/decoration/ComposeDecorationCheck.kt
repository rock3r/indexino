package dev.sebastiano.indexino.plugin.compose.decoration

import dev.sebastiano.indexino.model.Finding
import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.plugin.api.CheckContextV1
import dev.sebastiano.indexino.plugin.api.IndexinoCheckV1

@OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)
internal class ComposeDecorationCheck : IndexinoCheckV1 {
    override val id: String = "modifier-chain-report"

    override suspend fun run(context: CheckContextV1): List<Finding> {
        val findings = mutableListOf<Finding>()
        var offset = 0
        do {
            val page =
                ComposeDecorationQueries.findSites(
                    context.facts,
                    QueryOptions.page(limit = 500, offset = offset),
                )
            for (site in page.items) {
                if (!site.hasModifierArgument) {
                    findings +=
                        Finding(
                            plugin = PluginId.of("dev.sebastiano.compose-decoration"),
                            checkId = id,
                            message = "${site.composableCalleeName} has no modifier argument",
                            range = site.composableRange,
                            properties =
                                mapOf(
                                    "composableCallId" to site.composableCallId.value,
                                    "chainConfidence" to site.chainConfidence.value,
                                ),
                        )
                } else if (site.chain.links.isNotEmpty()) {
                    val names = site.chain.links.joinToString(" → ") { it.calleeName }
                    findings +=
                        Finding(
                            plugin = PluginId.of("dev.sebastiano.compose-decoration"),
                            checkId = id,
                            message = "${site.composableCalleeName} modifier chain: $names",
                            range = site.modifierArgumentRange ?: site.composableRange,
                            properties =
                                mapOf(
                                    "composableCallId" to site.composableCallId.value,
                                    "chainLength" to site.chain.links.size.toString(),
                                    "chainConfidence" to site.chainConfidence.value,
                                ),
                        )
                }
            }
            offset += page.items.size
            if (page.items.isEmpty()) break
        } while (page.hasMore)
        return findings
    }
}
