package dev.sebastiano.indexino.engine

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal class RuntimeLease(
    val ownerPid: Long,
    val ownerStartedAtMillis: Long = 0L,
    val startedAtMillis: Long,
    val protocolMajor: Int,
    val protocolMinor: Int,
    val endpoint: String,
    val workspace: String,
)

internal sealed interface RuntimeLeaseAcquisition {
    class Owned(val lease: RuntimeLease) : RuntimeLeaseAcquisition

    class Existing(val lease: RuntimeLease) : RuntimeLeaseAcquisition
}

internal class RuntimeLeaseStore(
    private val cacheRoot: Path,
    private val pidProvider: () -> Long = { ProcessHandle.current().pid() },
    private val isProcessAlive: (Long) -> Boolean = { pid ->
        ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
    },
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val processStartedAtMillis: (Long) -> Long? = RuntimeLeaseStore::processStartedAtMillis,
) {
    fun acquire(workspaceId: String, endpoint: Path, workspace: Path): RuntimeLeaseAcquisition {
        val leasePath = RuntimePaths.leasePath(cacheRoot, workspaceId)
        Files.createDirectories(leasePath.parent)
        val candidate =
            RuntimeLease(
                ownerPid = pidProvider(),
                ownerStartedAtMillis = processStartedAtMillis(pidProvider()) ?: 0L,
                startedAtMillis = clockMillis(),
                protocolMajor = PROTOCOL_MAJOR,
                protocolMinor = PROTOCOL_MINOR,
                endpoint = endpoint.toString(),
                workspace = workspace.toString(),
            )
        while (true) {
            try {
                Files.writeString(
                    leasePath,
                    JSON.encodeToString(RuntimeLease.serializer(), candidate),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                )
                return RuntimeLeaseAcquisition.Owned(candidate)
            } catch (_: FileAlreadyExistsException) {
                val existing =
                    checkNotNull(read(leasePath)) {
                        "Runtime lease exists but cannot be decoded: $leasePath"
                    }
                if (isLive(existing)) {
                    return RuntimeLeaseAcquisition.Existing(existing)
                }
                Files.deleteIfExists(leasePath)
                Files.deleteIfExists(endpoint)
            }
        }
    }

    fun release(workspaceId: String, lease: RuntimeLease) {
        val leasePath = RuntimePaths.leasePath(cacheRoot, workspaceId)
        val current = read(leasePath)
        if (
            current?.ownerPid == lease.ownerPid &&
                current.startedAtMillis == lease.startedAtMillis &&
                current.endpoint == lease.endpoint
        ) {
            Files.deleteIfExists(leasePath)
        }
    }

    private fun isLive(lease: RuntimeLease): Boolean =
        isProcessAlive(lease.ownerPid) &&
            (lease.ownerStartedAtMillis == 0L ||
                processStartedAtMillis(lease.ownerPid) == lease.ownerStartedAtMillis)

    companion object {
        const val PROTOCOL_MAJOR = 1
        const val PROTOCOL_MINOR = 0

        private val JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
        }

        fun processStartedAtMillis(pid: Long): Long? =
            ProcessHandle.of(pid)
                .flatMap { handle -> handle.info().startInstant() }
                .map { instant -> instant.toEpochMilli() }
                .orElse(null)

        fun isLive(lease: RuntimeLease): Boolean =
            ProcessHandle.of(lease.ownerPid).map(ProcessHandle::isAlive).orElse(false) &&
                (lease.ownerStartedAtMillis == 0L ||
                    processStartedAtMillis(lease.ownerPid) == lease.ownerStartedAtMillis)

        fun read(path: Path): RuntimeLease? =
            if (Files.isRegularFile(path)) {
                JSON.decodeFromString(RuntimeLease.serializer(), Files.readString(path))
            } else {
                null
            }
    }
}
