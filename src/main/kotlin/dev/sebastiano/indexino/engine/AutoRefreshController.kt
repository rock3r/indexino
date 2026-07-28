package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.RefreshHandle
import dev.sebastiano.indexino.api.RefreshRequest
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

/** Runtime-owned, scope-derived watcher that coalesces filesystem hints into refresh requests. */
internal class AutoRefreshController(
    private val workspace: Path,
    private val mode: AutoRefreshMode,
    private val refresh: (RefreshRequest) -> Unit,
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
    private val active = ConcurrentHashMap<RefreshRequest, String>()
    private val watcher =
        Thread(::watch, "indexino-source-watcher").apply {
            isDaemon = true
            start()
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
        directories.forEach { directory ->
            requestsByDirectory
                .computeIfAbsent(directory) { ConcurrentHashMap.newKeySet() }
                .add(request)
            registerDirectory(directory)
        }
    }

    fun onRefreshStarted(request: RefreshRequest, handle: RefreshHandle) {
        active[request] = handle.id.value
        scheduler.execute {
            try {
                kotlinx.coroutines.runBlocking { handle.await() }
            } catch (_: Exception) {
                // The last complete generation stays queryable; a later event/manual refresh
                // retries.
            } finally {
                active.remove(request, handle.id.value)
                if (dirty.remove(request)) enqueue(request)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        watcher.interrupt()
        runCatching { watchService.close() }
        scheduler.shutdownNow()
        keys.clear()
        requestsByDirectory.clear()
        directoriesByRequest.clear()
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
        if (mode == AutoRefreshMode.DISABLED || closed.get()) return
        dirty.add(request)
        if (active.containsKey(request) || !queued.add(request)) return
        scheduler.schedule(
            {
                queued.remove(request)
                if (!closed.get() && dirty.remove(request)) refresh(request)
            },
            DEBOUNCE_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun registerDirectory(directory: Path) {
        if (keys.values.any { registered -> registered == directory }) return
        try {
            val key = directory.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
            keys[key] = directory
        } catch (_: IOException) {
            // An uncovered directory remains reconciled by explicit/manual refresh; S7 records
            // degraded coverage in the registry in the next tranche.
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
    }
}
