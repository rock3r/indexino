package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.IndexinoConfiguration
import dev.sebastiano.indexino.api.RuntimeAttachMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean

/** The sole owner of a workspace's refresh registry and mutable writer state. */
internal class WorkspaceRuntime
private constructor(
    private val workspace: Path,
    private val cacheRoot: Path,
    private val workspaceId: String,
    private val owner: Indexino,
    private val workspaceFileKey: String?,
) : AutoCloseable {
    private val refreshDispatcher = RuntimeRefreshDispatcher(owner)
    private val snapshotDispatcher = RuntimeSnapshotDispatcher(owner)
    private val workspaceLost = AtomicBoolean()
    private lateinit var livenessThread: Thread
    private lateinit var daemon: RuntimeDaemon

    val endpoint: Path
        get() = daemon.endpoint

    override fun close() {
        if (::livenessThread.isInitialized) livenessThread.interrupt()
        try {
            if (::daemon.isInitialized) daemon.close()
        } finally {
            snapshotDispatcher.close()
            owner.close()
        }
    }

    internal fun snapshotLeaseCountForTests(): Int = snapshotDispatcher.leaseCountForTests()

    private fun dispatch(session: RuntimeSession, command: ByteArray): ByteArray {
        if (!isBoundWorkspace()) {
            handleWorkspaceLoss()
            throw RuntimeProtocolException("WORKSPACE_LOST: bound workspace disappeared")
        }
        if (command.firstOrNull() == RuntimeControlProtocol.SHUTDOWN.toByte()) {
            close()
            return ByteArray(0)
        }
        return when (command.firstOrNull()?.toInt()) {
            RuntimeRefreshProtocol.REFRESH,
            RuntimeRefreshProtocol.AWAIT,
            RuntimeRefreshProtocol.STOP,
            RuntimeRefreshProtocol.ACTIVE,
            RuntimeRefreshProtocol.PROGRESS -> refreshDispatcher.dispatch(command)
            RuntimeSnapshotProtocol.ACQUIRE,
            RuntimeSnapshotProtocol.RELEASE,
            RuntimeSnapshotProtocol.FIND_SYMBOLS,
            RuntimeSnapshotProtocol.FIND_REFERENCES,
            RuntimeSnapshotProtocol.FIND_CALLS,
            RuntimeSnapshotProtocol.RUN_CHECK -> snapshotDispatcher.dispatch(session, command)
            else -> throw RuntimeProtocolException("Unknown runtime command")
        }
    }

    private fun startLivenessMonitoring() {
        livenessThread =
            Thread(
                    {
                        try {
                            while (!Thread.currentThread().isInterrupted) {
                                if (!isBoundWorkspace()) {
                                    handleWorkspaceLoss()
                                    return@Thread
                                }
                                Thread.sleep(LIVENESS_PROBE_INTERVAL_MILLIS)
                            }
                        } catch (_: InterruptedException) {
                            // Runtime shutdown interrupts the liveness probe.
                        }
                    },
                    "indexino-workspace-liveness",
                )
                .apply {
                    isDaemon = true
                    start()
                }
    }

    private fun isBoundWorkspace(): Boolean =
        try {
            val attributes = Files.readAttributes(workspace, BasicFileAttributes::class.java)
            attributes.isDirectory &&
                (workspaceFileKey == null || attributes.fileKey()?.toString() == workspaceFileKey)
        } catch (_: java.io.IOException) {
            false
        }

    private fun handleWorkspaceLoss() {
        if (workspaceLost.compareAndSet(false, true)) {
            refreshDispatcher.stopAll()
            RuntimeTombstoneStore.write(
                RuntimePaths.tombstonePath(cacheRoot, workspaceId),
                workspace,
                workspaceFileKey,
            )
            close()
        }
    }

    companion object {
        private const val LIVENESS_PROBE_INTERVAL_MILLIS = 100L

        fun start(workspace: Path, cacheRoot: Path): WorkspaceRuntime {
            val canonicalWorkspace = workspace.toRealPath()
            val owner =
                Indexino.connectBlocking(
                    IndexinoConfiguration.forWorkspace(canonicalWorkspace)
                        .withRuntimeAttach(RuntimeAttachMode.IN_PROCESS)
                )
            val workspaceId = InProcessCacheLayout.workspaceId(canonicalWorkspace)
            val workspaceFileKey =
                Files.readAttributes(canonicalWorkspace, BasicFileAttributes::class.java)
                    .fileKey()
                    ?.toString()
            val runtime =
                WorkspaceRuntime(
                    canonicalWorkspace,
                    cacheRoot,
                    workspaceId,
                    owner,
                    workspaceFileKey,
                )
            val start =
                RuntimeDaemon.start(
                    cacheRoot = cacheRoot,
                    workspaceId = workspaceId,
                    workspace = canonicalWorkspace,
                    sessionCommandHandler = runtime::dispatch,
                    sessionDisconnected = runtime.snapshotDispatcher::releaseSession,
                )
            if (start is RuntimeDaemonStart.Owned) {
                RuntimeTombstoneStore.acknowledge(
                    RuntimePaths.tombstonePath(cacheRoot, workspaceId)
                )
                runtime.daemon = start.daemon
                runtime.startLivenessMonitoring()
                return runtime
            }
            owner.close()
            error("A workspace runtime already owns ${workspace.toAbsolutePath()}")
        }
    }
}
