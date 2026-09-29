package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.FreshnessPolicy
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Starts a recursive native stream for a workspace: root, path callback, overflow callback. */
internal typealias NativeWorkspaceWatcherFactory =
    (Path, (Path) -> Unit, () -> Unit) -> AutoCloseable

/** Runtime-owned, scope-derived watcher that coalesces filesystem hints into refresh requests. */
@Suppress("TooManyFunctions")
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
        ((RefreshRequest, TopologyResult, Set<Path>, () -> Boolean, Path?) -> Unit)? =
        null,
    private val nativeWorkspaceWatcher: NativeWorkspaceWatcherFactory? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val watcherKind = AutoRefreshWatcherFactory.configuredKind()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "indexino-auto-refresh").apply { isDaemon = true }
    }
    private val requestsByDirectory = ConcurrentHashMap<Path, MutableSet<RefreshRequest>>()
    private val directoriesByRequest = ConcurrentHashMap<RefreshRequest, Set<Path>>()
    private val sourcePathsByRequest = ConcurrentHashMap<RefreshRequest, Set<Path>>()
    private val keys = ConcurrentHashMap<WatchKey, Path>()
    private val queued = ConcurrentHashMap.newKeySet<RefreshRequest>()
    private val dirty = ConcurrentHashMap.newKeySet<RefreshRequest>()
    private val dirtyEpoch = ConcurrentHashMap<RefreshRequest, AtomicLong>()
    private val active = ConcurrentHashMap<RefreshRequest, String>()
    private val pendingLaunch = ConcurrentHashMap<RefreshRequest, Long>()
    private val stateLock = Any()
    private val hintedPaths = mutableMapOf<RefreshRequest, MutableMap<Path, Long>>()
    private val uncertainTopology = mutableMapOf<RefreshRequest, Long>()
    private val registrationEpoch = mutableMapOf<RefreshRequest, Long>()
    private val captureEpoch = mutableMapOf<RefreshRequest, Pair<String, Long>>()
    private val pendingTopology = mutableMapOf<RefreshRequest, TopologyResult>()
    private val pendingHintedPaths = mutableMapOf<RefreshRequest, Set<Path>>()
    private val coverageEpoch = AtomicLong()
    private val retryAttempts = ConcurrentHashMap<RefreshRequest, Int>()
    private val uncovered = ConcurrentHashMap.newKeySet<RefreshRequest>()
    // Reverse index of [keys]; a linear scan per registration is quadratic on large closures.
    private val keyByDirectory = ConcurrentHashMap<Path, WatchKey>()
    private val macWatcher: AutoCloseable? =
        when {
            nativeWorkspaceWatcher != null ->
                nativeWorkspaceWatcher.invoke(workspace, ::onMacPathChanged, ::onWatcherOverflow)
            watcherKind == AutoRefreshWatcherKind.MAC_FSEVENTS ->
                runCatching {
                        MacFseventsWatcher(workspace, ::onMacPathChanged, ::onWatcherOverflow)
                    }
                    .getOrNull()
            else -> null
        }
    // FSEvents is the primary macOS transport and recursively covers the workspace. WatchService
    // supplies coverage elsewhere; on macOS it polls, rescanning each registered directory.
    private val watchService: WatchService? = FileSystems.getDefault().newWatchService()
    private val watcher: Thread? = watchService?.let {
        Thread(::watch, "indexino-source-watcher").apply {
            isDaemon = true
            start()
        }
    }

    init {
        scheduler.scheduleWithFixedDelay(
            {
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
        sourcePathsByRequest[request] =
            sources.mapTo(linkedSetOf()) { it.originRoot.resolve(it.path).normalize() }
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
                    topologyInputs(sources).mapTo(this) { it.parent ?: workspace }
                }
                .filter { directory -> Files.isDirectory(directory) && !excluded(directory) }
                .toSet()
        val previousDirectories = directoriesByRequest.put(request, directories).orEmpty()
        (previousDirectories - directories).forEach { directory ->
            removeRequestFromDirectory(directory, request)
        }
        var coverageComplete = directories.isNotEmpty()
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
        if (coverageComplete) uncovered.remove(request) else uncovered.add(request)
        // IndexBuildRunner calls this before capturing source bytes. Events observed before
        // registration completes are covered by that full capture; later events need a successor.
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
                        if (succeeded) {
                            // Keep a path if it was touched again after the full capture began.
                            hintedPaths[request]?.let { paths ->
                                paths.entries.removeIf { it.value <= coveredEpoch }
                                if (paths.isEmpty()) hintedPaths.remove(request)
                            }
                            if ((uncertainTopology[request] ?: Long.MAX_VALUE) <= coveredEpoch)
                                uncertainTopology.remove(request)
                        }
                        val newerChange = (dirtyEpoch[request]?.get() ?: 0L) > coveredEpoch
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
    }

    fun freshness(freshness: FreshnessPolicy): SnapshotFreshness =
        when {
            directoriesByRequest.isEmpty() -> SnapshotFreshness.UNKNOWN
            uncovered.isNotEmpty() -> SnapshotFreshness.UNKNOWN
            dirty.isNotEmpty() -> SnapshotFreshness.DIRTY
            freshness == FreshnessPolicy.AWAIT_CURRENT -> SnapshotFreshness.CURRENT
            else -> SnapshotFreshness.UNKNOWN
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
        uncovered.clear()
        dirtyEpoch.clear()
        synchronized(stateLock) {
            hintedPaths.clear()
            uncertainTopology.clear()
            registrationEpoch.clear()
            captureEpoch.clear()
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
            if (path == null || path !in sourcePathsByRequest[request].orEmpty())
                uncertainTopology[request] = epoch
            else hintedPaths.getOrPut(request) { linkedMapOf() }[path] = epoch
            dirty.add(request)
            retryAttempts.remove(request)
            if (
                mode == AutoRefreshMode.DISABLED ||
                    active.containsKey(request) ||
                    pendingLaunch.containsKey(request) ||
                    !queued.add(request)
            )
                return
            scheduleDebounced(request, epoch, System.nanoTime())
        }
    }

    private fun claimLaunch(request: RefreshRequest): Boolean {
        if (
            closed.get() ||
                pendingLaunch.containsKey(request) ||
                active.containsKey(request) ||
                !dirty.remove(request)
        )
            return false
        pendingLaunch[request] = dirtyEpoch[request]?.get() ?: 0L
        val paths = hintedPaths.remove(request)?.keys.orEmpty()
        val uncertain = uncertainTopology.remove(request) != null
        if (
            !uncertain &&
                request !in uncovered &&
                paths.isNotEmpty() &&
                paths.all {
                    it in sourcePathsByRequest[request].orEmpty() && Files.isRegularFile(it)
                }
        ) {
            topologyProvider(request)?.let { topology ->
                pendingTopology[request] = topology
                // Mac WatchService polling does not prove source bytes unchanged on external
                // mounts; the native FSEvents stream only covers the workspace tree.
                pendingHintedPaths[request] =
                    if (
                        watcherKind != AutoRefreshWatcherKind.MAC_FSEVENTS ||
                            (macWatcher != null &&
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

    private fun launch(request: RefreshRequest) {
        val (topology, paths, epoch) =
            synchronized(stateLock) {
                val invalidated = request in uncovered || request in uncertainTopology
                val topology = pendingTopology.remove(request)
                val paths = pendingHintedPaths.remove(request).orEmpty()
                Triple(
                    if (invalidated) null else topology,
                    if (invalidated) emptySet() else paths,
                    coverageEpoch.get(),
                )
            }
        if (topology != null && refreshWithTopology != null)
            refreshWithTopology.invoke(
                request,
                topology,
                paths,
                { !closed.get() && request !in uncovered && coverageEpoch.get() == epoch },
                // Only the native stream reports every path under the workspace; per-directory
                // WatchService keys miss directories without sources.
                workspace.takeIf { macWatcher != null },
            )
        else refresh(request)
    }

    private fun scheduleDebounced(request: RefreshRequest, epoch: Long, firstChangeNanos: Long) {
        scheduler.schedule(
            {
                val launch =
                    synchronized(stateLock) {
                        val latestEpoch = dirtyEpoch[request]?.get() ?: epoch
                        if (
                            !closed.get() &&
                                queued.contains(request) &&
                                latestEpoch != epoch &&
                                System.nanoTime() - firstChangeNanos < maxDebounceNanos
                        ) {
                            scheduleDebounced(request, latestEpoch, firstChangeNanos)
                            false
                        } else {
                            queued.remove(request) && claimLaunch(request)
                        }
                    }
                if (launch) launch(request)
            },
            DEBOUNCE_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

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
        macWatcher != null && directory.startsWith(workspace)

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
        val affected = linkedSetOf<RefreshRequest>()
        generateSequence(changed) { it.parent }
            .forEach { directory -> requestsByDirectory[directory]?.let(affected::addAll) }
        affected.forEach { enqueue(it, changed) }
    }

    private fun onWatcherOverflow() = invalidateCoverage(directoriesByRequest.keys)

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
        val MAX_DEBOUNCE_NANOS = TimeUnit.SECONDS.toNanos(30)
        const val RECONCILIATION_INTERVAL_MILLIS = 30_000L
        const val MAX_RETRY_ATTEMPTS = 3
        const val MAX_MODULE_DISCOVERY_DEPTH = 6
        val RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 5_000L, 30_000L)
        val SOURCE_ROOT_NAMES = setOf("kotlin", "java", "resources", "res", "composeResources")
    }
}
