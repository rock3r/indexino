package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.api.BuildSystem
import dev.sebastiano.indexino.api.IndexChanges
import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.RefreshHandle
import dev.sebastiano.indexino.api.RefreshOutcome
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.RefreshResult
import dev.sebastiano.indexino.api.RefreshSummary
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.model.RefreshId
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.SourceOriginRevision
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import dev.sebastiano.indexino.model.WorkspaceRevision
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking

internal class RuntimeRefreshHandle(val id: String, private val connection: RuntimeConnection) {
    fun await(): RuntimeRefreshResult {
        val response = connection.request(RuntimeRefreshProtocol.awaitCommand(id))
        return RuntimeRefreshProtocol.decodeAwaitResponse(response)
    }

    fun stop() {
        connection.request(RuntimeRefreshProtocol.stopCommand(id))
    }
}

internal class RuntimeRefreshResult(val result: RefreshResult) {
    val generation: String
        get() = result.generation.value
}

internal class RuntimeRefreshClient(private val connection: RuntimeConnection) {
    fun refresh(request: RefreshRequest): RuntimeRefreshHandle {
        val response = connection.request(RuntimeRefreshProtocol.refreshCommand(request))
        return RuntimeRefreshHandle(
            RuntimeRefreshProtocol.decodeRefreshResponse(response),
            connection,
        )
    }

    fun active(): List<RefreshSummary> =
        RuntimeRefreshProtocol.decodeActiveResponse(
            connection.request(RuntimeRefreshProtocol.activeCommand())
        )
}

internal class RuntimeRefreshDispatcher(private val owner: Indexino) {
    private val handles = ConcurrentHashMap<String, RefreshHandle>()

    @OptIn(IndexinoInternalApi::class)
    fun dispatch(command: ByteArray): ByteArray {
        val input = DataInputStream(ByteArrayInputStream(command))
        return when (input.readUnsignedByte()) {
            RuntimeRefreshProtocol.REFRESH -> {
                val request = RuntimeRefreshProtocol.decodeRefreshRequest(input)
                val handle = runBlocking { owner.refresh(request) }
                handles[handle.id.value] = handle
                RuntimeRefreshProtocol.refreshResponse(handle.id.value)
            }
            RuntimeRefreshProtocol.AWAIT -> {
                val id = input.readUTF()
                val handle = handles[id] ?: throw RuntimeProtocolException("Unknown refresh $id")
                RuntimeRefreshProtocol.awaitResponse(runBlocking { handle.await() })
            }
            RuntimeRefreshProtocol.ACTIVE ->
                RuntimeRefreshProtocol.activeResponse(runBlocking { owner.activeRefreshes() })
            RuntimeRefreshProtocol.STOP -> {
                val id = input.readUTF()
                val handle = handles[id] ?: throw RuntimeProtocolException("Unknown refresh $id")
                runBlocking { handle.stop() }
                RuntimeRefreshProtocol.emptyResponse()
            }
            else -> throw RuntimeProtocolException("Unknown runtime command")
        }
    }
}

@Suppress("TooManyFunctions")
internal object RuntimeRefreshProtocol {
    const val REFRESH = 1
    const val AWAIT = 2
    const val STOP = 3
    const val ACTIVE = 5

    fun refreshCommand(request: RefreshRequest): ByteArray = bytes {
        writeByte(REFRESH)
        writeRequest(request)
    }

    fun activeCommand(): ByteArray = bytes { writeByte(ACTIVE) }

    fun awaitCommand(id: String): ByteArray = bytes {
        writeByte(AWAIT)
        writeUTF(id)
    }

    fun stopCommand(id: String): ByteArray = bytes {
        writeByte(STOP)
        writeUTF(id)
    }

    fun decodeRefreshRequest(input: DataInputStream): RefreshRequest = input.readRequest()

    private fun DataInputStream.readRequest(): RefreshRequest {
        val buildSystem = readUTF()
        val scopeValue = readUTF()
        val includesDependencies = readBoolean()
        val scope =
            when (buildSystem) {
                BuildSystem.GRADLE.value -> IndexScope.gradle(scopeValue)
                BuildSystem.BAZEL.value -> IndexScope.bazel(scopeValue)
                else -> throw RuntimeProtocolException("Unsupported build system $buildSystem")
            }
        var request =
            RefreshRequest.forScope(
                if (includesDependencies) scope.includingDependencies() else scope
            )
        repeat(readInt()) { request = request.withPlugin(PluginId.of(readUTF())) }
        return request
    }

    fun refreshResponse(id: String): ByteArray = bytes { writeUTF(id) }

    @OptIn(IndexinoInternalApi::class)
    fun activeResponse(summaries: List<RefreshSummary>): ByteArray = bytes {
        writeInt(summaries.size)
        summaries
            .sortedBy { it.id.value }
            .forEach { summary ->
                writeUTF(summary.id.value)
                writeRequest(summary.request)
            }
    }

    @OptIn(IndexinoInternalApi::class)
    fun decodeActiveResponse(response: ByteArray): List<RefreshSummary> =
        DataInputStream(ByteArrayInputStream(response)).use { input ->
            List(input.readInt()) {
                RefreshSummary(RefreshId.of(input.readUTF()), input.readRequest())
            }
        }

    fun decodeRefreshResponse(response: ByteArray): String =
        DataInputStream(ByteArrayInputStream(response)).use(DataInputStream::readUTF)

    fun awaitResponse(result: RefreshResult): ByteArray = bytes {
        writeUTF(result.refreshId.value)
        writeUTF(result.outcome.value)
        writeUTF(result.generation.value)
        writeUTF(result.revision.fingerprint)
        writeInt(result.revision.origins.size)
        result.revision.origins.forEach { origin ->
            writeUTF(origin.originId.value)
            writeNullableUtf(origin.revision)
            writeUTF(origin.stateFingerprint)
            writeNullableUtf(origin.expectedRevision)
        }
        writeUTF(result.scope.buildSystem.value)
        writeUTF(result.scope.value)
        writeBoolean(result.scope.includesDependencies)
        writeInt(result.changes.changedFileCount)
        writeInt(result.changes.unchangedFileCount)
        writeInt(result.changes.removedFileCount)
    }

    @OptIn(IndexinoInternalApi::class)
    fun decodeAwaitResponse(response: ByteArray): RuntimeRefreshResult =
        DataInputStream(ByteArrayInputStream(response)).use { input ->
            val id = RefreshId.of(input.readUTF())
            val outcome =
                when (input.readUTF()) {
                    RefreshOutcome.UPDATED.value -> RefreshOutcome.UPDATED
                    RefreshOutcome.UNCHANGED.value -> RefreshOutcome.UNCHANGED
                    else -> throw RuntimeProtocolException("Unknown refresh outcome")
                }
            val generation = WorkspaceGenerationId.of(input.readUTF())
            val revisionFingerprint = input.readUTF()
            val origins =
                List(input.readInt()) {
                    SourceOriginRevision(
                        originId = SourceOriginId.of(input.readUTF()),
                        revision = input.readNullableUtf(),
                        stateFingerprint = input.readUTF(),
                        expectedRevision = input.readNullableUtf(),
                    )
                }
            val scope = decodeScope(input.readUTF(), input.readUTF(), input.readBoolean())
            val changes = IndexChanges(input.readInt(), input.readInt(), input.readInt())
            RuntimeRefreshResult(
                RefreshResult(
                    refreshId = id,
                    outcome = outcome,
                    generation = generation,
                    revision = WorkspaceRevision(revisionFingerprint, origins),
                    scope = scope,
                    changes = changes,
                )
            )
        }

    fun emptyResponse(): ByteArray = ByteArray(0)

    private fun decodeScope(
        buildSystem: String,
        value: String,
        includesDependencies: Boolean,
    ): IndexScope {
        val scope =
            when (buildSystem) {
                BuildSystem.GRADLE.value -> IndexScope.gradle(value)
                BuildSystem.BAZEL.value -> IndexScope.bazel(value)
                else -> throw RuntimeProtocolException("Unsupported build system $buildSystem")
            }
        return if (includesDependencies) scope.includingDependencies() else scope
    }

    private fun DataOutputStream.writeRequest(request: RefreshRequest) {
        writeUTF(request.scope.buildSystem.value)
        writeUTF(request.scope.value)
        writeBoolean(request.scope.includesDependencies)
        writeInt(request.plugins.size)
        request.plugins.sortedBy(PluginId::value).forEach { plugin -> writeUTF(plugin.value) }
    }

    private fun DataOutputStream.writeNullableUtf(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataInputStream.readNullableUtf(): String? = if (readBoolean()) readUTF() else null

    private fun bytes(write: DataOutputStream.() -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use(write)
        return output.toByteArray()
    }
}
