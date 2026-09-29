// SPDX-License-Identifier: UEL-1.0
package dev.sebastiano.indexino.acceptance

import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.IndexinoConfiguration
import dev.sebastiano.indexino.api.IndexinoException
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.RuntimeAttachMode
import dev.sebastiano.indexino.api.SnapshotFreshness
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Opt-in controlled edits in an explicitly owned disposable checkout. */
internal object IncrementalAcceptanceDriver {
    private val stageWait = 10.minutes
    private val defaultRunWait = 10.minutes
    private const val MAX_SEED_WAIT_MINUTES = 120
    private const val MAX_RUN_WAIT_MINUTES = 240
    private const val MAX_EVIDENCE_LINE = 300
    internal const val EDIT_NONCE_PROPERTY = "indexino.acceptance.editNonce"
    private val phaseCompleted = Regex("index phase=(\\S+) state=completed durationMillis=(\\d+)")
    private val evidencePrefixes = listOf("index ", "bazel query index=", "worktree fork ")

    /**
     * Refresh journals observed during one stage. Lines naming the private owned root are dropped
     * so reports stay shareable; phase durations are summed per phase name.
     */
    internal class RefreshEvidence(private val privateRoot: String) {
        private val ids = linkedSetOf<String>()
        private val local = mutableMapOf<String, List<String>>()

        @Synchronized
        fun observe(id: String) {
            ids += id
        }

        @Synchronized
        fun record(id: String, lines: List<String>) {
            ids += id
            local[id] = lines
        }

        fun drain(remote: ((String) -> List<String>)?): JsonArray {
            val (drained, recorded) =
                synchronized(this) {
                    (ids.toList() to local.toMap()).also {
                        ids.clear()
                        local.clear()
                    }
                }
            return JsonArray(
                drained.map { id ->
                    val lines =
                        recorded[id]
                            ?: runCatching { remote?.invoke(id) }.getOrNull()
                            ?: return@map buildJsonObject {
                                put("id", id)
                                put("journalUnavailable", true)
                            }
                    summarize(id, lines, privateRoot)
                }
            )
        }

        companion object {
            fun summarize(id: String, lines: List<String>, privateRoot: String): JsonObject {
                val kept =
                    lines
                        .filter { line -> evidencePrefixes.any(line::startsWith) }
                        .filterNot { privateRoot in it }
                        .map { it.take(MAX_EVIDENCE_LINE) }
                val phaseMillis = linkedMapOf<String, Long>()
                kept.forEach { line ->
                    phaseCompleted.matchEntire(line)?.destructured?.let { (phase, millis) ->
                        phaseMillis[phase] = (phaseMillis[phase] ?: 0L) + millis.toLong()
                    }
                }
                return buildJsonObject {
                    put("id", id)
                    put(
                        "phaseMillis",
                        buildJsonObject { phaseMillis.forEach { (phase, ms) -> put(phase, ms) } },
                    )
                    put("lines", JsonArray(kept.map(::JsonPrimitive)))
                }
            }
        }
    }

    internal class Progress(private val periodMillis: Long, private val sink: (String) -> Unit) :
        AutoCloseable {
        @Volatile private var currentStage = "seed"
        @Volatile private var observation: JsonObject? = null
        @Volatile private var stageStartNanos = System.nanoTime()
        @Volatile var refreshProbe: (() -> JsonObject?)? = null
        @Volatile private var latestRefresh: JsonObject? = null
        private val probePending = AtomicBoolean()
        private val probeExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "indexino-acceptance-progress-probe").apply { isDaemon = true }
        }
        private val heartbeat = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "indexino-acceptance-heartbeat").apply { isDaemon = true }
        }

        init {
            heartbeat.scheduleAtFixedRate(
                { emit(true) },
                periodMillis,
                periodMillis,
                TimeUnit.MILLISECONDS,
            )
        }

        fun stage(value: String) {
            currentStage = value
            stageStartNanos = System.nanoTime()
            observation = null
            latestRefresh = null
            emit(false)
        }

        fun observe(generation: String, elapsedNanos: Long, ordinal: Int?, predicate: String) {
            observation = buildJsonObject {
                put("generation", generation)
                put("elapsedNanos", elapsedNanos)
                put("predicate", predicate)
                if (ordinal != null) put("ordinal", ordinal)
            }
        }

        private fun emit(includeRefresh: Boolean) {
            val probe = refreshProbe
            if (includeRefresh && probe != null && probePending.compareAndSet(false, true)) {
                val stageAtStart = stageStartNanos
                probeExecutor.execute {
                    try {
                        val refresh = probe()
                        if (stageStartNanos == stageAtStart) latestRefresh = refresh
                    } catch (error: Exception) {
                        if (stageStartNanos == stageAtStart) {
                            latestRefresh = buildJsonObject {
                                put("probeError", error.javaClass.simpleName)
                            }
                        }
                    } finally {
                        probePending.set(false)
                    }
                }
            }
            sink(
                buildJsonObject {
                        put("at", Instant.now().toString())
                        put("stage", currentStage)
                        put("stageElapsedNanos", System.nanoTime() - stageStartNanos)
                        observation?.forEach { (key, value) -> put(key, value) }
                        latestRefresh?.let { put("refresh", it) }
                    }
                    .toString()
            )
        }

        override fun close() {
            heartbeat.shutdownNow()
            probeExecutor.shutdownNow()
        }
    }

    @JvmStatic
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun main(args: Array<String>): Unit = runBlocking {
        require(args.size == 5 || args.size == 7) {
            "owned-root plan.json manual|watcher repeats report.json " +
                "[seed-wait-minutes run-wait-minutes]"
        }
        val seedWait =
            if (args.size == 7) {
                args[5].toInt().also { require(it in 1..MAX_SEED_WAIT_MINUTES) }.minutes
            } else stageWait
        val runWait =
            if (args.size == 7) {
                args[6]
                    .toInt()
                    .also { require(it in 1..MAX_RUN_WAIT_MINUTES) }
                    .minutes
                    .also { require(it > seedWait) { "The run bound must leave time for edits" } }
            } else defaultRunWait
        val root = Path.of(args[0]).toRealPath()
        require(Files.isRegularFile(root.resolve(".indexino-benchmark-owned")))
        val workspace = root.resolve("workspace").toRealPath()
        require(workspace.startsWith(root))
        val plan = Json.parseToJsonElement(Files.readString(Path.of(args[1]))).jsonObject
        require(plan.getValue("schema").jsonPrimitive.int == 1)
        require(args[2] in setOf("manual", "watcher"))
        val watcher = args[2] == "watcher"
        val repeats = args[3].toInt().also { require(it in 1..10) }
        val output = Path.of(args[4]).toAbsolutePath().normalize()
        require(!output.startsWith(root) && Files.isDirectory(output.parent))
        // Opt-in: edited bytes unique to this run cannot repoint to a generation from earlier runs.
        val editNonce = System.getProperty(EDIT_NONCE_PROPERTY)
        val workload = IncrementalWorkload(workspace, plan, editNonce)
        val samples = mutableListOf<JsonObject>()
        val report = linkedMapOf<String, JsonElement>("status" to JsonPrimitive("incomplete"))
        editNonce?.let { report["editNonce"] = JsonPrimitive(it) }
        val progress = Progress(30_000L, System.err::println)
        var stage = "seed"
        var lastWatcherObservation: JsonObject? = null
        progress.stage(stage)
        fun setStage(value: String) {
            stage = value
            progress.stage(stage)
        }
        fun observe(generation: String, elapsedNanos: Long, ordinal: Int?, predicate: String) {
            lastWatcherObservation = buildJsonObject {
                put("generation", generation)
                put("elapsedNanos", elapsedNanos)
                put("predicate", predicate)
                if (ordinal != null) put("ordinal", ordinal)
            }
            progress.observe(generation, elapsedNanos, ordinal, predicate)
        }
        if (watcher) report["watcherWaitMillis"] = JsonPrimitive(stageWait.inWholeMilliseconds)
        report["stageWaitMillis"] = JsonPrimitive(stageWait.inWholeMilliseconds)
        report["seedWaitMillis"] = JsonPrimitive(seedWait.inWholeMilliseconds)
        report["runWaitMillis"] = JsonPrimitive(runWait.inWholeMilliseconds)
        val evidence = RefreshEvidence(root.toString())
        val resets = mutableListOf<JsonObject>()
        fun checkpoint() {
            report["samples"] = JsonArray(samples)
            Files.writeString(output, JsonObject(report).toString() + "\n")
        }
        val previousCache = System.getProperty("indexino.cache.dir")
        try {
            withTimeout(runWait) {
                System.setProperty("indexino.cache.dir", root.resolve("cache").toString())
                val request = request(plan)
                workload.write(workload.sources, 0)
                val configuration =
                    IndexinoConfiguration.forWorkspace(workspace)
                        .withRuntimeAttach(
                            if (watcher) RuntimeAttachMode.PREFER_DAEMON
                            else RuntimeAttachMode.IN_PROCESS
                        )
                        .withAutoRefresh(
                            if (watcher) AutoRefreshMode.ENABLED else AutoRefreshMode.DISABLED
                        )
                Indexino.connect(configuration).use { index ->
                    val remoteJournal: ((String) -> List<String>)? =
                        if (watcher) { id -> index.refreshProgress(id).text } else null
                    if (watcher) {
                        progress.refreshProbe = {
                            currentRefresh(index, request).also { refresh ->
                                refresh["id"]?.jsonPrimitive?.content?.let(evidence::observe)
                            }
                        }
                    }
                    try {
                        val seedStart = System.nanoTime()
                        var seedRefreshed = seedStart
                        withTimeout(seedWait) {
                            refreshAndLog(index, request, watcher, evidence)
                            seedRefreshed = System.nanoTime()
                            index.snapshot().use {
                                check(workload.matches(it, workload.sources, 0))
                            }
                        }
                        val seedReady = System.nanoTime()
                        report["seedReadyNanos"] = JsonPrimitive(seedReady - seedStart)
                        report["seedRefreshNanos"] = JsonPrimitive(seedRefreshed - seedStart)
                        report["seedVerifyNanos"] = JsonPrimitive(seedReady - seedRefreshed)
                        report["seedRefreshes"] = evidence.drain(remoteJournal)
                        checkpoint()
                        repeat(repeats) { repetition ->
                            for (group in workload.groups) {
                                setStage("settle:${group.id}:$repetition")
                                val settleNanos =
                                    if (watcher) {
                                        val settleStart = System.nanoTime()
                                        awaitQuiescent(
                                            stageWait,
                                            active = {
                                                index.activeRefreshes().map { it.id.value }
                                            },
                                            dirty = {
                                                index.snapshot().use {
                                                    it.freshnessAtAcquisition ==
                                                        SnapshotFreshness.DIRTY
                                                }
                                            },
                                            onActive = evidence::observe,
                                        )
                                        System.nanoTime() - settleStart
                                    } else 0L
                                val settleRefreshes = evidence.drain(remoteJournal)
                                setStage("edit:${group.id}:$repetition")
                                lastWatcherObservation = null
                                val sample =
                                    withTimeout(stageWait) {
                                        measure(
                                            index,
                                            request,
                                            workload,
                                            group,
                                            watcher,
                                            repetition,
                                            ::observe,
                                            evidence,
                                        )
                                    }
                                samples += buildJsonObject {
                                    sample.forEach { (key, value) -> put(key, value) }
                                    if (watcher) {
                                        put("preEditSettleNanos", settleNanos)
                                        put("settleRefreshes", settleRefreshes)
                                    }
                                    put("refreshes", evidence.drain(remoteJournal))
                                }
                                checkpoint()
                                setStage("reset:${group.id}:$repetition")
                                lastWatcherObservation = null
                                withTimeout(stageWait) {
                                    workload.write(group.sources, 0)
                                    if (watcher) {
                                        val resetStart = System.nanoTime()
                                        awaitRestoredBaseline(
                                            stageWait,
                                            { refreshAndLog(index, request, true, evidence) },
                                        ) {
                                            index.snapshot().use { snapshot ->
                                                observe(
                                                    snapshot.generation.value,
                                                    System.nanoTime() - resetStart,
                                                    null,
                                                    "querying",
                                                )
                                                var mismatch: Pair<Int, String>? = null
                                                val matches =
                                                    workload.matches(
                                                        snapshot,
                                                        workload.sources,
                                                        0,
                                                    ) { ordinal, predicate ->
                                                        mismatch = ordinal to predicate
                                                    }
                                                observe(
                                                    snapshot.generation.value,
                                                    System.nanoTime() - resetStart,
                                                    mismatch?.first,
                                                    mismatch?.second ?: "matched",
                                                )
                                                matches
                                            }
                                        }
                                    } else {
                                        refreshAndLog(index, request, evidence = evidence)
                                        index.snapshot().use {
                                            check(workload.matches(it, workload.sources, 0))
                                        }
                                    }
                                }
                                resets += buildJsonObject {
                                    put("stage", stage)
                                    put("refreshes", evidence.drain(remoteJournal))
                                }
                                report["resets"] = JsonArray(resets)
                            }
                        }
                        setStage("shutdown")
                    } catch (error: Exception) {
                        // Journals are daemon-owned; collect them before the runtime shuts down.
                        report["failureRefreshes"] = evidence.drain(remoteJournal)
                        throw error
                    } finally {
                        if (watcher) index.shutdownRuntime()
                    }
                }
                report["status"] = JsonPrimitive("passed")
            }
        } catch (error: Exception) {
            report["failureType"] = JsonPrimitive(error.javaClass.simpleName)
            report["failureStage"] = JsonPrimitive(stage)
            lastWatcherObservation?.let { report["lastWatcherObservation"] = it }
            if (error is IndexinoException) {
                report["failureCode"] = JsonPrimitive(error.failure.code)
                report["failureCategory"] = JsonPrimitive(error.failure.category.value)
            }
            throw error
        } finally {
            try {
                workload.restoreOriginals()
            } finally {
                progress.close()
                if (previousCache == null) System.clearProperty("indexino.cache.dir")
                else System.setProperty("indexino.cache.dir", previousCache)
                report["schema"] = JsonPrimitive(1)
                report["lane"] = JsonPrimitive(args[2])
                report["workload"] =
                    JsonPrimitive(
                        "controlled declaration rename and method arity change in existing source files"
                    )
                report["timing"] =
                    JsonPrimitive(
                        "readyNanos starts after final write and ends after correct public queries; " +
                            "totalNanos includes writes; watcher edits start only after " +
                            "preEditSettleNanos observes no active refresh and no dirty scope; " +
                            "seedReadyNanos is a separate bounded seed stage, not edit latency"
                    )
                checkpoint()
            }
        }
    }

    private suspend fun refreshAndLog(
        index: Indexino,
        request: RefreshRequest,
        remote: Boolean = false,
        evidence: RefreshEvidence? = null,
    ): dev.sebastiano.indexino.api.RefreshResult {
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val handle =
            index.refresh(
                request,
                { line ->
                    System.err.println(line)
                    lines += line
                },
                null,
            )
        // Remote refreshes ignore local progress; their journal is fetched from the daemon.
        if (remote) evidence?.observe(handle.id.value) else evidence?.record(handle.id.value, lines)
        try {
            return handle.await()
        } catch (failure: Exception) {
            if (remote) {
                try {
                    val journal = index.refreshProgress(handle.id.value)
                    System.err.println("failed daemon refresh ${handle.id.value}; last progress:")
                    journal.text.takeLast(32).forEach { System.err.println(it.take(512)) }
                    journal.machine.lastOrNull()?.let { System.err.println(it.take(512)) }
                } catch (diagnosticFailure: Exception) {
                    System.err.println(
                        "daemon progress unavailable: ${diagnosticFailure.javaClass.simpleName}"
                    )
                }
            }
            throw failure
        }
    }

    internal fun activeQueryDetail(lines: List<String>): String? {
        val latestPhase = lines.lastOrNull { it.startsWith("index phase=") }
        if (latestPhase != null && latestPhase != "index phase=topology state=started") return null
        return lines.lastOrNull { it.startsWith("bazel query index=") }
    }

    private fun currentRefresh(index: Indexino, request: RefreshRequest): JsonObject = runBlocking {
        val active = index.activeRefreshes().firstOrNull { it.request == request }
        if (active == null) {
            buildJsonObject { put("state", "idle") }
        } else {
            val journal = index.refreshProgress(active.id.value)
            val event = journal.machine.lastOrNull()?.let { Json.parseToJsonElement(it).jsonObject }
            buildJsonObject {
                put("state", "active")
                put("id", active.id.value)
                journal.text
                    .lastOrNull { it.startsWith("index phase=") }
                    ?.let { put("phaseDetail", it) }
                activeQueryDetail(journal.text)?.let { put("queryDetail", it) }
                event?.get("event")?.let { put("event", it) }
                event?.get("phase")?.let { put("phase", it) }
                event?.get("phaseCompleted")?.let { put("phaseCompleted", it) }
                event?.get("phaseTotal")?.let { put("phaseTotal", it) }
                event?.get("changedFiles")?.let { put("changedFiles", it) }
            }
        }
    }

    /**
     * Waits until no refresh is active and the scope is not dirty in two consecutive polls, so a
     * measured edit does not also wait for unrelated background refresh work.
     */
    internal suspend fun awaitQuiescent(
        timeout: Duration,
        active: suspend () -> List<String>,
        dirty: suspend () -> Boolean,
        onActive: (String) -> Unit,
        pollMillis: Long = 100L,
    ) {
        withTimeout(timeout) {
            var quietPolls = 0
            while (quietPolls < 2) {
                val ids = active()
                ids.forEach(onActive)
                quietPolls = if (ids.isEmpty() && !dirty()) quietPolls + 1 else 0
                if (quietPolls < 2) delay(pollMillis)
            }
        }
    }

    internal suspend fun awaitRestoredBaseline(
        timeout: Duration,
        refresh: suspend () -> Unit,
        matches: suspend () -> Boolean,
    ) {
        withTimeout(timeout) {
            while (true) {
                // An explicit refresh can join the watcher-triggered V1 refresh that was still
                // publishing when we restored V0. Awaiting that handle alone is not a V0 barrier.
                refresh()
                if (matches()) break
                delay(50)
            }
        }
    }

    private fun request(plan: JsonObject): RefreshRequest {
        val target = plan.getValue("target").jsonPrimitive.content
        val scope =
            when (plan.getValue("buildSystem").jsonPrimitive.content) {
                "gradle" -> IndexScope.gradle(target)
                "bazel" -> IndexScope.bazel(target)
                else -> error("Unsupported build system")
            }
        return RefreshRequest.forScope(scope.includingDependencies())
    }

    private suspend fun measure(
        index: Indexino,
        request: RefreshRequest,
        workload: IncrementalWorkload,
        group: IncrementalWorkload.Group,
        watcher: Boolean,
        repetition: Int,
        onWatcherPoll: (String, Long, Int?, String) -> Unit,
        evidence: RefreshEvidence,
    ): JsonObject =
        index.snapshot().use { previous ->
            check(workload.matches(previous, workload.sources, 0))
            val start = System.nanoTime()
            workload.write(group.sources, 1)
            val written = System.nanoTime()
            val timing = WatcherTiming()
            if (watcher) {
                while (true) {
                    val polled = System.nanoTime() - written
                    index.activeRefreshes().forEach {
                        evidence.observe(it.id.value)
                        timing.active(it.id.value, polled)
                    }
                    val acquiring = System.nanoTime()
                    val ready =
                        index.snapshot().use { snapshot ->
                            timing.acquired(System.nanoTime() - acquiring)
                            val generation = snapshot.generation
                            if (generation == previous.generation) {
                                onWatcherPoll(
                                    generation.value,
                                    System.nanoTime() - written,
                                    null,
                                    "generationUnchanged",
                                )
                                false
                            } else {
                                onWatcherPoll(
                                    generation.value,
                                    System.nanoTime() - written,
                                    null,
                                    "querying",
                                )
                                var mismatch: Pair<Int, String>? = null
                                val querying = System.nanoTime()
                                timing.newGeneration(querying - written)
                                val matches =
                                    workload.matches(snapshot, group.sources, 1) {
                                        ordinal,
                                        predicate ->
                                        mismatch = ordinal to predicate
                                    }
                                timing.queried(System.nanoTime() - querying)
                                onWatcherPoll(
                                    generation.value,
                                    System.nanoTime() - written,
                                    mismatch?.first,
                                    mismatch?.second ?: "matched",
                                )
                                matches
                            }
                        }
                    if (ready) break
                    delay(50)
                }
            } else {
                val result = refreshAndLog(index, request, evidence = evidence)
                check(result.changes.changedFileCount == group.sources.size)
                check(result.changes.removedFileCount == 0)
                index.snapshot().use {
                    check(
                        it.generation != previous.generation &&
                            workload.matches(it, group.sources, 1)
                    )
                }
            }
            val ready = System.nanoTime()
            check(workload.matches(previous, workload.sources, 0)) { "Pinned snapshot changed" }
            val unchanged = workload.sources.filter { it !in group.sources }
            index.snapshot().use { check(workload.matches(it, unchanged, 0)) }
            buildJsonObject {
                put("scenario", group.id)
                put("repeat", repetition)
                put("changedFiles", group.sources.size)
                put("changedModules", group.sources.map { it.module }.distinct().size)
                put("writeNanos", written - start)
                put("readyNanos", ready - written)
                put("totalNanos", ready - start)
                put("pinnedVerified", true)
                put("queriesVerified", true)
                if (watcher) put("watcherTiming", timing.toJson())
            }
        }

    /**
     * Splits watcher readiness into detection, refresh windows, publication visibility and
     * snapshot/query work. Windows come from 50ms polling, so they are approximate.
     */
    internal class WatcherTiming {
        private val windows = linkedMapOf<String, LongArray>()
        private var firstNewGeneration: Long? = null
        private var lastAcquire = 0L
        private var lastQuery = 0L
        private var queryPolls = 0

        fun active(id: String, sinceWrite: Long) {
            windows.getOrPut(id) { longArrayOf(sinceWrite, sinceWrite) }[1] = sinceWrite
        }

        fun acquired(nanos: Long) {
            lastAcquire = nanos
        }

        fun newGeneration(sinceWrite: Long) {
            if (firstNewGeneration == null) firstNewGeneration = sinceWrite
        }

        fun queried(nanos: Long) {
            lastQuery = nanos
            queryPolls++
        }

        fun toJson(): JsonObject = buildJsonObject {
            put(
                "refreshWindows",
                JsonArray(
                    windows.map { (id, window) ->
                        buildJsonObject {
                            put("id", id)
                            put("firstSeenNanos", window[0])
                            put("lastSeenNanos", window[1])
                        }
                    }
                ),
            )
            firstNewGeneration?.let { put("firstNewGenerationNanos", it) }
            put("finalSnapshotAcquireNanos", lastAcquire)
            put("finalQueryNanos", lastQuery)
            put("queryPolls", queryPolls)
        }
    }
}
