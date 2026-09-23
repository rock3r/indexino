package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.cli.BuildStoreCheckpoint
import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.plugin.StorePluginFactSink
import dev.sebastiano.indexino.core.record.FileHashRecord
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
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking

@OptIn(IndexinoInternalApi::class)
class PluginAnalyzerRunnerTest {
    @Test
    fun `build checkpoint restores basic and plugin facts after post processor failure`() {
        val root = createTempDirectory("plugin-rollback-")
        try {
            XodusCodeIndexStore.open(root.resolve("store")).use { store ->
                val pluginId = PluginId.of("dev.example.rollback")
                val key = CodeIndexKey.file("A.kt", "hash")
                store.put(key, FileHashRecord("A.kt", "before"))
                runBlocking {
                    StorePluginFactSink(store, pluginId.value, "__postprocess__")
                        .put("previous", PluginFactValue.Text.of("old"))
                }
                val before = store.prefixScan("").toMap()
                val failure = IllegalStateException("post processor failed after writing")
                val processor =
                    object : PostProcessorV1 {
                        override val id = "failing"
                        override val level = PostProcessLevelV1.SHARD
                        override val readsBasicFactFamilies: Set<String> = emptySet()
                        override val readsPluginNamespaces: Set<String> = emptySet()

                        override suspend fun process(context: PostProcessContextV1) {
                            context.facts.put("partial", PluginFactValue.Text.of("new"))
                            throw failure
                        }
                    }
                val registry =
                    PluginRegistry(
                        descriptors = emptyMap(),
                        fileAnalyzers = emptyList(),
                        postProcessors =
                            listOf(PluginRegistry.RegisteredPostProcessor(pluginId, processor)),
                        checks = emptyList(),
                    )
                val context =
                    IndexBuildContext.forInlineSources(store, "fixture", mapOf("A.kt" to "class A"))
                val observed =
                    assertFailsWith<IllegalStateException> {
                        BuildStoreCheckpoint(store, root.resolve("manifest.json")).use { checkpoint
                            ->
                            checkpoint.run {
                                store.put(key, FileHashRecord("A.kt", "partial"))
                                PluginAnalyzerRunner(registry)
                                    .analyze(context, setOf(pluginId.value))
                            }
                        }
                    }
                assertSame(failure, observed)
                assertEquals(before, store.prefixScan("").toMap())
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

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
                    resolvedOriginIds = setOf("git:first", "git:second", "git:empty"),
                )

            PluginAnalyzerRunner(registry).analyze(context, setOf(pluginId.value))

            assertEquals(setOf("git:empty", "git:first", "git:second"), processor.origins)
            assertEquals(
                setOf("git:empty", "git:first", "git:second"),
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
