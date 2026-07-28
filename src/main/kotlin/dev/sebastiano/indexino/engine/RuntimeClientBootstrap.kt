package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.InProcessCacheLayout
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Attaches to, or starts, the local runtime owner for a canonical workspace. */
internal object RuntimeClientBootstrap {
    fun connect(workspace: Path, autoRefreshMode: AutoRefreshMode): RuntimeConnection {
        val cacheRoot = InProcessCacheLayout.cacheRoot()
        val workspaceId = InProcessCacheLayout.workspaceId(workspace)
        val endpoint = RuntimePaths.socketPath(cacheRoot, workspaceId)
        val tombstonePath = RuntimePaths.tombstonePath(cacheRoot, workspaceId)
        RuntimeTombstoneStore.read(tombstonePath)?.let { tombstone ->
            val currentFileKey =
                runCatching {
                        Files.readAttributes(
                                workspace,
                                java.nio.file.attribute.BasicFileAttributes::class.java,
                            )
                            .fileKey()
                            ?.toString()
                    }
                    .getOrNull()
            if (
                tombstone.workspaceFileKey != null && tombstone.workspaceFileKey != currentFileKey
            ) {
                RuntimeTombstoneStore.acknowledge(tombstonePath)
            } else {
                throw RuntimeProtocolException(
                    "WORKSPACE_LOST: daemon stop must acknowledge this workspace"
                )
            }
        }
        val leasePath = RuntimePaths.leasePath(cacheRoot, workspaceId)
        RuntimeLeaseStore.read(leasePath)
            ?.takeIf { it.protocolMajor != RuntimeLeaseStore.PROTOCOL_MAJOR }
            ?.let {
                Files.deleteIfExists(endpoint)
                Files.deleteIfExists(leasePath)
            }
        assertCompatibleMode(leasePath, autoRefreshMode)
        if (!Files.exists(endpoint)) startOwner(workspace, cacheRoot, endpoint, autoRefreshMode)
        var lastFailure: IOException? = null
        repeat(CONNECT_WAIT_ATTEMPTS) {
            try {
                return RuntimeConnection.connect(endpoint)
            } catch (failure: IOException) {
                lastFailure = failure
                recoverDeadEndpoint(cacheRoot, workspace, endpoint)
                assertCompatibleMode(leasePath, autoRefreshMode)
                if (!Files.exists(endpoint))
                    startOwner(workspace, cacheRoot, endpoint, autoRefreshMode)
                Thread.sleep(START_WAIT_MILLIS)
            }
        }
        throw checkNotNull(lastFailure)
    }

    private fun recoverDeadEndpoint(cacheRoot: Path, workspace: Path, endpoint: Path) {
        val workspaceId = InProcessCacheLayout.workspaceId(workspace)
        val leasePath = RuntimePaths.leasePath(cacheRoot, workspaceId)
        val lease = RuntimeLeaseStore.read(leasePath)
        if (lease == null || !RuntimeLeaseStore.isLive(lease)) {
            Files.deleteIfExists(endpoint)
            Files.deleteIfExists(leasePath)
        }
    }

    private fun startOwner(
        workspace: Path,
        cacheRoot: Path,
        endpoint: Path,
        autoRefreshMode: AutoRefreshMode,
    ) {
        val lease =
            RuntimeLeaseStore.read(
                RuntimePaths.leasePath(cacheRoot, InProcessCacheLayout.workspaceId(workspace))
            )
        if (lease == null || !RuntimeLeaseStore.isLive(lease)) {
            val java = Path.of(System.getProperty("java.home"), "bin", "java")
            if (Files.isExecutable(java)) {
                val command = buildList {
                    add(java.toString())
                    add("--enable-native-access=ALL-UNNAMED")
                    add("-Dindexino.cache.dir=$cacheRoot")
                    add("-cp")
                    add(System.getProperty("java.class.path"))
                    add("dev.sebastiano.indexino.engine.RuntimeOwnerMainKt")
                    add(workspace.toString())
                    add(autoRefreshMode.name)
                }
                ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } else {
                suppressEmbeddedRegistryWarnings()
                embeddedRuntimes.computeIfAbsent(InProcessCacheLayout.workspaceId(workspace)) {
                    WorkspaceRuntime.start(workspace, cacheRoot, autoRefreshMode)
                }
            }
        }
        repeat(START_WAIT_ATTEMPTS) {
            if (Files.exists(endpoint)) return
            Thread.sleep(START_WAIT_MILLIS)
        }
        throw RuntimeProtocolException("Local runtime failed to start")
    }

    private fun assertCompatibleMode(leasePath: Path, requested: AutoRefreshMode) {
        val lease = RuntimeLeaseStore.read(leasePath) ?: return
        if (RuntimeLeaseStore.isLive(lease) && lease.autoRefreshMode != requested) {
            throw RuntimeProtocolException(
                "AUTO_REFRESH_MODE_MISMATCH: runtime uses ${lease.autoRefreshMode}"
            )
        }
    }

    private val embeddedRuntimes = ConcurrentHashMap<String, WorkspaceRuntime>()
    private val embeddedStderrFiltered = AtomicBoolean()

    private fun suppressEmbeddedRegistryWarnings() {
        if (embeddedStderrFiltered.compareAndSet(false, true)) {
            System.setErr(RegistryWarningFilteringPrintStream(System.err))
        }
    }

    private const val CONNECT_WAIT_ATTEMPTS = 100
    private const val START_WAIT_ATTEMPTS = 600
    private const val START_WAIT_MILLIS = 50L
}

private class RegistryWarningFilteringPrintStream(private val delegate: PrintStream) :
    PrintStream(delegate, true) {
    override fun println(value: String?) {
        if (value?.startsWith(REGISTRY_WARNING_PREFIX) != true) delegate.println(value)
    }

    override fun println(value: Any?) = println(value?.toString())

    private companion object {
        const val REGISTRY_WARNING_PREFIX = "WARN: Attempt to load key '"
    }
}
