package dev.sebastiano.indexino.plugin.selection

import dev.sebastiano.indexino.model.Finding
import dev.sebastiano.indexino.model.PluginFactValue
import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.plugin.api.CheckContextV1
import dev.sebastiano.indexino.plugin.api.IndexinoCheckV1

@OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)
internal class SelectionContextCheck : IndexinoCheckV1 {
    override val id: String = "interactive-in-selection"

    private companion object {
        const val MAX_FINDINGS_PER_RUN: Int = 10_000
        val INTERACTIVE_CALLEES: Set<String> =
            setOf(
                "ActionButton",
                "IconButton",
                "TextButton",
                "OutlinedButton",
                "FloatingActionButton",
                "IconToggleButton",
                "Checkbox",
                "Switch",
                "RadioButton",
                "Slider",
            )
    }

    override suspend fun run(context: CheckContextV1): List<Finding> {
        val findings = mutableListOf<Finding>()
        var offset = 0
        do {
            val page =
                context.facts.entries(
                    "selection-site:",
                    QueryOptions.page(MAX_FINDINGS_PER_RUN, offset),
                )
            findings +=
                page.items.flatMap { entry ->
                    val fields =
                        (entry.value as? PluginFactValue.Struct)?.fields
                            ?: return@flatMap emptyList()
                    val inSelection =
                        (fields["inSelectionContainer"] as? PluginFactValue.Bool)?.value ?: false
                    val excluded =
                        (fields["excludedByDisableSelection"] as? PluginFactValue.Bool)?.value
                            ?: false
                    if (!inSelection) return@flatMap emptyList()
                    val callee = (fields["callee"] as? PluginFactValue.Text)?.value ?: "<unknown>"
                    val findings = mutableListOf<Finding>()
                    val selectionContainerCount =
                        (fields["selectionContainerCount"] as? PluginFactValue.Integer)?.value ?: 0
                    if (selectionContainerCount > 1) {
                        findings +=
                            Finding(
                                plugin = PluginId.of("dev.sebastiano.selection-context"),
                                checkId = id,
                                message = "$callee is inside nested SelectionContainers",
                                range = entry.range,
                                properties =
                                    mapOf(
                                        "factKey" to entry.key,
                                        "callee" to callee,
                                        "selectionContainerCount" to
                                            selectionContainerCount.toString(),
                                    ),
                            )
                    }
                    if (!excluded && callee in INTERACTIVE_CALLEES) {
                        findings +=
                            Finding(
                                plugin = PluginId.of("dev.sebastiano.selection-context"),
                                checkId = id,
                                message = "$callee is interactive inside SelectionContainer",
                                range = entry.range,
                                properties = mapOf("factKey" to entry.key, "callee" to callee),
                            )
                    }
                    findings
                }
            offset += page.items.size
            if (page.items.isEmpty()) break
        } while (page.hasMore)
        return findings
    }
}
