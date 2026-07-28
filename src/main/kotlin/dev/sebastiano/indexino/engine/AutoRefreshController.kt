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
    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
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
    private val uncovered = ConcurrentHashMap.newKeySet<RefreshRequest>()
    private val watcher =
        Thread(::watch, "indexino-source-watcher").apply {
            isDaemon = true
            start()
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
                        workspace.resolve(source).normalize().parent?.let(::add)
                    }
                    topologyInputs().mapTo(this) { it.parent ?: workspace }
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
        scheduler.execute {
            var succeeded = false
            try {
                kotlinx.coroutines.runBlocking { handle.await() }
                succeeded = true
            } catch (_: Exception) {
                // The last complete generation stays queryable; a later event/manual refresh
                // retries.
            } finally {
                active.remove(request, handle.id.value)
                val newerChange = (dirtyEpoch[request]?.get() ?: 0L) > epochAtStart
                if (succeeded && !newerChange) {
                    dirty.remove(request)
                } else if (dirty.contains(request)) {
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
        watcher.interrupt()
        runCatching { watchService.close() }
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
                    watchService.take()
                } catch (_: InterruptedException) {
                    return
                } catch (_: java.nio.file.ClosedWatchServiceException) {
                    return
                }
            val directory = keys[key]
            if (directory != null) {
                key.pollEvents().forEach { event ->
                    if (
                        event.kind() == ENTRY_CREATE ||
                            event.kind() == ENTRY_DELETE ||
                            event.kind() == ENTRY_MODIFY
                    ) {
                        requestsByDirectory[directory]?.forEach(::enqueue)
                    }
                }
            }
            if (!key.reset()) keys.remove(key)
        }
    }

    private fun enqueue(request: RefreshRequest) {
        if (closed.get()) return
        dirty.add(request)
        dirtyEpoch.computeIfAbsent(request) { AtomicLong() }.incrementAndGet()
        if (mode == AutoRefreshMode.DISABLED || active.containsKey(request) || !queued.add(request))
            return
        scheduler.schedule(
            {
                queued.remove(request)
                if (!closed.get() && dirty.remove(request)) refresh(request)
            },
            DEBOUNCE_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun registerDirectory(directory: Path): Boolean {
        if (keys.values.any { registered -> registered == directory }) return true
        return try {
            val key = directory.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
            keys[key] = directory
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun topologyInputs(): List<Path> =
        listOf(
                workspace.resolve("settings.gradle.kts"),
                workspace.resolve("settings.gradle"),
                workspace.resolve("build.gradle.kts"),
                workspace.resolve("build.gradle"),
                workspace.resolve("MODULE.bazel"),
                workspace.resolve("WORKSPACE"),
                workspace.resolve("WORKSPACE.bazel"),
            )
            .filter(Files::exists)

    private fun excluded(path: Path): Boolean =
        path.any { segment -> segment.toString() == ".git" } ||
            path.startsWith(dev.sebastiano.indexino.api.InProcessCacheLayout.cacheRoot())

    private companion object {
        const val DEBOUNCE_MILLIS = 150L
        const val RECONCILIATION_INTERVAL_MILLIS = 30_000L
    }
}
