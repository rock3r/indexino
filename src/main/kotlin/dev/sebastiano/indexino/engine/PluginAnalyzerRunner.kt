package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.api.IndexSnapshot
import dev.sebastiano.indexino.api.SnapshotFreshness
import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.PluginFactRecord
import dev.sebastiano.indexino.model.SourceFile
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.SourceOriginRevision
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import dev.sebastiano.indexino.model.WorkspaceRevision
import dev.sebastiano.indexino.plugin.api.FileAnalysisContextV1
import dev.sebastiano.indexino.plugin.api.FileAnalyzerV1
import dev.sebastiano.indexino.plugin.api.PostProcessContextV1
import dev.sebastiano.indexino.plugin.api.PostProcessLevelV1
import dev.sebastiano.indexino.producer.FactSink
import dev.sebastiano.indexino.producer.IndexBuildContext
import dev.sebastiano.indexino.producer.IndexedSource
import dev.sebastiano.indexino.producer.KotlinFileAnalysis
import dev.sebastiano.indexino.producer.SortedFactBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking

/**
 * Runs bundled Kotlin file analyzers inside the Kotlin producer's parse workers, while each syntax
 * tree is still live. Only plugins in [BUNDLED_KOTLIN_PLUGIN_IDS] take this path; every other
 * analyzer keeps the separate post-producer pass.
 */
@OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)
internal class PluginAnalyzerRunner(private val registry: PluginRegistry) {
    private val eagerAnalyzers = mutableMapOf<String, List<FileAnalyzerV1>>()
    private val filesToAnalyze = mutableMapOf<String, Set<IndexedSource>>()
    private val analyzersByPlugin = mutableMapOf<String, List<FileAnalyzerV1>>()
    private val eagerSources = ConcurrentHashMap.newKeySet<EagerSource>()
    private val bufferedFacts = ConcurrentLinkedQueue<Pair<CodeIndexKey, PluginFactRecord>>()
    private val handoffs = AtomicInteger()
    private val eagerLock = Any()
    private var prepared = false

    internal val sharedSyntaxHandoffs: Int
        get() = handoffs.get()

    /** Only bundled plugins opt into analysis on the parse worker. */
    internal fun kotlinFileAnalysis(): KotlinFileAnalysis? =
        if (eagerAnalyzers.isEmpty()) null else EagerKotlinFileAnalysis()

    /**
     * Deletes stale plugin facts before producers run. New facts are buffered by the Kotlin
     * producer and committed through its sorted write batch; the regular plugin pass must not
     * delete them again.
     */
    internal fun prepare(
        context: IndexBuildContext,
        selectedPluginIds: Set<String>,
        fullAnalysisPlugins: Set<String> = emptySet(),
    ) {
        if (prepared) return
        registry
            .pluginIds()
            .map { it.value }
            .filterNot(selectedPluginIds::contains)
            .forEach { pluginId ->
                val keys = mutableListOf<CodeIndexKey>()
                context.store.forEachWritablePrefix(
                    CodeIndexKey.pluginFactPluginPrefix(pluginId)
                ) { key, _ ->
                    keys += key
                    true
                }
                keys.forEach(context.store::delete)
            }
        val selectedAnalyzers =
            registry.fileAnalyzers
                .filter { it.pluginId.value in selectedPluginIds }
                .groupBy { it.pluginId.value }
        selectedPluginIds.forEach { pluginId ->
            val sources =
                if (pluginId in fullAnalysisPlugins) context.sources
                else context.changedSources + context.reindexedSources
            filesToAnalyze[pluginId] = sources.toSet()
            val analyzers = selectedAnalyzers[pluginId].orEmpty().map { it.analyzer }
            analyzersByPlugin[pluginId] = analyzers
            if (pluginId in BUNDLED_KOTLIN_PLUGIN_IDS && analyzers.isNotEmpty()) {
                eagerAnalyzers[pluginId] = analyzers
            }
        }
        deleteAffectedFacts(context, selectedPluginIds)
        prepared = true
    }

    /**
     * Mutates build facts; the build owner checkpoints and restores the complete writable store.
     */
    @Suppress("LongMethod")
    internal fun analyze(
        context: IndexBuildContext,
        selectedPluginIds: Set<String>,
        fullAnalysisPlugins: Set<String> = emptySet(),
    ) {
        if (!prepared) prepare(context, selectedPluginIds, fullAnalysisPlugins)
        flushBufferedFacts(context)
        val phaseFacts = SortedFactBuffer(context.store, "plugins")
        selectedPluginIds.forEach { pluginId ->
            val analyzers = analyzersByPlugin[pluginId].orEmpty()
            val pending =
                filesToAnalyze[pluginId].orEmpty().filterNot { source ->
                    EagerSource(pluginId, source.originId, source.path) in eagerSources
                }
            try {
                pending.forEach { source ->
                    analyzers.forEach { analyzer ->
                        runBlocking {
                            analyzer.analyze(
                                FileAnalysisContextV1(
                                    file =
                                        SourceFile.of(
                                            SourceOriginId.of(source.originId),
                                            source.path,
                                            source.path,
                                        ),
                                    sourceText = context.readSource(source),
                                    facts =
                                        BufferedPhaseFactSink(
                                            pluginId,
                                            source.originId,
                                            source.path,
                                            phaseFacts,
                                        ),
                                    active = {
                                        coroutineContext.ensureActive()
                                        !Thread.currentThread().isInterrupted
                                    },
                                )
                            )
                        }
                    }
                }
            } finally {
                analyzers.forEach { analyzer ->
                    runCatching { (analyzer as? AutoCloseable)?.close() }
                }
            }
            registry.postProcessors
                .filter {
                    it.pluginId.value == pluginId && it.processor.level == PostProcessLevelV1.SHARD
                }
                .forEach { registered ->
                    val buildQueries = context.buildQueriesSnapshot()
                    context.resolvedOriginIds
                        .ifEmpty { setOf("workspace") }
                        .forEach { originId ->
                            runBlocking {
                                registered.processor.process(
                                    PostProcessContextV1(
                                        queries = buildQueries,
                                        facts =
                                            BufferedPhaseFactSink(
                                                pluginId,
                                                originId,
                                                POST_PROCESSOR_FILE,
                                                phaseFacts,
                                            ),
                                        originId = SourceOriginId.of(originId),
                                        active = {
                                            coroutineContext.ensureActive()
                                            !Thread.currentThread().isInterrupted
                                        },
                                    )
                                )
                            }
                        }
                }
            registry.postProcessors
                .filter {
                    it.pluginId.value == pluginId &&
                        it.processor.level == PostProcessLevelV1.COMPOSITE
                }
                .forEach { registered ->
                    val buildQueries = context.buildQueriesSnapshot()
                    runBlocking {
                        registered.processor.process(
                            PostProcessContextV1(
                                queries = buildQueries,
                                facts =
                                    BufferedPhaseFactSink(
                                        pluginId,
                                        "workspace",
                                        POST_PROCESSOR_FILE,
                                        phaseFacts,
                                    ),
                                active = {
                                    coroutineContext.ensureActive()
                                    !Thread.currentThread().isInterrupted
                                },
                            )
                        )
                    }
                }
        }
        phaseFacts.flush()
    }

    private fun deleteAffectedFacts(context: IndexBuildContext, selectedPluginIds: Set<String>) {
        selectedPluginIds.forEach { pluginId ->
            val affectedSources = filesToAnalyze[pluginId].orEmpty() + context.deletedSources
            val affectedFiles = affectedSources.mapTo(hashSetOf()) { it.originId to it.path }
            val keys = mutableListOf<CodeIndexKey>()
            context.store.forEachWritablePrefix(CodeIndexKey.pluginFactPluginPrefix(pluginId)) {
                key,
                record ->
                if (
                    record is PluginFactRecord &&
                        ((record.originId to record.relativeFile) in affectedFiles ||
                            record.relativeFile == POST_PROCESSOR_FILE)
                )
                    keys += key
                true
            }
            keys.forEach(context.store::delete)
        }
    }

    private fun flushBufferedFacts(context: IndexBuildContext) {
        if (bufferedFacts.isEmpty()) return
        val facts = SortedFactBuffer(context.store, "plugins")
        while (true) {
            val entry = bufferedFacts.poll() ?: break
            facts.put(entry.first, entry.second)
        }
        facts.flush()
    }

    private fun analyzeEagerly(
        source: IndexedSource,
        sourceText: String,
        file: org.jetbrains.kotlin.psi.KtFile,
    ) {
        eagerAnalyzers.forEach { (pluginId, analyzers) ->
            val key = EagerSource(pluginId, source.originId, source.path)
            if (key in eagerSources) return@forEach
            if (source !in filesToAnalyze[pluginId].orEmpty()) return@forEach
            val sourceFile =
                SourceFile.of(SourceOriginId.of(source.originId), source.path, source.path)
            analyzers.forEach { analyzer ->
                val sink =
                    BufferedPluginFactSink(pluginId, source.originId, source.path, sourceText, file)
                synchronized(eagerLock) {
                    runBlocking {
                        analyzer.analyze(
                            FileAnalysisContextV1(
                                file = sourceFile,
                                sourceText = sourceText,
                                facts = sink,
                                active = {
                                    coroutineContext.ensureActive()
                                    !Thread.currentThread().isInterrupted
                                },
                            )
                        )
                    }
                }
                bufferedFacts.addAll(sink.drain())
                if (sink.tookSharedSyntax) handoffs.incrementAndGet()
            }
            eagerSources.add(key)
        }
    }

    private inner class EagerKotlinFileAnalysis : KotlinFileAnalysis {
        override fun analyze(
            source: IndexedSource,
            sourceText: String,
            file: org.jetbrains.kotlin.psi.KtFile,
        ) {
            analyzeEagerly(source, sourceText, file)
        }

        override fun writeBufferedFacts(sink: FactSink) {
            while (true) {
                val entry = bufferedFacts.poll() ?: return
                sink.put(entry.first, entry.second)
            }
        }
    }

    private data class EagerSource(val pluginId: String, val originId: String, val path: String)

    internal companion object {
        const val POST_PROCESSOR_FILE: String = "__postprocess__"

        /** Bundled plugins whose Kotlin analyzers understand the in-worker syntax handoff. */
        val BUNDLED_KOTLIN_PLUGIN_IDS: Set<String> = setOf("dev.sebastiano.selection-context")
    }
}

@OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)
private fun IndexBuildContext.buildQueriesSnapshot(): IndexSnapshot {
    val originIds = resolvedOriginIds.ifEmpty { setOf("workspace") }
    return IndexSnapshot.create(
        store = store,
        revision =
            WorkspaceRevision(
                fingerprint = commitHash,
                origins =
                    originIds
                        .map { originId ->
                            SourceOriginRevision(
                                originId = SourceOriginId.of(originId),
                                revision = commitHash,
                                stateFingerprint = commitHash,
                                expectedRevision = null,
                            )
                        }
                        .toList(),
            ),
        generation = WorkspaceGenerationId.of("build:$commitHash"),
        freshnessAtAcquisition = SnapshotFreshness.UNKNOWN,
    )
}
