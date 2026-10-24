package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.FreshnessPolicy
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.RefreshHandle
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.SnapshotFreshness
import dev.sebastiano.indexino.producer.IndexedSource
import dev.sebastiano.indexino.topology.TopologyResult
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Starts a recursive native stream for a workspace: root, path callback, overflow callback. */
internal typealias NativeWorkspaceWatcherFactory =
    (Path, (Path) -> Unit, () -> Unit) -> NativeWorkspaceWatcher

/** Native transports must stop claiming recursive coverage when their worker exits. */
internal interface NativeWorkspaceWatcher : AutoCloseable {
    val isAlive: Boolean
}

/** Runtime-owned, scope-derived watcher that coalesces filesystem hints into refresh requests. */
@Suppress("TooManyFunctions", "LargeClass")
internal class AutoRefreshController(
    private val workspace: Path,
    private val mode: AutoRefreshMode,
    private val refresh: (RefreshRequest) -> Unit,
    private val maxWatchedDirectories: Int = Int.MAX_VALUE,
    private val reconciliationIntervalMillis: Long = RECONCILIATION_INTERVAL_MILLIS,
    private val maxDebounceNanos: Long = MAX_DEBOUNCE_NANOS,
    private val topologyProvider: (RefreshRequest) -> TopologyResult? = { null },
    /**
     * Launches a known-source refresh: request, reused topology, hinted paths, coverage check and
     * the root a recursive native stream covers (null when only per-directory watches exist).
     */
    private val refreshWithTopology:
        ((RefreshRequest, TopologyResult, Set<Path>, () -> Boolean, Path?, Boolean) -> Unit)? =
        null,
    private val nativeWorkspaceWatcher: NativeWorkspaceWatcherFactory? = null,
    private val tentativeParentHint:
        ((RefreshRequest, Set<Path>, Set<Path>) -> Indexino.TentativeHint?)? =
        null,
    private val nativeFlushForTests: (() -> Unit)? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val watcherKind = AutoRefreshWatcherFactory.configuredKind()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "indexino-auto-refresh").apply { isDaemon = true }
    }
    private val requestsByDirectory = ConcurrentHashMap<Path, MutableSet<RefreshRequest>>()
    private val directoriesByRequest = ConcurrentHashMap<RefreshRequest, Set<Path>>()
    private val sourcePathsByRequest = ConcurrentHashMap<RefreshRequest, Set<Path>>()
    private val topologyInputsByRequest = ConcurrentHashMap<RefreshRequest, Set<Path>>()
    private val keys = ConcurrentHashMap<WatchKey, Path>()
    private val queued = ConcurrentHashMap.newKeySet<RefreshRequest>()
    private val dirty = ConcurrentHashMap.newKeySet<RefreshRequest>()
    private val dirtyEpoch = ConcurrentHashMap<RefreshRequest, AtomicLong>()
    private val active = ConcurrentHashMap<RefreshRequest, String>()
    private val pendingLaunch = ConcurrentHashMap<RefreshRequest, Long>()
    private val stateLock = Any()
    private val pendingRegistration = mutableMapOf<RefreshRequest, CompletableFuture<Unit>>()
    private val hintedPaths = mutableMapOf<RefreshRequest, MutableMap<Path, Long>>()
    private val uncertainTopology = mutableMapOf<RefreshRequest, Long>()
    private val clusterPaths = mutableMapOf<RefreshRequest, MutableSet<Path>>()
    private val lastChangeNanos = mutableMapOf<RefreshRequest, Long>()
    private val warmParents = mutableMapOf<RefreshRequest, MutableSet<Path>>()

    private data class PendingParent(
        val known: Set<Path>,
        val unknown: Set<Path>,
        val dirtyEpoch: Long,
    )

    private data class LaunchState(
        val topology: TopologyResult?,
        val paths: Set<Path>,
        val coverageEpoch: Long,
        val parent: PendingParent?,
    )

    private val pendingParent = mutableMapOf<RefreshRequest, PendingParent>()
    private val observationLock = Any()
    private val nativeEvents = ArrayDeque<Pair<Long, Path?>>()
    private val nativeEpoch = AtomicLong()
    private val registrationEpoch = mutableMapOf<RefreshRequest, Long>()
    private val captureEpoch = mutableMapOf<RefreshRequest, Pair<String, Long>>()
    private val topologyResolutionEpoch = mutableMapOf<RefreshRequest, Pair<String, Long>>()
    private val pendingTopology = mutableMapOf<RefreshRequest, TopologyResult>()
    private val pendingHintedPaths = mutableMapOf<RefreshRequest, Set<Path>>()
    private val coverageEpoch = AtomicLong()
    private val retryAttempts = ConcurrentHashMap<RefreshRequest, Int>()
    private val uncovered = ConcurrentHashMap.newKeySet<RefreshRequest>()
    // Reverse index of [keys]; a linear scan per registration is quadratic on large closures.
    private val keyByDirectory = ConcurrentHashMap<Path, WatchKey>()
    private val nativeCoverageLost = AtomicBoolean()
    private val macWatcher: NativeWorkspaceWatcher? =
        runCatching {
                when {
                    nativeWorkspaceWatcher != null ->
                        nativeWorkspaceWatcher.invoke(
                            workspace,
                            ::onMacPathChanged,
                            ::onWatcherOverflow,
                        )
                    watcherKind == AutoRefreshWatcherKind.MAC_FSEVENTS ->
                        MacFseventsWatcher(workspace, ::onMacPathChanged, ::onWatcherOverflow)
                    else -> null
                }
            }
            .onFailure {
                nativeCoverageLost.set(true)
                System.getLogger(AutoRefreshController::class.java.name)
                    .log(
                        System.Logger.Level.WARNING,
                        "Native workspace watcher failed for $workspace; using fallback watches",
                        it,
                    )
            }
            .getOrNull()
    // FSEvents is the primary macOS transport and recursively covers the workspace. WatchService
    // supplies coverage elsewhere; on macOS it polls, rescanning each registered directory.
    private val watchService: WatchService? = FileSystems.getDefault().newWatchService()
    private val watcher: Thread? = watchService?.let {
        Thread(::watch, "indexino-source-watcher").apply {
            isDaemon = true
            start()
        }
    }

    /** Flush outside stateLock before discovery so a seed cannot ignore a delayed old callback. */
    internal fun beforeDiscoveryFence(): Long? {
        val flush =
            (macWatcher as? MacFseventsWatcher)?.let { it::flushForTests }
                ?: nativeFlushForTests
                ?: return null
        return runCatching {
                flush()
                nativeEpoch.get()
            }
            .getOrNull()
    }

    /** Called before source bytes are captured, never after the generation is published. */
    internal fun captureWarmParents(
        request: RefreshRequest,
        sources: List<IndexedSource>,
    ): Map<Path, TentativeParentInventory> {
        val parents =
            synchronized(stateLock) { warmParents[request].orEmpty().take(MAX_ACTIVE_PARENTS) }
        return parents
            .mapNotNull { parent ->
                TentativeParentInventory.capture(parent, sources)?.let { parent to it }
            }
            .toMap()
    }

    init {
        scheduler.scheduleWithFixedDelay(
            {
                checkNativeCoverage()
                uncovered.forEach { request ->
                    if (!queued.contains(request) && !active.containsKey(request)) enqueue(request)
                }
            },
            reconciliationIntervalMillis,
            reconciliationIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    @Suppress("CyclomaticComplexMethod")
    fun register(
        request: RefreshRequest,
        sources: List<IndexedSource>,
        topologyRoots: List<Path> = emptyList(),
    ) {
        if (closed.get()) return
        checkNativeCoverage()
        sourcePathsByRequest[request] =
            sources.mapTo(linkedSetOf()) { it.originRoot.resolve(it.path).normalize() }
        val topologyInputs = topologyInputs(sources).toSet()
        topologyInputsByRequest[request] = topologyInputs
        val directories =
            buildSet {
                    sources.forEach { source ->
                        val sourcePath = source.originRoot.resolve(source.path).normalize()
                        require(sourcePath.startsWith(source.originRoot)) {
                            "Indexed source escapes its origin root: ${source.path}"
                        }
                        sourcePath.parent?.let(::add)
                        sourceRoot(sourcePath, source.originRoot)?.let(::add)
                    }
                    topologyRoots.filter(Files::isDirectory).forEach { root ->
                        add(root)
                        discoverSourceRoots(root).forEach { sourceRoot ->
                            discoverDirectories(sourceRoot).forEach(::add)
                        }
                    }
                    topologyInputs.mapTo(this) { it.parent ?: workspace }
                }
                .filter { directory -> Files.isDirectory(directory) && !excluded(directory) }
                .toSet()
        val previousDirectories = directoriesByRequest.put(request, directories).orEmpty()
        (previousDirectories - directories).forEach { directory ->
            removeRequestFromDirectory(directory, request)
        }
        val linkedRoutes = mutableMapOf<Path, Boolean>()
        fun linkedRoute(path: Path): Boolean =
            linkedRoutes.getOrPut(path) {
                Files.isSymbolicLink(path) ||
                    (path != workspace &&
                        path.parent?.takeIf { it.startsWith(workspace) }?.let(::linkedRoute) ==
                            true)
            }
        // The native stream observes only workspace paths; an external symlink target can change
        // without any callback, and an internal target callback need not name its lexical alias.
        var coverageComplete =
            directories.isNotEmpty() && sourcePathsByRequest[request].orEmpty().none(::linkedRoute)
        directories.forEach { directory ->
            if (
                !nativelyCovered(directory) &&
                    keys.size >= maxWatchedDirectories &&
                    !keyByDirectory.containsKey(directory)
            ) {
                coverageComplete = false
            } else {
                requestsByDirectory
                    .computeIfAbsent(directory) { ConcurrentHashMap.newKeySet() }
                    .add(request)
                if (!registerDirectory(directory)) coverageComplete = false
            }
        }
        if (coverageComplete && !nativeCoverageLost.get()) uncovered.remove(request)
        else uncovered.add(request)
        // IndexBuildRunner calls this before capturing source bytes. Earlier source edits are
        // covered by that capture; topology edits use the earlier resolution boundary instead.
        synchronized(stateLock) {
            val epoch = dirtyEpoch[request]?.get() ?: 0L
            val id = active[request]
            if (id == null) registrationEpoch[request] = epoch
            else captureEpoch[request] = id to epoch
        }
    }

    internal fun directoriesForTests(request: RefreshRequest): Set<Path> =
        directoriesByRequest[request].orEmpty()

    internal fun polledDirectoriesForTests(): Set<Path> = keys.values.toSet()

    internal fun dirtyEpochForTests(request: RefreshRequest): Long =
        dirtyEpoch[request]?.get() ?: 0L

    internal fun onPathChangedForTests(path: Path) = onMacPathChanged(path)

    internal fun onWatcherOverflowForTests() = onWatcherOverflow()

    fun onTopologyResolutionStarted(request: RefreshRequest, refreshId: String) {
        synchronized(stateLock) {
            topologyResolutionEpoch[request] = refreshId to (dirtyEpoch[request]?.get() ?: 0L)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    fun onRefreshStarted(
        request: RefreshRequest,
        handle: RefreshHandle,
        automatic: Boolean = true,
    ) {
        val (epochAtStart, automaticRefresh) =
            synchronized(stateLock) {
                if (active[request] == handle.id.value) return
                active[request] = handle.id.value
                val automaticEpoch = pendingLaunch.remove(request)
                pendingRegistration.remove(request)?.complete(Unit)
                val armedEpoch = registrationEpoch.remove(request)
                if (automaticEpoch != null && !automatic) {
                    // The explicit worker may have captured its input before the queued edit.
                    // Its coalesced automatic handle cannot prove that this edit was indexed.
                    dirty.add(request)
                    uncertainTopology[request] = dirtyEpoch[request]?.get() ?: 0L
                }
                val epoch =
                    if (automaticEpoch != null && !automatic) automaticEpoch - 1
                    else automaticEpoch ?: armedEpoch ?: (dirtyEpoch[request]?.get() ?: 0L)
                epoch to (automaticEpoch != null && automatic)
            }
        scheduler.execute {
            var succeeded = false
            try {
                kotlinx.coroutines.runBlocking { handle.await() }
                succeeded = true
            } catch (_: Exception) {
                // Automatic failures retain the dirty epoch for bounded retry.
            } finally {
                synchronized(stateLock) {
                    if (active.remove(request, handle.id.value)) {
                        val captured = captureEpoch.remove(request)
                        val coveredEpoch =
                            if (captured?.first == handle.id.value)
                                maxOf(epochAtStart, captured.second)
                            else epochAtStart
                        val topologyStart =
                            topologyResolutionEpoch[request]?.takeIf { it.first == handle.id.value }
                        if (topologyStart != null) topologyResolutionEpoch.remove(request)
                        val topologyCoveredEpoch = topologyStart?.second ?: epochAtStart
                        if (succeeded) {
                            // Keep a path if it was touched again after the full capture began.
                            hintedPaths[request]?.let { paths ->
                                paths.entries.removeIf { it.value <= coveredEpoch }
                                if (paths.isEmpty()) hintedPaths.remove(request)
                            }
                            if (
                                (uncertainTopology[request] ?: Long.MAX_VALUE) <=
                                    topologyCoveredEpoch
                            )
                                uncertainTopology.remove(request)
                        }
                        val newerChange =
                            (dirtyEpoch[request]?.get() ?: 0L) > coveredEpoch ||
                                (uncertainTopology[request]?.let { it > topologyCoveredEpoch } ==
                                    true)
                        if (succeeded && !newerChange) {
                            dirty.remove(request)
                            retryAttempts.remove(request)
                        } else {
                            if (newerChange || automaticRefresh) dirty.add(request)
                            if (!succeeded && automaticRefresh) scheduleRetry(request)
                            else if (
                                mode != AutoRefreshMode.DISABLED &&
                                    dirty.contains(request) &&
                                    queued.add(request)
                            ) {
                                // The path and uncertainty from events during this refresh are
                                // already recorded. A pathless enqueue would discard a safe hint
                                // and force the successor through full topology reconciliation.
                                scheduleDebounced(
                                    request,
                                    dirtyEpoch[request]?.get() ?: 0L,
                                    System.nanoTime(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun startQueuedForAwaitCurrent() {
        if (mode == AutoRefreshMode.DISABLED) return
        checkNativeCoverage()
        dirty.toList().forEach { request ->
            val launch =
                synchronized(stateLock) {
                    if (active.containsKey(request) || pendingLaunch.containsKey(request)) false
                    else {
                        queued.remove(request)
                        claimLaunch(request)
                    }
                }
            if (launch) launch(request)
        }
        // Claiming removes the dirty bit before the coordinator has registered its handle.
        // Do not let snapshot(AWAIT_CURRENT) pass through that invisible handoff.
        val registrations = synchronized(stateLock) { pendingRegistration.values.toList() }
        registrations.forEach { it.join() }
    }

    fun freshness(freshness: FreshnessPolicy): SnapshotFreshness? {
        checkNativeCoverage()
        return when {
            directoriesByRequest.isEmpty() -> SnapshotFreshness.UNKNOWN
            nativeCoverageLost.get() -> SnapshotFreshness.UNKNOWN
            uncovered.isNotEmpty() -> SnapshotFreshness.UNKNOWN
            dirty.isNotEmpty() || pendingLaunch.isNotEmpty() -> SnapshotFreshness.DIRTY
            freshness == FreshnessPolicy.AWAIT_CURRENT -> SnapshotFreshness.CURRENT
            else -> null // Clean PUBLISHED retains the generation's own freshness evidence.
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        watcher?.interrupt()
        runCatching { watchService?.close() }
        macWatcher?.close()
        scheduler.shutdownNow()
        keys.clear()
        keyByDirectory.clear()
        requestsByDirectory.clear()
        directoriesByRequest.clear()
        sourcePathsByRequest.clear()
        topologyInputsByRequest.clear()
        uncovered.clear()
        dirtyEpoch.clear()
        synchronized(stateLock) {
            pendingRegistration.values.forEach {
                it.completeExceptionally(IllegalStateException("Auto-refresh controller closed"))
            }
            pendingRegistration.clear()
            pendingLaunch.clear()
            hintedPaths.clear()
            uncertainTopology.clear()
            clusterPaths.clear()
            lastChangeNanos.clear()
            warmParents.clear()
            pendingParent.clear()
            registrationEpoch.clear()
            captureEpoch.clear()
            topologyResolutionEpoch.clear()
            pendingTopology.clear()
            pendingHintedPaths.clear()
        }
    }

    private fun watch() {
        while (!closed.get()) {
            val key =
                try {
                    checkNotNull(watchService).take()
                } catch (_: InterruptedException) {
                    return
                } catch (_: java.nio.file.ClosedWatchServiceException) {
                    return
                }
            val directory = keys[key]
            if (directory != null) {
                key.pollEvents().forEach { event -> onWatchEvent(directory, event) }
            }
            if (!key.reset()) {
                keys.remove(key)?.let { invalidDirectory ->
                    keyByDirectory.remove(invalidDirectory, key)
                    requestsByDirectory[invalidDirectory]?.let(::invalidateCoverage)
                }
            }
        }
    }

    private fun onWatchEvent(directory: Path, event: WatchEvent<*>) {
        when (event.kind()) {
            OVERFLOW -> onWatcherOverflow()
            ENTRY_CREATE,
            ENTRY_DELETE,
            ENTRY_MODIFY -> {
                val path = directory.resolve(event.context() as Path).normalize()
                // A polled `.git` entry changes mtime on every index lock.
                val gitChurn =
                    event.kind() == ENTRY_MODIFY && path.fileName?.toString() == GIT_DIRECTORY
                if (!ignoredEvent(path) && !gitChurn) {
                    requestsByDirectory[directory]?.forEach { enqueue(it, path) }
                }
            }
        }
    }

    private fun enqueue(request: RefreshRequest, path: Path? = null) {
        synchronized(stateLock) {
            if (closed.get()) return
            val epoch = dirtyEpoch.computeIfAbsent(request) { AtomicLong() }.incrementAndGet()
            val changedAt = System.nanoTime()
            lastChangeNanos[request] = changedAt
            if (
                path == null ||
                    path in topologyInputsByRequest[request].orEmpty() ||
                    path !in sourcePathsByRequest[request].orEmpty()
            )
                uncertainTopology[request] = epoch
            else hintedPaths.getOrPut(request) { linkedMapOf() }[path] = epoch
            val paths = clusterPaths.getOrPut(request) { linkedSetOf() }
            if (paths.size <= MAX_LOCAL_CLUSTER_PATHS) paths.add(path ?: workspace)
            dirty.add(request)
            retryAttempts.remove(request)
            if (
                mode == AutoRefreshMode.DISABLED ||
                    active.containsKey(request) ||
                    pendingLaunch.containsKey(request) ||
                    !queued.add(request)
            )
                return
            scheduleDebounced(request, epoch, changedAt)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun claimLaunch(request: RefreshRequest): Boolean {
        checkNativeCoverage()
        if (
            closed.get() ||
                pendingLaunch.containsKey(request) ||
                active.containsKey(request) ||
                !dirty.remove(request)
        )
            return false
        lastChangeNanos.remove(request)
        pendingLaunch[request] = dirtyEpoch[request]?.get() ?: 0L
        pendingRegistration[request] = CompletableFuture()
        val paths = hintedPaths.remove(request)?.keys.orEmpty()
        val cluster = clusterPaths.remove(request).orEmpty()
        val uncertain = uncertainTopology.remove(request) != null
        if (uncertain && request !in uncovered && nativeCoverageAvailable()) {
            val unknown = cluster - paths.toSet()
            if (
                unknown.isNotEmpty() &&
                    unknown.none { it in topologyInputsByRequest[request].orEmpty() } &&
                    paths.isNotEmpty() &&
                    (unknown + paths).map { it.parent }.distinct().size == 1 &&
                    cluster.size <= MAX_LOCAL_CLUSTER_PATHS
            ) {
                pendingParent[request] =
                    PendingParent(paths.toSet(), unknown, pendingLaunch.getValue(request))
            }
        }
        if (uncertain || nativeCoverageLost.get() || request in uncovered) return true
        if (
            paths.isNotEmpty() &&
                paths.all {
                    it in sourcePathsByRequest[request].orEmpty() &&
                        Files.isRegularFile(it) &&
                        !linkedSourceRoute(it)
                }
        ) {
            topologyProvider(request)?.let { topology ->
                pendingTopology[request] = topology
                // Mac WatchService polling does not prove source bytes unchanged on external
                // mounts; the native FSEvents stream only covers the workspace tree.
                pendingHintedPaths[request] =
                    if (
                        watcherKind != AutoRefreshWatcherKind.MAC_FSEVENTS ||
                            (nativeCoverageAvailable() &&
                                sourcePathsByRequest[request].orEmpty().all {
                                    it.startsWith(workspace)
                                })
                    )
                        paths.toSet()
                    else emptySet()
            }
        }
        return true
    }

    @Suppress("CyclomaticComplexMethod", "ComplexCondition")
    private fun launch(request: RefreshRequest) {
        checkNativeCoverage()
        val state =
            synchronized(stateLock) {
                val invalidated =
                    nativeCoverageLost.get() || request in uncovered || request in uncertainTopology
                val topology = pendingTopology.remove(request)
                val paths = pendingHintedPaths.remove(request).orEmpty()
                LaunchState(
                    if (invalidated) null else topology,
                    if (invalidated) emptySet() else paths,
                    coverageEpoch.get(),
                    pendingParent.remove(request),
                )
            }
        try {
            val nativeCandidate = state.parent
            val provisional =
                if (nativeCandidate != null && nativeCoverageAvailable()) {
                    val before = beforeDiscoveryFence()
                    val hint =
                        if (before != null)
                            tentativeParentHint?.invoke(
                                request,
                                nativeCandidate.unknown,
                                nativeCandidate.known,
                            )
                        else null
                    val after = beforeDiscoveryFence()
                    val acceptedEpoch =
                        if (hint != null && after != null)
                            eventsSince(
                                hint.observationEpoch,
                                nativeCandidate.known + nativeCandidate.unknown,
                            )
                        else null
                    if (
                        hint != null &&
                            acceptedEpoch != null &&
                            nativeCoverageAvailable() &&
                            nativeEpoch.get() == acceptedEpoch &&
                            dirtyEpoch[request]?.get() == nativeCandidate.dirtyEpoch &&
                            request !in uncovered &&
                            coverageEpoch.get() == state.coverageEpoch
                    )
                        hint to acceptedEpoch
                    else null
                } else null
            if (provisional != null && refreshWithTopology != null) {
                val (hint, acceptedEpoch) = provisional
                refreshWithTopology.invoke(
                    request,
                    hint.topology,
                    hint.paths,
                    {
                        checkNativeCoverage()
                        !closed.get() &&
                            nativeCoverageAvailable() &&
                            request !in uncovered &&
                            coverageEpoch.get() == state.coverageEpoch &&
                            nativeEpoch.get() == acceptedEpoch
                    },
                    workspace,
                    true,
                )
                return
            }
            if (state.topology != null && refreshWithTopology != null)
                refreshWithTopology.invoke(
                    request,
                    state.topology,
                    state.paths,
                    {
                        checkNativeCoverage()
                        !closed.get() &&
                            !nativeCoverageLost.get() &&
                            request !in uncovered &&
                            coverageEpoch.get() == state.coverageEpoch
                    },
                    // Only the native stream reports every path under the workspace; per-directory
                    // WatchService keys miss directories without sources.
                    workspace.takeIf { nativeCoverageAvailable() },
                    false,
                )
            else refresh(request)
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            synchronized(stateLock) {
                pendingLaunch.remove(request)
                pendingRegistration.remove(request)?.completeExceptionally(failure)
                dirty.add(request)
                uncertainTopology[request] = dirtyEpoch[request]?.get() ?: 0L
            }
            throw failure
        }
    }

    private fun scheduleDebounced(
        request: RefreshRequest,
        epoch: Long,
        firstChangeNanos: Long,
        delayNanos: Long = TimeUnit.MILLISECONDS.toNanos(DEBOUNCE_MILLIS),
    ) {
        scheduler.schedule(
            {
                val launch =
                    synchronized(stateLock) {
                        val latestEpoch = dirtyEpoch[request]?.get() ?: epoch
                        val now = System.nanoTime()
                        val remainingQuietNanos =
                            TimeUnit.MILLISECONDS.toNanos(DEBOUNCE_MILLIS) -
                                (now - (lastChangeNanos[request] ?: firstChangeNanos))
                        if (
                            !closed.get() &&
                                queued.contains(request) &&
                                latestEpoch != epoch &&
                                now - firstChangeNanos < maxDebounceNanos &&
                                remainingQuietNanos > 0
                        ) {
                            scheduleDebounced(
                                request,
                                latestEpoch,
                                firstChangeNanos,
                                remainingQuietNanos,
                            )
                            false
                        } else {
                            queued.remove(request) && claimLaunch(request)
                        }
                    }
                if (launch) launch(request)
            },
            delayNanos,
            TimeUnit.NANOSECONDS,
        )
    }

    private fun linkedSourceRoute(path: Path): Boolean =
        generateSequence(path) { it.parent }
            .takeWhile { it.startsWith(workspace) }
            .any(Files::isSymbolicLink)

    private fun scheduleRetry(request: RefreshRequest) {
        uncertainTopology[request] = dirtyEpoch[request]?.get() ?: 0L
        val attempt =
            retryAttempts.compute(request) { _, previous -> (previous ?: 0) + 1 } ?: return
        if (
            attempt > MAX_RETRY_ATTEMPTS || mode == AutoRefreshMode.DISABLED || !queued.add(request)
        ) {
            return
        }
        scheduler.schedule(
            {
                val launch =
                    synchronized(stateLock) { queued.remove(request) && claimLaunch(request) }
                if (launch) launch(request)
            },
            RETRY_DELAYS_MILLIS[attempt - 1],
            TimeUnit.MILLISECONDS,
        )
    }

    private fun removeRequestFromDirectory(directory: Path, request: RefreshRequest) {
        val requests = requestsByDirectory[directory] ?: return
        requests.remove(request)
        if (requests.isNotEmpty() || !requestsByDirectory.remove(directory, requests)) return
        keyByDirectory.remove(directory)?.let { key ->
            keys.remove(key)
            key.cancel()
        }
    }

    private fun discoverSourceRoots(root: Path): Set<Path> =
        discoverDirectories(root, MAX_MODULE_DISCOVERY_DEPTH).filterTo(linkedSetOf()) { directory ->
            directory.fileName.toString() in SOURCE_ROOT_NAMES &&
                directory.parent?.parent?.fileName?.toString() == "src"
        }

    private fun discoverDirectories(root: Path, maxDepth: Int = Int.MAX_VALUE): Set<Path> =
        buildSet {
            runCatching {
                Files.walkFileTree(
                    root,
                    emptySet(),
                    maxDepth,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(
                            directory: Path,
                            attributes: BasicFileAttributes,
                        ): FileVisitResult {
                            if (excluded(directory)) return FileVisitResult.SKIP_SUBTREE
                            add(directory)
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
            }
        }

    private fun nativelyCovered(directory: Path): Boolean =
        nativeCoverageAvailable() && directory.startsWith(workspace)

    private fun nativeCoverageAvailable(): Boolean =
        !nativeCoverageLost.get() && macWatcher?.isAlive == true

    private fun checkNativeCoverage() {
        if (closed.get() || macWatcher == null || macWatcher.isAlive) return
        if (!nativeCoverageLost.compareAndSet(false, true)) return
        System.getLogger(AutoRefreshController::class.java.name)
            .log(
                System.Logger.Level.WARNING,
                "Native workspace watcher exited for $workspace; reconciling with fallback watches",
            )
        invalidateCoverage(directoriesByRequest.keys)
        // Keep scopes uncovered until recursive native coverage is restored. On macOS these
        // fallback keys poll, so they cannot justify inherited hashes even after a full capture.
        directoriesByRequest.values.flatten().distinct().forEach { directory ->
            if (keys.size < maxWatchedDirectories) registerDirectory(directory)
        }
    }

    private fun registerDirectory(directory: Path): Boolean {
        if (nativelyCovered(directory)) return true
        if (watchService == null) return false
        if (keyByDirectory.containsKey(directory)) return true
        return try {
            val key = directory.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
            keys[key] = directory
            keyByDirectory[directory] = key
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun onMacPathChanged(path: Path) {
        val changed = path.normalize()
        if (ignoredEvent(changed)) return
        recordNative(changed)
        val affected = linkedSetOf<RefreshRequest>()
        generateSequence(changed) { it.parent }
            .forEach { directory -> requestsByDirectory[directory]?.let(affected::addAll) }
        affected.forEach { enqueue(it, changed) }
        if (affected.any { changed in sourcePathsByRequest[it].orEmpty() }) {
            synchronized(stateLock) {
                affected
                    .filter { changed in sourcePathsByRequest[it].orEmpty() }
                    .forEach { request ->
                        warmParents
                            .getOrPut(request) { linkedSetOf() }
                            .let { parents ->
                                if (parents.size < MAX_ACTIVE_PARENTS) parents.add(changed.parent)
                            }
                    }
            }
        }
    }

    private fun onWatcherOverflow() {
        recordNative(null)
        invalidateCoverage(directoriesByRequest.keys)
    }

    private fun recordNative(path: Path?) {
        synchronized(observationLock) {
            nativeEvents.addLast(nativeEpoch.incrementAndGet() to path)
            if (nativeEvents.size > MAX_OBSERVED_EVENTS) nativeEvents.removeFirst()
        }
    }

    private fun eventsSince(epoch: Long, allowed: Set<Path>): Long? =
        synchronized(observationLock) {
            val first = nativeEvents.firstOrNull()?.first ?: return@synchronized null
            if (first > epoch + 1) return@synchronized null
            if (nativeEvents.asSequence().filter { it.first > epoch }.any { it.second !in allowed })
                return@synchronized null
            nativeEpoch.get()
        }

    private fun invalidateCoverage(affected: Collection<RefreshRequest>) {
        val requests = affected.toList()
        coverageEpoch.incrementAndGet()
        uncovered.addAll(requests)
        requests.forEach { enqueue(it) }
    }

    private fun sourceRoot(sourcePath: Path, originRoot: Path): Path? =
        generateSequence(sourcePath.parent) { current -> current.parent }
            .takeWhile { current -> current.startsWith(originRoot) }
            .firstOrNull { current ->
                current.fileName.toString() in SOURCE_ROOT_NAMES &&
                    current.parent?.parent?.fileName?.toString() == "src"
            }

    private fun topologyInputs(sources: List<IndexedSource>): List<Path> =
        buildSet {
                addTopologyInputs(this, workspace)
                sources.forEach { source ->
                    val sourcePath = source.originRoot.resolve(source.path).normalize()
                    generateSequence(sourcePath.parent) { current -> current.parent }
                        .takeWhile { current -> current.startsWith(source.originRoot) }
                        .forEach { directory -> addTopologyInputs(this, directory) }
                }
            }
            .toList()

    private fun addTopologyInputs(inputs: MutableSet<Path>, directory: Path) {
        inputs.add(directory.resolve("settings.gradle.kts"))
        inputs.add(directory.resolve("settings.gradle"))
        inputs.add(directory.resolve("build.gradle.kts"))
        inputs.add(directory.resolve("build.gradle"))
        inputs.add(directory.resolve("MODULE.bazel"))
        inputs.add(directory.resolve("WORKSPACE"))
        inputs.add(directory.resolve("WORKSPACE.bazel"))
        inputs.add(directory.resolve("BUILD"))
        inputs.add(directory.resolve("BUILD.bazel"))
    }

    private fun excluded(path: Path): Boolean =
        path.any { segment -> segment.toString() == GIT_DIRECTORY } ||
            path.startsWith(dev.sebastiano.indexino.api.InProcessCacheLayout.cacheRoot())

    /**
     * Git's own bookkeeping (index refreshes by status/diff during origin resolution) and cache
     * writes are not source edits. A `.git` entry itself still counts: a repository appearing or
     * disappearing changes origin boundaries.
     */
    private fun ignoredEvent(path: Path): Boolean =
        path.parent?.any { segment -> segment.toString() == GIT_DIRECTORY } == true ||
            path.startsWith(dev.sebastiano.indexino.api.InProcessCacheLayout.cacheRoot())

    private companion object {
        const val GIT_DIRECTORY = ".git"
        const val DEBOUNCE_MILLIS = 150L
        const val MAX_LOCAL_CLUSTER_PATHS = 5
        const val MAX_ACTIVE_PARENTS = 64
        const val MAX_OBSERVED_EVENTS = 256
        val MAX_DEBOUNCE_NANOS = TimeUnit.SECONDS.toNanos(30)
        const val RECONCILIATION_INTERVAL_MILLIS = 30_000L
        const val MAX_RETRY_ATTEMPTS = 3
        const val MAX_MODULE_DISCOVERY_DEPTH = 6
        val RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 5_000L, 30_000L)
        val SOURCE_ROOT_NAMES = setOf("kotlin", "java", "resources", "res", "composeResources")
    }
}
