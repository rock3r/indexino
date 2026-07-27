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

    override suspend fun run(context: CheckContextV1): List<Finding> =
        context.facts
            .entries("selection-site:", QueryOptions.page(MAX_FINDINGS_PER_RUN))
            .items
            .mapNotNull { entry ->
                val fields =
                    (entry.value as? PluginFactValue.Struct)?.fields ?: return@mapNotNull null
                val inSelection =
                    (fields["inSelectionContainer"] as? PluginFactValue.Bool)?.value ?: false
                val excluded =
                    (fields["excludedByDisableSelection"] as? PluginFactValue.Bool)?.value ?: false
                if (!inSelection || excluded) return@mapNotNull null
                val callee = (fields["callee"] as? PluginFactValue.Text)?.value ?: "<unknown>"
                if (callee !in INTERACTIVE_CALLEES) return@mapNotNull null
                Finding(
                    plugin = PluginId.of("dev.sebastiano.selection-context"),
                    checkId = id,
                    message = "$callee is interactive inside SelectionContainer",
                    range = entry.range,
                    properties = mapOf("factKey" to entry.key, "callee" to callee),
                )
            }
}
