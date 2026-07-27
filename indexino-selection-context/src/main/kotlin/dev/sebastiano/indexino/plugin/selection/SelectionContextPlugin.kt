package dev.sebastiano.indexino.plugin.selection

import dev.sebastiano.indexino.model.BasicFactSchemaVersion
import dev.sebastiano.indexino.model.PluginFactSchemaVersion
import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.plugin.api.IndexinoPluginProvider
import dev.sebastiano.indexino.plugin.api.IndexinoPluginRegistrar
import dev.sebastiano.indexino.plugin.api.PluginDescriptor

@OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)
public class SelectionContextPlugin : IndexinoPluginProvider {
    override fun install(registrar: IndexinoPluginRegistrar): Unit {
        registrar.fileAnalyzer(SelectionContextAnalyzer())
        registrar.check(SelectionContextCheck())
        registrar.plugin(
            PluginDescriptor.of(
                id = PluginId.of("dev.sebastiano.selection-context"),
                version = "1",
                factSchemaVersion = PluginFactSchemaVersion.of(1),
                requiredBasicFactSchema = BasicFactSchemaVersion.of(1),
                producedNamespaces = setOf("dev.sebastiano.selection-context"),
                displayName = "Selection context",
            )
        )
    }
}
