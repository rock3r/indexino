package dev.sebastiano.indexino.acceptance

import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.IndexSnapshot
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.IndexinoConfiguration
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.RuntimeAttachMode
import dev.sebastiano.indexino.api.SnapshotFreshness
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifest
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifestStore
import dev.sebastiano.indexino.core.cache.WorktreeOverlayStoreOpener
import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.core.store.WorktreeOverlayIndexStore
import dev.sebastiano.indexino.engine.RuntimeLeaseStore
import dev.sebastiano.indexino.engine.RuntimePaths
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.SourceOriginRevision
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import dev.sebastiano.indexino.model.WorkspaceRevision
import dev.sebastiano.indexino.producer.FileHashProducer
import dev.sebastiano.indexino.producer.IndexBuildContext
import dev.sebastiano.indexino.producer.IndexedSource
import dev.sebastiano.indexino.producer.ProducerRegistry
import dev.sebastiano.indexino.producer.SourceContentSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Private opt-in corpus experiment, not a runtime feature. Compares a complete one-file fact delta
 * queried through the real IndexSnapshot implementation with the daemon's durable publication on
 * the same saved edit. Optional `manual repeat` reverts the edited file in the same connected
 * runtime and reports those phase times with a `repeat_` prefix. Manual mode disables auto-refresh
 * and reports write-relative refresh request/await, public query readiness, and the completed
 * refresh's phase journal separately; watcher mode leaves auto-refresh enabled. The live revision
 * is deliberately experimental, not Git provenance.
 */
@OptIn(IndexinoInternalApi::class)
internal object LiveOverlayCorpusProbe {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        require(args.size in 4..6) {
            "owned-root plan.json report.json edit-nonce [watcher|manual] [repeat]"
        }
        val durableMode = args.getOrNull(4) ?: "watcher"
        require(durableMode in setOf("watcher", "manual"))
        require(args.size != 6 || (durableMode == "manual" && args[5] == "repeat"))
        val root = Path.of(args[0]).toRealPath()
        require(Files.isRegularFile(root.resolve(".indexino-benchmark-owned")))
        val workspace = root.resolve("workspace").toRealPath()
        val output = Path.of(args[2]).toAbsolutePath().normalize()
        require(!output.startsWith(root) && Files.isDirectory(output.parent))
        val plan = Json.parseToJsonElement(Files.readString(Path.of(args[1]))).jsonObject
        require(plan.getValue("groups").jsonArray.size == 1)
        val workload = IncrementalWorkload(workspace, plan, args[3])
        val group = workload.groups.single()
        require(group.sources.size == 1)
        val source = group.sources.single()
        val priorCache = System.getProperty("indexino.cache.dir")
        val report = mutableMapOf("status" to "incomplete", "durableMode" to durableMode)
        try {
            System.setProperty("indexino.cache.dir", root.resolve("cache").toString())
            workload.write(workload.sources, 0)
            runCorpus(workspace, plan, workload, source, durableMode, args.size == 6, report)
            report["status"] = "passed"
        } catch (error: Exception) {
            report["failure"] = error.javaClass.simpleName
            throw error
        } finally {
            try {
                workload.restoreOriginals()
            } finally {
                if (priorCache == null) System.clearProperty("indexino.cache.dir")
                else System.setProperty("indexino.cache.dir", priorCache)
                report["experimentalRevisionNotGitProvenance"] = "true"
                Files.writeString(
                    output,
                    buildJsonObject { report.forEach { (key, value) -> put(key, value) } }
                        .toString() + "\n",
                )
            }
        }
    }

    private suspend fun runCorpus(
        workspace: Path,
        plan: kotlinx.serialization.json.JsonObject,
        workload: IncrementalWorkload,
        source: IncrementalWorkload.Source,
        durableMode: String,
        repeat: Boolean,
        report: MutableMap<String, String>,
    ) {
        val target = plan.getValue("target").jsonPrimitive.content
        val scope =
            when (plan.getValue("buildSystem").jsonPrimitive.content) {
                "bazel" -> IndexScope.bazel(target)
                "gradle" -> IndexScope.gradle(target)
                else -> error("Unsupported build system")
            }
        val request = RefreshRequest.forScope(scope.includingDependencies())
        val configuration =
            IndexinoConfiguration.forWorkspace(workspace)
                .withRuntimeAttach(RuntimeAttachMode.PREFER_DAEMON)
                .withAutoRefresh(
                    if (durableMode == "manual") AutoRefreshMode.DISABLED
                    else AutoRefreshMode.ENABLED
                )
        Indexino.connect(configuration).use { index ->
            try {
                val lease =
                    checkNotNull(
                        RuntimeLeaseStore.read(
                            RuntimePaths.leasePath(
                                InProcessCacheLayout.cacheRoot(),
                                InProcessCacheLayout.workspaceId(workspace),
                            )
                        )
                    )
                check(lease.autoRefreshMode == configuration.autoRefreshMode)
                report["runtimeAutoRefreshMode"] = lease.autoRefreshMode.name
                withTimeout(20.minutes) { index.refresh(request).await() }
                index.snapshot().use { old ->
                    check(workload.matches(old, workload.sources, 0))
                    IncrementalAcceptanceDriver.awaitQuiescent(
                        3.minutes,
                        active = { index.activeRefreshes().map { it.id.value } },
                        dirty = {
                            index.snapshot().use {
                                it.freshnessAtAcquisition == SnapshotFreshness.DIRTY
                            }
                        },
                        onActive = {},
                    )
                    val cacheRoot = InProcessCacheLayout.cacheRoot()
                    val manifest =
                        checkNotNull(
                            WorkspaceGenerationManifestStore(
                                    cacheRoot,
                                    InProcessCacheLayout.workspaceId(workspace),
                                )
                                .current()
                        )
                    check(manifest.generation == old.generation.value)
                    // Warm a private read-only base; the daemon owns the shared Xodus store.
                    val base =
                        WorktreeOverlayStoreOpener.openForQuery(
                            cacheRoot,
                            workspace,
                            "live-experiment-${System.nanoTime()}",
                            manifest,
                        )
                    compareEdit(
                        index,
                        request,
                        old,
                        base,
                        manifest,
                        workspace,
                        workload,
                        source,
                        durableMode,
                        1,
                        report,
                    )
                    if (repeat)
                        compareRevertedEdit(
                            index,
                            request,
                            old,
                            workspace,
                            workload,
                            source,
                            durableMode,
                            report,
                        )
                }
            } finally {
                index.shutdownRuntime()
            }
        }
    }

    private suspend fun compareRevertedEdit(
        index: Indexino,
        request: RefreshRequest,
        original: IndexSnapshot,
        workspace: Path,
        workload: IncrementalWorkload,
        source: IncrementalWorkload.Source,
        durableMode: String,
        report: MutableMap<String, String>,
    ) {
        // Keep the original snapshot pinned while measuring the reverse edit against the
        // newly published generation in this same runtime.
        index.snapshot().use { afterFirst ->
            check(workload.matches(afterFirst, listOf(source), 1))
            val cacheRoot = InProcessCacheLayout.cacheRoot()
            val nextManifest =
                checkNotNull(
                    WorkspaceGenerationManifestStore(
                            cacheRoot,
                            InProcessCacheLayout.workspaceId(workspace),
                        )
                        .current()
                )
            check(nextManifest.generation == afterFirst.generation.value)
            val nextBase =
                WorktreeOverlayStoreOpener.openForQuery(
                    cacheRoot,
                    workspace,
                    "live-experiment-${System.nanoTime()}",
                    nextManifest,
                )
            val repeatReport = mutableMapOf<String, String>()
            compareEdit(
                index,
                request,
                afterFirst,
                nextBase,
                nextManifest,
                workspace,
                workload,
                source,
                durableMode,
                0,
                repeatReport,
            )
            repeatReport.forEach { (key, value) -> report["repeat_$key"] = value }
            check(workload.matches(original, listOf(source), 0))
        }
    }

    private suspend fun compareEdit(
        index: Indexino,
        request: RefreshRequest,
        old: IndexSnapshot,
        base: CodeIndexStore,
        manifest: WorkspaceGenerationManifest,
        workspace: Path,
        workload: IncrementalWorkload,
        source: IncrementalWorkload.Source,
        durableMode: String,
        version: Int,
        report: MutableMap<String, String>,
    ) =
        Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "indexino-live-durable-observer").apply { isDaemon = true }
            }
            .asCoroutineDispatcher()
            .use { observer ->
                coroutineScope {
                    var baseHandedOff = false
                    try {
                        val edited = System.nanoTime()
                        workload.write(listOf(source), version)
                        val written = System.nanoTime()
                        report["writeNanos"] = (written - edited).toString()
                        val durableObserved =
                            async(observer) {
                                observeDurable(
                                    index,
                                    request,
                                    old,
                                    workload,
                                    source,
                                    durableMode,
                                    version,
                                    written,
                                )
                            }
                        try {
                            val live =
                                buildLiveSnapshot(
                                    base,
                                    manifest,
                                    workspace,
                                    source,
                                    written,
                                    report,
                                )
                            baseHandedOff = true
                            live.use {
                                val queryStarted = System.nanoTime()
                                check(workload.matches(it, listOf(source), version))
                                report["liveQueryNanos"] =
                                    (System.nanoTime() - queryStarted).toString()
                                report["liveReadyNanos"] = (System.nanoTime() - written).toString()
                                check(workload.matches(old, listOf(source), 1 - version))
                                val observed = durableObserved.await()
                                report["durableReadyNanos"] = observed.readyNanos.toString()
                                observed.refreshRequestNanos?.let { elapsed ->
                                    report["durableRefreshRequestNanos"] = elapsed.toString()
                                }
                                observed.refreshAwaitNanos?.let { elapsed ->
                                    report["durableRefreshAwaitNanos"] = elapsed.toString()
                                }
                                observed.refreshId?.let { id ->
                                    val phases =
                                        IncrementalAcceptanceDriver.RefreshEvidence.summarize(
                                                id,
                                                index.refreshProgress(id).text,
                                                workspace.parent.toString(),
                                            )
                                            .getValue("phaseMillis")
                                            .jsonObject
                                    phases.forEach { (phase, millis) ->
                                        report["durablePhase_${phase}Millis"] =
                                            millis.jsonPrimitive.content
                                    }
                                }
                                index.snapshot().use { durable ->
                                    check(durable.generation != old.generation)
                                    check(workload.matches(durable, listOf(source), version))
                                    check(
                                        workload.matches(
                                            durable,
                                            workload.sources.filter { item -> item != source },
                                            0,
                                        )
                                    )
                                }
                                check(
                                    workload.matches(
                                        it,
                                        workload.sources.filter { item -> item != source },
                                        0,
                                    )
                                )
                                report["oldPinAndUnchangedVerified"] = "true"
                                report["publicQueriesVerified"] = "true"
                                report["liveQueryLocalDurableQueryRemote"] = "true"
                            }
                        } finally {
                            durableObserved.cancel()
                        }
                    } finally {
                        if (!baseHandedOff) base.close()
                    }
                }
            }

    private suspend fun observeDurable(
        index: Indexino,
        request: RefreshRequest,
        old: IndexSnapshot,
        workload: IncrementalWorkload,
        source: IncrementalWorkload.Source,
        durableMode: String,
        version: Int,
        written: Long,
    ): DurableObservation =
        withTimeout(3.minutes) {
            var refreshRequested: Long? = null
            var refreshAwaited: Long? = null
            var refreshId: String? = null
            var awaitedGeneration: WorkspaceGenerationId? = null
            if (durableMode == "manual") {
                refreshRequested = System.nanoTime() - written
                val handle = index.refresh(request)
                refreshId = handle.id.value
                awaitedGeneration = handle.await().generation
                refreshAwaited = System.nanoTime() - written
            }
            while (true) {
                val matched =
                    index.snapshot().use { durable ->
                        durable.generation != old.generation &&
                            (awaitedGeneration == null ||
                                durable.generation == awaitedGeneration) &&
                            workload.matches(durable, listOf(source), version)
                    }
                if (matched) break
                delay(50)
            }
            DurableObservation(
                refreshRequested,
                refreshAwaited,
                System.nanoTime() - written,
                refreshId,
            )
        }

    private class DurableObservation(
        val refreshRequestNanos: Long?,
        val refreshAwaitNanos: Long?,
        val readyNanos: Long,
        val refreshId: String?,
    )

    private fun buildLiveSnapshot(
        base: CodeIndexStore,
        manifest: WorkspaceGenerationManifest,
        workspace: Path,
        source: IncrementalWorkload.Source,
        written: Long,
        report: MutableMap<String, String>,
    ): IndexSnapshot {
        val originRoot =
            if (source.file.originId.value == "workspace") workspace
            else workspace.resolve(source.file.originId.value.removePrefix("git:"))
        val indexed = IndexedSource(source.file.originId.value, originRoot, source.file.path)
        val captureStarted = System.nanoTime()
        val captured = SourceContentSnapshot.capture(listOf(indexed))
        report["liveCaptureNanos"] = (System.nanoTime() - captureStarted).toString()
        val overlayStarted = System.nanoTime()
        val delta = MemoryStore()
        val overlay =
            WorktreeOverlayIndexStore(
                base,
                delta,
                listOf(
                    WorktreeOverlayIndexStore.tombstonePrefixForSource(
                        indexed.originId,
                        indexed.path,
                    )
                ),
            )
        val context =
            IndexBuildContext(
                store = overlay,
                commitHash = "experimental-live-only",
                workspaceRoot = workspace,
                sourceFiles = listOf(source.relative),
                sources = listOf(indexed),
                sourceSnapshot = captured,
                changedSourceSet = setOf(indexed),
            )
        report["liveOverlaySetupNanos"] = (System.nanoTime() - overlayStarted).toString()
        ProducerRegistry.forApplications(emptyList()).forEach { producer ->
            val producerStarted = System.nanoTime()
            producer.produce(context.copy(activePhase = producer.id), overlay)
            val elapsed = (System.nanoTime() - producerStarted).toString()
            report["liveProducer_${producer.id}Nanos"] = elapsed
            if (producer.id == "java-source") report["liveJavaProducerNanos"] = elapsed
        }
        delta.freeze()
        report["liveFactsNanos"] = (System.nanoTime() - written).toString()
        val contentIdentity =
            FileHashProducer.contentHash(
                "${manifest.generation}:${source.file.originId.value}:" +
                    "${source.file.path}:${captured.contentHash(indexed)}"
            )
        val revision =
            WorkspaceRevision(
                contentIdentity,
                manifest.origins.map { origin ->
                    SourceOriginRevision(
                        dev.sebastiano.indexino.model.SourceOriginId.of(origin.originId),
                        null,
                        if (origin.originId == source.file.originId.value) contentIdentity
                        else origin.stateFingerprint,
                        origin.expectedRevision,
                    )
                },
            )
        val snapshotStarted = System.nanoTime()
        return IndexSnapshot.create(overlay, revision, WorkspaceGenerationId.of(contentIdentity))
            .also {
                report["liveSnapshotCreateNanos"] = (System.nanoTime() - snapshotStarted).toString()
            }
    }

    private class MemoryStore : CodeIndexStore {
        private val records = linkedMapOf<CodeIndexKey, CodeIndexRecord>()
        private var frozen = false

        fun freeze() {
            frozen = true
        }

        override fun get(key: CodeIndexKey): CodeIndexRecord? = records[key]

        override fun put(key: CodeIndexKey, record: CodeIndexRecord) {
            check(!frozen)
            records[key] = record
        }

        override fun delete(key: CodeIndexKey) {
            check(!frozen)
            records.remove(key)
        }

        override fun prefixScan(prefix: String): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> =
            records
                .asSequence()
                .filter { (key, _) -> key.value.startsWith(prefix) }
                .map { it.toPair() }

        override fun <T> transaction(block: () -> T): T = block()

        override fun close() = Unit
    }
}
