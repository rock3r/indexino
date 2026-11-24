package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.PluginFactRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.PluginFactValue
import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.plugin.api.FileAnalysisContextV1
import dev.sebastiano.indexino.plugin.api.FileAnalyzerV1
import dev.sebastiano.indexino.producer.IndexBuildContext
import dev.sebastiano.indexino.producer.kotlinpsi.KotlinPsiSymbolProducer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(IndexinoInternalApi::class)
class SelectionContextEagerAnalysisTest {
    @Test
    fun `bundled analyzer runs once per Kotlin file during parsing and is skipped afterwards`() {
        val root = createTempDirectory("eager-analysis-")
        try {
            XodusCodeIndexStore.open(root.resolve("store")).use { store ->
                val pluginId = PluginId.of(BUNDLED_PLUGIN)
                val calls = ConcurrentHashMap<String, Int>()
                val sharedUses = AtomicInteger()
                val analyzer =
                    object : FileAnalyzerV1 {
                        override val id: String = "selection-context"

                        override suspend fun analyze(context: FileAnalysisContextV1) {
                            if (!context.file.path.endsWith(".kt")) return
                            calls.merge(context.file.path, 1, Int::plus)
                            if (takeSharedFile(context) != null) sharedUses.incrementAndGet()
                            context.facts.put("fake", PluginFactValue.Text.of(context.file.path))
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
                val runner = PluginAnalyzerRunner(registry)
                val context = eagerContext(store)
                runner.prepare(context, setOf(pluginId.value))
                val hook = assertNotNull(runner.kotlinFileAnalysis())

                KotlinPsiSymbolProducer().produce(context.copy(kotlinFileAnalysis = hook), store)

                assertEquals(KOTLIN_FILES, calls.size, "every Kotlin file must be analyzed")
                assertEquals(KOTLIN_FILES, calls.values.sum(), "each file is analyzed once")
                assertEquals(
                    KOTLIN_FILES,
                    sharedUses.get(),
                    "analyzer must receive the live KtFile",
                )
                assertEquals(KOTLIN_FILES, runner.sharedSyntaxHandoffs)
                assertEquals(
                    KOTLIN_FILES,
                    pluginFacts(store, pluginId).size,
                    "producer must flush buffered plugin facts",
                )

                runner.analyze(context, setOf(pluginId.value))

                assertEquals(
                    KOTLIN_FILES,
                    calls.values.sum(),
                    "the plugin pass must skip eagerly analyzed files",
                )
                assertEquals(KOTLIN_FILES, sharedUses.get())
                assertEquals(KOTLIN_FILES, pluginFacts(store, pluginId).size)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `eager analyzer failure aborts without committing buffered facts`() {
        val root = createTempDirectory("eager-failure-")
        try {
            XodusCodeIndexStore.open(root.resolve("store")).use { store ->
                val pluginId = PluginId.of(BUNDLED_PLUGIN)
                val analyzer =
                    object : FileAnalyzerV1 {
                        override val id: String = "failing"

                        override suspend fun analyze(context: FileAnalysisContextV1) {
                            if (!context.file.path.endsWith(".kt")) return
                            error("analyzer stopped")
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
                val runner = PluginAnalyzerRunner(registry)
                val context = eagerContext(store)
                runner.prepare(context, setOf(pluginId.value))
                val hook = assertNotNull(runner.kotlinFileAnalysis())

                assertFailsWith<IllegalStateException> {
                    KotlinPsiSymbolProducer()
                        .produce(context.copy(kotlinFileAnalysis = hook), store)
                }
                assertEquals(
                    0,
                    pluginFacts(store, pluginId).size,
                    "buffered facts must not be committed after an analyzer failure",
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `third party analyzers keep the separate plugin pass`() {
        val root = createTempDirectory("third-party-analysis-")
        try {
            XodusCodeIndexStore.open(root.resolve("store")).use { store ->
                val pluginId = PluginId.of("dev.example.third")
                val calls = AtomicInteger()
                val analyzer =
                    object : FileAnalyzerV1 {
                        override val id: String = "third"

                        override suspend fun analyze(context: FileAnalysisContextV1) {
                            if (!context.file.path.endsWith(".kt")) return
                            calls.incrementAndGet()
                            context.facts.put("third", PluginFactValue.Text.of(context.file.path))
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
                val runner = PluginAnalyzerRunner(registry)
                val context = eagerContext(store)
                runner.prepare(context, setOf(pluginId.value))
                assertNull(runner.kotlinFileAnalysis(), "third-party analyzers are not eager")

                KotlinPsiSymbolProducer().produce(context, store)
                assertEquals(0, calls.get())

                runner.analyze(context, setOf(pluginId.value))
                assertEquals(KOTLIN_FILES, calls.get())
                assertEquals(KOTLIN_FILES, pluginFacts(store, pluginId).size)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun takeSharedFile(context: FileAnalysisContextV1): org.jetbrains.kotlin.psi.KtFile? {
        val sink = context.facts as? Function1<*, *> ?: return null
        @Suppress("UNCHECKED_CAST") val provider = sink as Function1<String, Any?>
        return provider.invoke(context.sourceText) as? org.jetbrains.kotlin.psi.KtFile
    }

    private fun eagerContext(store: XodusCodeIndexStore): IndexBuildContext =
        IndexBuildContext.forInlineSources(store, "eager-analysis", sources())

    private fun pluginFacts(
        store: XodusCodeIndexStore,
        pluginId: PluginId,
    ): List<PluginFactRecord> =
        store
            .prefixScan(CodeIndexKey.pluginFactPluginPrefix(pluginId.value))
            .map { (_, record) -> record }
            .filterIsInstance<PluginFactRecord>()
            .toList()

    private fun sources(): LinkedHashMap<String, String> =
        linkedMapOf(
            "src/main/kotlin/fixture/One.kt" to "package fixture\nclass One { fun call() = Unit }",
            "src/main/kotlin/fixture/Two.kt" to "package fixture\nclass Two { fun call() = Unit }",
            "src/main/kotlin/fixture/Three.kt" to
                "package fixture\nclass Three { fun call() = Unit }",
            "src/main/java/fixture/Four.java" to "package fixture; class Four {}",
        )

    private companion object {
        const val BUNDLED_PLUGIN = "dev.sebastiano.selection-context"
        const val KOTLIN_FILES = 3
    }
}
