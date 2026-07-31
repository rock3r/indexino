package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.core.record.PluginFactRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.PluginFactValue
import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.plugin.api.PostProcessContextV1
import dev.sebastiano.indexino.plugin.api.PostProcessLevelV1
import dev.sebastiano.indexino.plugin.api.PostProcessorV1
import dev.sebastiano.indexino.producer.IndexBuildContext
import dev.sebastiano.indexino.producer.IndexedSource
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(IndexinoInternalApi::class)
class PluginAnalyzerRunnerTest {
    @Test
    fun `runs shard post processors with an origin scoped fact sink`() {
        val root = createTempDirectory("plugin-analyzer-")
        val store = XodusCodeIndexStore.open(root.resolve("store"))
        try {
            val pluginId = PluginId.of("dev.example.shard")
            val processor = ShardFactProcessor()
            val registry =
                PluginRegistry(
                    descriptors = emptyMap(),
                    fileAnalyzers = emptyList(),
                    postProcessors =
                        listOf(PluginRegistry.RegisteredPostProcessor(pluginId, processor)),
                    checks = emptyList(),
                )
            val context =
                IndexBuildContext(
                    store = store,
                    commitHash = "commit",
                    sourceFiles = listOf("A.kt", "B.kt"),
                    workspaceRoot = root,
                    sources =
                        listOf(
                            IndexedSource("git:first", root, "A.kt"),
                            IndexedSource("git:second", root, "B.kt"),
                        ),
                )

            PluginAnalyzerRunner(registry).analyze(context, setOf(pluginId.value))

            assertEquals(setOf("git:first", "git:second"), processor.origins)
            assertEquals(
                setOf("git:first", "git:second"),
                store
                    .prefixScan("plugin:${pluginId.value}:")
                    .map { it.second as PluginFactRecord }
                    .map { it.originId }
                    .toSet(),
            )
        } finally {
            store.close()
            root.toFile().deleteRecursively()
        }
    }

    private class ShardFactProcessor : PostProcessorV1 {
        val origins = mutableSetOf<String>()

        override val id: String = "shard-facts"
        override val level: PostProcessLevelV1 = PostProcessLevelV1.SHARD
        override val readsBasicFactFamilies: Set<String> = emptySet()
        override val readsPluginNamespaces: Set<String> = emptySet()

        override suspend fun process(context: PostProcessContextV1) {
            origins += requireNotNull(context.originId).value
            context.facts.put("processed", PluginFactValue.Text.of("yes"))
        }
    }
}
