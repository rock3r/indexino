package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.FreshnessPolicy
import dev.sebastiano.indexino.api.RefreshHandle
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.SnapshotFreshness
import dev.sebastiano.indexino.producer.IndexedSource
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
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Runtime-owned, scope-derived watcher that coalesces filesystem hints into refresh requests. */
@Suppress("TooManyFunctions")
internal class AutoRefreshController(
    private val workspace: Path,
    private val mode: AutoRefreshMode,
    private val refresh: (RefreshRequest) -> Unit,
    private val maxWatchedDirectories: Int = Int.MAX_VALUE,
    private val reconciliationIntervalMillis: Long = RECONCILIATION_INTERVAL_MILLIS,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val watcherKind = AutoRefreshWatcherFactory.configuredKind()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "indexino-auto-refresh").apply { isDaemon = true }
    }
    private val requestsByDirectory = ConcurrentHashMap<Path, MutableSet<RefreshRequest>>()
    private val directoriesByRequest = ConcurrentHashMap<RefreshRequest, Set<Path>>()
    private val keys = ConcurrentHashMap<WatchKey, Path>()
    private val queued = ConcurrentHashMap.newKeySet<RefreshRequest>()
    private val dirty = ConcurrentHashMap.newKeySet<RefreshRequest>()
    private val dirtyEpoch = ConcurrentHashMap<RefreshRequest, AtomicLong>()
    private val active = ConcurrentHashMap<RefreshRequest, String>()
    private val automatic = ConcurrentHashMap.newKeySet<RefreshRequest>()
    private val retryAttempts = ConcurrentHashMap<RefreshRequest, Int>()
    private val uncovered = ConcurrentHashMap.newKeySet<RefreshRequest>()
    private val macWatcher: MacFseventsWatcher? =
        if (watcherKind == AutoRefreshWatcherKind.MAC_FSEVENTS) {
            runCatching { MacFseventsWatcher(workspace, ::onMacPathChanged, ::onWatcherOverflow) }
                .getOrNull()
        } else {
            null
        }
    // FSEvents is the primary macOS transport; WatchService supplies portable fallback coverage.
    private val watchService: WatchService? = FileSystems.getDefault().newWatchService()
    private val watcher: Thread? = watchService?.let {
        Thread(::watch, "indexino-source-watcher").apply {
            isDaemon = true
            start()
        }
    }

    init {
        scheduler.scheduleWithFixedDelay(
            { uncovered.forEach(::enqueue) },
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
            if (keys.size >= maxWatchedDirectories && keys.values.none { it == directory }) {
                coverageComplete = false
            } else {
                requestsByDirectory
                    .computeIfAbsent(directory) { ConcurrentHashMap.newKeySet() }
                    .add(request)
                if (!registerDirectory(directory)) coverageComplete = false
            }
        }
        if (coverageComplete) uncovered.remove(request) else uncovered.add(request)
    }

    internal fun directoriesForTests(request: RefreshRequest): Set<Path> =
        directoriesByRequest[request].orEmpty()

    internal fun onPathChangedForTests(path: Path) = onMacPathChanged(path)

    fun onRefreshStarted(request: RefreshRequest, handle: RefreshHandle) {
        active[request] = handle.id.value
        val epochAtStart = dirtyEpoch[request]?.get() ?: 0L
        val automaticRefresh = automatic.remove(request)
        scheduler.execute {
            var succeeded = false
            try {
                kotlinx.coroutines.runBlocking { handle.await() }
                succeeded = true
            } catch (_: Exception) {
                if (automaticRefresh) {
                    dirty.add(request)
                    scheduleRetry(request)
                }
            } finally {
                active.remove(request, handle.id.value)
                val newerChange = (dirtyEpoch[request]?.get() ?: 0L) > epochAtStart
                if (succeeded && !newerChange) {
                    dirty.remove(request)
                    retryAttempts.remove(request)
                } else if (dirty.contains(request) && (succeeded || !automaticRefresh)) {
                    enqueue(request)
                }
            }
        }
    }

    fun startQueuedForAwaitCurrent() {
        if (mode == AutoRefreshMode.DISABLED) return
        dirty.toList().forEach { request ->
            if (active.containsKey(request)) return@forEach
            queued.remove(request)
            automatic.add(request)
            refresh(request)
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
        requestsByDirectory.clear()
        directoriesByRequest.clear()
        uncovered.clear()
        dirtyEpoch.clear()
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
                key.pollEvents().forEach { event ->
                    when (event.kind()) {
                        OVERFLOW -> onWatcherOverflow()
                        ENTRY_CREATE,
                        ENTRY_DELETE,
                        ENTRY_MODIFY -> requestsByDirectory[directory]?.forEach(::enqueue)
                    }
                }
            }
            if (!key.reset()) {
                keys.remove(key)?.let { invalidDirectory ->
                    requestsByDirectory[invalidDirectory]?.let(uncovered::addAll)
                }
            }
        }
    }

    private fun enqueue(request: RefreshRequest) {
        if (closed.get()) return
        dirty.add(request)
        dirtyEpoch.computeIfAbsent(request) { AtomicLong() }.incrementAndGet()
        retryAttempts.remove(request)
        if (mode == AutoRefreshMode.DISABLED || active.containsKey(request) || !queued.add(request))
            return
        scheduler.schedule(
            {
                queued.remove(request)
                if (!closed.get() && dirty.remove(request)) {
                    automatic.add(request)
                    refresh(request)
                }
            },
            DEBOUNCE_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun scheduleRetry(request: RefreshRequest) {
        val attempt =
            retryAttempts.compute(request) { _, previous -> (previous ?: 0) + 1 } ?: return
        if (
            attempt > MAX_RETRY_ATTEMPTS || mode == AutoRefreshMode.DISABLED || !queued.add(request)
        ) {
            return
        }
        scheduler.schedule(
            {
                queued.remove(request)
                if (!closed.get() && dirty.contains(request) && !active.containsKey(request)) {
                    automatic.add(request)
                    refresh(request)
                }
            },
            RETRY_DELAYS_MILLIS[attempt - 1],
            TimeUnit.MILLISECONDS,
        )
    }

    private fun removeRequestFromDirectory(directory: Path, request: RefreshRequest) {
        val requests = requestsByDirectory[directory] ?: return
        requests.remove(request)
        if (requests.isNotEmpty() || !requestsByDirectory.remove(directory, requests)) return
        keys.entries
            .filter { (_, registered) -> registered == directory }
            .forEach { (key, _) ->
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

    private fun registerDirectory(directory: Path): Boolean {
        if (watchService == null) return macWatcher != null
        if (keys.values.any { registered -> registered == directory }) return true
        return try {
            val key = directory.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
            keys[key] = directory
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun onMacPathChanged(path: Path) {
        requestsByDirectory
            .filterKeys { directory -> path.startsWith(directory) }
            .values
            .flatten()
            .forEach(::enqueue)
    }

    private fun onWatcherOverflow() {
        uncovered.addAll(directoriesByRequest.keys)
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
        path.any { segment -> segment.toString() == ".git" } ||
            path.startsWith(dev.sebastiano.indexino.api.InProcessCacheLayout.cacheRoot())

    private companion object {
        const val DEBOUNCE_MILLIS = 150L
        const val RECONCILIATION_INTERVAL_MILLIS = 30_000L
        const val MAX_RETRY_ATTEMPTS = 3
        const val MAX_MODULE_DISCOVERY_DEPTH = 6
        val RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 5_000L, 30_000L)
        val SOURCE_ROOT_NAMES = setOf("kotlin", "java", "resources", "res", "composeResources")
    }
}
