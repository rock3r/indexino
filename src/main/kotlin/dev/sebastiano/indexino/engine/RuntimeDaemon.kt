package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.api.AutoRefreshMode
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface RuntimeDaemonStart {
    class Owned(val daemon: RuntimeDaemon) : RuntimeDaemonStart

    class Existing(val lease: RuntimeLease) : RuntimeDaemonStart
}

/** Owns the AF_UNIX endpoint and lease for one workspace until explicit shutdown. */
internal class RuntimeDaemon
internal constructor(
    private val cacheRoot: Path,
    private val workspaceId: String,
    private val leaseStore: RuntimeLeaseStore,
    private val lease: RuntimeLease,
    private val handshakeServer: AutoCloseable,
    val endpoint: Path,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private var serverClosed = false

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var completed = false
        try {
            try {
                if (!serverClosed) {
                    handshakeServer.close()
                    serverClosed = true
                }
            } finally {
                leaseStore.release(workspaceId, lease)
            }
            completed = true
        } finally {
            if (!completed) closed.set(false)
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
