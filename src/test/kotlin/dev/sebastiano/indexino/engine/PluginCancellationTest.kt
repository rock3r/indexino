package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.plugin.api.FileAnalysisContextV1
import dev.sebastiano.indexino.plugin.api.FileAnalyzerV1
import dev.sebastiano.indexino.plugin.api.PostProcessContextV1
import dev.sebastiano.indexino.plugin.api.PostProcessLevelV1
import dev.sebastiano.indexino.plugin.api.PostProcessorV1
import dev.sebastiano.indexino.producer.IndexBuildContext
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

@OptIn(IndexinoInternalApi::class)
internal class PluginCancellationTest {
    @TempDir lateinit var root: Path

    @Test
    fun `file analyzer cancellation checkpoint observes worker interruption`() = exercise(null)

    @Test
    fun `shard post processor cancellation checkpoint observes worker interruption`() =
        exercise(PostProcessLevelV1.SHARD)

    @Test
    fun `composite post processor cancellation checkpoint observes worker interruption`() =
        exercise(PostProcessLevelV1.COMPOSITE)

    private fun exercise(level: PostProcessLevelV1?) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val crossedCheckpoint = AtomicBoolean()
        val failed = AtomicBoolean()
        fun checkpoint(ensureActive: () -> Unit) {
            entered.countDown()
            try {
                check(release.await(5, TimeUnit.SECONDS))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            ensureActive()
            crossedCheckpoint.set(true)
        }
        val plugin = PluginId.of("dev.example.cancellation")
        val analyzer =
            object : FileAnalyzerV1 {
                override val id: String = "cooperative-file"

                override suspend fun analyze(context: FileAnalysisContextV1) =
                    checkpoint(context::ensureActive)
            }
        val processor =
            object : PostProcessorV1 {
                override val id: String = "cooperative-post"
                override val level: PostProcessLevelV1 = level ?: PostProcessLevelV1.SHARD
                override val readsBasicFactFamilies: Set<String> = emptySet()
                override val readsPluginNamespaces: Set<String> = emptySet()

                override suspend fun process(context: PostProcessContextV1) =
                    checkpoint(context::ensureActive)
            }
        val registry =
            PluginRegistry(
                descriptors = emptyMap(),
                fileAnalyzers =
                    if (level == null) {
                        listOf(PluginRegistry.RegisteredFileAnalyzer(plugin, analyzer))
                    } else emptyList(),
                postProcessors =
                    if (level != null) {
                        listOf(PluginRegistry.RegisteredPostProcessor(plugin, processor))
                    } else emptyList(),
                checks = emptyList(),
            )
        val store = XodusCodeIndexStore.open(root.resolve("store"))
        try {
            val context =
                IndexBuildContext.forInlineSources(store, "fixture", mapOf("A.kt" to "class A"))
            val worker =
                Thread(
                    {
                        try {
                            failed.set(
                                runCatching {
                                        PluginAnalyzerRunner(registry)
                                            .analyze(context, setOf(plugin.value))
                                    }
                                    .isFailure
                            )
                        } finally {
                            finished.countDown()
                        }
                    },
                    "indexino-plugin-cancellation-test",
                )
            worker.start()
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS), "Contribution did not start")
                worker.interrupt()
                assertTrue(finished.await(5, TimeUnit.SECONDS), "Contribution did not finish")
                assertFalse(crossedCheckpoint.get(), "Plugin ensureActive ignored cancellation")
                assertTrue(failed.get(), "Interrupted contribution completed successfully")
            } finally {
                release.countDown()
                worker.join(5_000)
                assertFalse(worker.isAlive, "Owned plugin test worker survived cleanup")
            }
        } finally {
            store.close()
        }
    }
}
