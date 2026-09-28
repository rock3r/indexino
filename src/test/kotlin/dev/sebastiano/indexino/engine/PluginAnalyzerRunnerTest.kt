package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.cli.BuildStoreCheckpoint
import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.plugin.PluginFactValueCodec
import dev.sebastiano.indexino.core.plugin.StorePluginFactSink
import dev.sebastiano.indexino.core.record.FileHashRecord
import dev.sebastiano.indexino.core.record.PluginFactRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.PluginFactValue
import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.plugin.api.FileAnalysisContextV1
import dev.sebastiano.indexino.plugin.api.FileAnalyzerV1
import dev.sebastiano.indexino.plugin.api.PostProcessContextV1
import dev.sebastiano.indexino.plugin.api.PostProcessLevelV1
import dev.sebastiano.indexino.plugin.api.PostProcessorV1
import dev.sebastiano.indexino.producer.IndexBuildContext
import dev.sebastiano.indexino.producer.IndexedSource
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking

@OptIn(IndexinoInternalApi::class)
class PluginAnalyzerRunnerTest {
    @Test
    fun `file analyzers replace only changed facts`() {
        val root = createTempDirectory("plugin-incremental-")
        try {
            XodusCodeIndexStore.open(root.resolve("store")).use { store ->
                val pluginId = PluginId.of("dev.example.incremental")
                val files = mapOf("A.kt" to "class NewA", "B.kt" to "class B", "C.kt" to "class C")
                val calls = mutableListOf<String>()
                val analyzer =
                    object : FileAnalyzerV1 {
                        override val id: String = "file-fact"

                        override suspend fun analyze(context: FileAnalysisContextV1) {
                            calls += context.file.path
                            context.facts.put("text", PluginFactValue.Text.of(context.sourceText))
                        }
                    }
                val registry =
                    PluginRegistry(
                        descriptors = emptyMap(),
                        fileAnalyzers =
                            listOf(PluginRegistry.RegisteredFileAnalyzer(pluginId, analyzer)),
                        postProcessors = emptyList(),
                        checks = emptyList(),
                    )
                runBlocking {
                    StorePluginFactSink(store, pluginId.value, "A.kt")
                        .put("text", PluginFactValue.Text.of("class OldA"))
                    StorePluginFactSink(store, pluginId.value, "B.kt")
                        .put("text", PluginFactValue.Text.of("class B"))
                    StorePluginFactSink(store, pluginId.value, "C.kt")
                        .put("text", PluginFactValue.Text.of("class C"))
                }
                val context =
                    IndexBuildContext.forInlineSources(store, "fixture", files)
                        .copy(changedSourceSet = setOf(IndexedSource.workspace(Path("."), "A.kt")))

                PluginAnalyzerRunner(registry).analyze(context, setOf(pluginId.value))
                assertEquals(listOf("A.kt"), calls)
                assertEquals(
                    files.values.toSet(),
                    store
                        .prefixScan(CodeIndexKey.pluginFactPluginPrefix(pluginId.value))
                        .map { (it.second as PluginFactRecord).encodedValue }
                        .map(PluginFactValueCodec::decode)
                        .map { (it as PluginFactValue.Text).value }
                        .toSet(),
                )
                calls.clear()
                PluginAnalyzerRunner(registry)
                    .analyze(context, setOf(pluginId.value), setOf(pluginId.value))
                assertEquals(files.keys.toList(), calls)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

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
