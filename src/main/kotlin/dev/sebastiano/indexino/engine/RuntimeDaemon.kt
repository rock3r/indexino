package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.api.AutoRefreshMode
import java.nio.file.Path

internal sealed interface RuntimeDaemonStart {
    class Owned(val daemon: RuntimeDaemon) : RuntimeDaemonStart

    class Existing(val lease: RuntimeLease) : RuntimeDaemonStart
}

/** Owns the AF_UNIX endpoint and lease for one workspace until explicit shutdown. */
internal class RuntimeDaemon
private constructor(
    private val cacheRoot: Path,
    private val workspaceId: String,
    private val leaseStore: RuntimeLeaseStore,
    private val lease: RuntimeLease,
    private val handshakeServer: RuntimeHandshakeServer,
    val endpoint: Path,
) : AutoCloseable {
    override fun close() {
        try {
            handshakeServer.close()
        } finally {
            leaseStore.release(workspaceId, lease)
        }
    }

    companion object {
        fun start(
            cacheRoot: Path,
            workspaceId: String,
            workspace: Path,
            autoRefreshMode: AutoRefreshMode = AutoRefreshMode.ENABLED,
            sessionCommandHandler: ((RuntimeSession, ByteArray) -> ByteArray)? = null,
            sessionDisconnected: (RuntimeSession) -> Unit = {},
            commandHandler: ((ByteArray) -> ByteArray)? = null,
        ): RuntimeDaemonStart {
            val endpoint = RuntimePaths.socketPath(cacheRoot, workspaceId)
            val leaseStore = RuntimeLeaseStore(cacheRoot)
            return when (
                val acquisition =
                    leaseStore.acquire(workspaceId, endpoint, workspace, autoRefreshMode)
            ) {
                is RuntimeLeaseAcquisition.Existing ->
                    RuntimeDaemonStart.Existing(acquisition.lease)
                is RuntimeLeaseAcquisition.Owned -> {
                    val server =
                        RuntimeHandshakeServer(
                            endpoint = endpoint,
                            commandHandler = commandHandler,
                            sessionCommandHandler = sessionCommandHandler,
                            sessionDisconnected = sessionDisconnected,
                        )
                    try {
                        server.start()
                        RuntimeDaemonStart.Owned(
                            RuntimeDaemon(
                                cacheRoot = cacheRoot,
                                workspaceId = workspaceId,
                                leaseStore = leaseStore,
                                lease = acquisition.lease,
                                handshakeServer = server,
                                endpoint = endpoint,
                            )
                        )
                    } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
                        try {
                            server.close()
                        } finally {
                            leaseStore.release(workspaceId, acquisition.lease)
                        }
                        throw thrown
                    }
                }
            }
        }
    }
}
