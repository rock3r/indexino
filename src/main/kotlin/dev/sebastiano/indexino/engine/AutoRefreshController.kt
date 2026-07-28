package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.FreshnessPolicy
import dev.sebastiano.indexino.api.RefreshHandle
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.SnapshotFreshness
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Runtime-owned, scope-derived watcher that coalesces filesystem hints into refresh requests. */
internal class AutoRefreshController(
    private val workspace: Path,
    private val mode: AutoRefreshMode,
    private val refresh: (RefreshRequest) -> Unit,
    private val maxWatchedDirectories: Int = Int.MAX_VALUE,
    private val reconciliationIntervalMillis: Long = RECONCILIATION_INTERVAL_MILLIS,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val watcherKind = AutoRefreshWatcherFactory.kindForPlatform()
    private val watchService: WatchService? =
        if (watcherKind == AutoRefreshWatcherKind.MAC_FSEVENTS) null
        else FileSystems.getDefault().newWatchService()
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
    private val watcher: Thread? = watchService?.let {
        Thread(::watch, "indexino-source-watcher").apply {
            isDaemon = true
            start()
        }
    }
    private val macWatcher: MacFseventsWatcher? =
        if (watcherKind == AutoRefreshWatcherKind.MAC_FSEVENTS) {
            MacFseventsWatcher(workspace, ::onMacPathChanged, ::onWatcherOverflow)
        } else {
            null
        }

    init {
        scheduler.scheduleWithFixedDelay(
            { uncovered.forEach(::enqueue) },
            reconciliationIntervalMillis,
            reconciliationIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    fun register(request: RefreshRequest, sourceFiles: List<String>) {
        if (closed.get()) return
        val directories =
            buildSet {
                    sourceFiles.forEach { source ->
                        val sourcePath = workspace.resolve(source).normalize()
                        sourcePath.parent?.let(::add)
                        sourceRoot(sourcePath)?.let(::add)
                    }
                    topologyInputs(sourceFiles).mapTo(this) { it.parent ?: workspace }
                }
                .filter { directory ->
                    Files.isDirectory(directory) &&
                        directory.startsWith(workspace) &&
                        !excluded(directory)
                }
                .toSet()
        directoriesByRequest[request] = directories
        var coverageComplete = true
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

    private fun registerDirectory(directory: Path): Boolean {
        if (watchService == null) return true
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

    private fun sourceRoot(sourcePath: Path): Path? =
        generateSequence(sourcePath.parent) { current -> current.parent }
            .takeWhile { current -> current.startsWith(workspace) }
            .firstOrNull { current -> current.fileName.toString() in SOURCE_ROOT_NAMES }

    private fun topologyInputs(sourceFiles: List<String>): List<Path> =
        buildSet {
                add(workspace.resolve("settings.gradle.kts"))
                add(workspace.resolve("settings.gradle"))
                add(workspace.resolve("build.gradle.kts"))
                add(workspace.resolve("build.gradle"))
                add(workspace.resolve("MODULE.bazel"))
                add(workspace.resolve("WORKSPACE"))
                add(workspace.resolve("WORKSPACE.bazel"))
                sourceFiles.forEach { source ->
                    generateSequence(workspace.resolve(source).normalize().parent) { current ->
                            current.parent
                        }
                        .takeWhile { current -> current.startsWith(workspace) }
                        .forEach { directory ->
                            add(directory.resolve("build.gradle.kts"))
                            add(directory.resolve("build.gradle"))
                            add(directory.resolve("BUILD"))
                            add(directory.resolve("BUILD.bazel"))
                        }
                }
            }
            .filter(Files::exists)

    private fun excluded(path: Path): Boolean =
        path.any { segment -> segment.toString() == ".git" } ||
            path.startsWith(dev.sebastiano.indexino.api.InProcessCacheLayout.cacheRoot())

    private companion object {
        const val DEBOUNCE_MILLIS = 150L
        const val RECONCILIATION_INTERVAL_MILLIS = 30_000L
        const val MAX_RETRY_ATTEMPTS = 3
        val RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 5_000L, 30_000L)
        val SOURCE_ROOT_NAMES = setOf("kotlin", "java", "resources")
    }
}
