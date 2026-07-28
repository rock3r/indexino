package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.api.FreshnessPolicy
import dev.sebastiano.indexino.api.IndexSnapshot
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.SnapshotFreshness
import dev.sebastiano.indexino.model.ArgumentKind
import dev.sebastiano.indexino.model.CallArgument
import dev.sebastiano.indexino.model.CallQuery
import dev.sebastiano.indexino.model.CallSite
import dev.sebastiano.indexino.model.CallSiteId
import dev.sebastiano.indexino.model.CheckRequest
import dev.sebastiano.indexino.model.Finding
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.NameMatchMode
import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.QueryPage
import dev.sebastiano.indexino.model.Reference
import dev.sebastiano.indexino.model.ReferenceQuery
import dev.sebastiano.indexino.model.ResolutionConfidence
import dev.sebastiano.indexino.model.SourceFile
import dev.sebastiano.indexino.model.SourceLocation
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.SourceOriginRevision
import dev.sebastiano.indexino.model.SourceRange
import dev.sebastiano.indexino.model.Symbol
import dev.sebastiano.indexino.model.SymbolId
import dev.sebastiano.indexino.model.SymbolQuery
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import dev.sebastiano.indexino.model.WorkspaceRevision
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking

internal class RuntimeSnapshotLease(
    val id: String,
    val generation: WorkspaceGenerationId,
    val revision: WorkspaceRevision,
    val freshness: SnapshotFreshness,
)

internal class RuntimeSnapshotClient(private val connection: RuntimeConnection) {
    fun acquire(freshness: FreshnessPolicy): RuntimeSnapshotLease =
        RuntimeSnapshotProtocol.decodeAcquireResponse(
            connection.request(RuntimeSnapshotProtocol.acquireCommand(freshness))
        )

    fun release(id: String) {
        connection.request(RuntimeSnapshotProtocol.releaseCommand(id))
    }

    fun findSymbols(leaseId: String, query: SymbolQuery, options: QueryOptions): QueryPage<Symbol> =
        RuntimeSnapshotProtocol.decodeFindSymbolsResponse(
            connection.request(RuntimeSnapshotProtocol.findSymbolsCommand(leaseId, query, options))
        )

    fun findReferences(
        leaseId: String,
        query: ReferenceQuery,
        options: QueryOptions,
    ): QueryPage<Reference> =
        RuntimeSnapshotProtocol.decodeFindReferencesResponse(
            connection.request(
                RuntimeSnapshotProtocol.findReferencesCommand(leaseId, query, options)
            )
        )

    fun findCalls(leaseId: String, query: CallQuery, options: QueryOptions): QueryPage<CallSite> =
        RuntimeSnapshotProtocol.decodeFindCallsResponse(
            connection.request(RuntimeSnapshotProtocol.findCallsCommand(leaseId, query, options))
        )

    fun runCheck(
        leaseId: String,
        request: CheckRequest,
        options: QueryOptions,
    ): QueryPage<Finding> =
        RuntimeSnapshotProtocol.decodeRunCheckResponse(
            connection.request(RuntimeSnapshotProtocol.runCheckCommand(leaseId, request, options))
        )
}

internal class RuntimeSnapshotDispatcher(
    private val owner: Indexino,
    private val freshnessOverride: (FreshnessPolicy) -> SnapshotFreshness? = { null },
    private val beforeAcquire: (FreshnessPolicy) -> Unit = {},
) : AutoCloseable {
    private val snapshots = ConcurrentHashMap<String, SessionSnapshot>()

    internal fun leaseCountForTests(): Int = snapshots.size

    internal fun releaseSession(session: RuntimeSession) {
        snapshots.entries.removeIf { (_, snapshot) ->
            if (snapshot.sessionId != session.id) return@removeIf false
            snapshot.snapshot.close()
            true
        }
    }

    fun dispatch(session: RuntimeSession, command: ByteArray): ByteArray {
        val input = DataInputStream(ByteArrayInputStream(command))
        return when (input.readUnsignedByte()) {
            RuntimeSnapshotProtocol.ACQUIRE -> {
                val freshness = RuntimeSnapshotProtocol.decodeFreshness(input)
                beforeAcquire(freshness)
                val snapshot = runBlocking { owner.snapshot(freshness) }
                val id = UUID.randomUUID().toString()
                snapshots[id] = SessionSnapshot(session.id, snapshot)
                RuntimeSnapshotProtocol.acquireResponse(
                    id = id,
                    generation = snapshot.generation,
                    revision = snapshot.revision,
                    freshness = freshnessOverride(freshness) ?: snapshot.freshnessAtAcquisition,
                )
            }
            RuntimeSnapshotProtocol.RELEASE -> {
                snapshots.remove(input.readUTF())?.snapshot?.close()
                RuntimeSnapshotProtocol.emptyResponse()
            }
            RuntimeSnapshotProtocol.FIND_SYMBOLS -> {
                val snapshot = snapshot(input.readUTF())
                val query = RuntimeSnapshotProtocol.decodeSymbolQuery(input)
                val options = RuntimeSnapshotProtocol.decodeQueryOptions(input)
                RuntimeSnapshotProtocol.findSymbolsResponse(
                    runBlocking { snapshot.findSymbols(query, options) }
                )
            }
            RuntimeSnapshotProtocol.FIND_REFERENCES -> {
                val snapshot = snapshot(input.readUTF())
                val query = RuntimeSnapshotProtocol.decodeReferenceQuery(input)
                val options = RuntimeSnapshotProtocol.decodeQueryOptions(input)
                RuntimeSnapshotProtocol.findReferencesResponse(
                    runBlocking { snapshot.findReferences(query, options) }
                )
            }
            RuntimeSnapshotProtocol.FIND_CALLS -> {
                val snapshot = snapshot(input.readUTF())
                val query = RuntimeSnapshotProtocol.decodeCallQuery(input)
                val options = RuntimeSnapshotProtocol.decodeQueryOptions(input)
                RuntimeSnapshotProtocol.findCallsResponse(
                    runBlocking { snapshot.findCalls(query, options) }
                )
            }
            RuntimeSnapshotProtocol.RUN_CHECK -> {
                val snapshot = snapshot(input.readUTF())
                val request = RuntimeSnapshotProtocol.decodeCheckRequest(input)
                val options = RuntimeSnapshotProtocol.decodeQueryOptions(input)
                RuntimeSnapshotProtocol.runCheckResponse(
                    runBlocking { snapshot.runCheck(request, options) }
                )
            }
            else -> throw RuntimeProtocolException("Unknown runtime snapshot command")
        }
    }

    private fun snapshot(id: String): IndexSnapshot =
        snapshots[id]?.snapshot ?: throw RuntimeProtocolException("Unknown snapshot lease")

    override fun close() {
        snapshots.values.forEach { it.snapshot.close() }
        snapshots.clear()
    }

    private class SessionSnapshot(val sessionId: String, val snapshot: IndexSnapshot)
}

@Suppress("TooManyFunctions")
internal object RuntimeSnapshotProtocol {
    const val ACQUIRE = 10
    const val RELEASE = 11
    const val FIND_SYMBOLS = 12
    const val FIND_REFERENCES = 13
    const val FIND_CALLS = 14
    const val RUN_CHECK = 15

    fun acquireCommand(freshness: FreshnessPolicy): ByteArray = bytes {
        writeByte(ACQUIRE)
        writeUTF(freshness.name)
    }

    fun releaseCommand(id: String): ByteArray = bytes {
        writeByte(RELEASE)
        writeUTF(id)
    }

    fun findSymbolsCommand(leaseId: String, query: SymbolQuery, options: QueryOptions): ByteArray =
        bytes {
            writeByte(FIND_SYMBOLS)
            writeUTF(leaseId)
            writeSymbolQuery(query)
            writeQueryOptions(options)
        }

    fun findReferencesCommand(
        leaseId: String,
        query: ReferenceQuery,
        options: QueryOptions,
    ): ByteArray = bytes {
        writeByte(FIND_REFERENCES)
        writeUTF(leaseId)
        writeUTF(query.symbolId.value)
        writeQueryOptions(options)
    }

    fun findCallsCommand(leaseId: String, query: CallQuery, options: QueryOptions): ByteArray =
        bytes {
            writeByte(FIND_CALLS)
            writeUTF(leaseId)
            writeNullableUtf(query.calleeName)
            writeNullableUtf(query.enclosingSymbolId?.value)
            writeNullableUtf(query.callSiteId?.value)
            writeBoolean(query.file != null)
            query.file?.let { file -> writeSourceFile(file) }
            writeQueryOptions(options)
        }

    fun runCheckCommand(leaseId: String, request: CheckRequest, options: QueryOptions): ByteArray =
        bytes {
            writeByte(RUN_CHECK)
            writeUTF(leaseId)
            writeUTF(request.pluginId.value)
            writeUTF(request.checkId)
            writeQueryOptions(options)
        }

    fun decodeCheckRequest(input: DataInputStream): CheckRequest =
        CheckRequest.of(PluginId.of(input.readUTF()), input.readUTF())

    fun decodeFreshness(input: DataInputStream): FreshnessPolicy =
        try {
            FreshnessPolicy.valueOf(input.readUTF())
        } catch (_: IllegalArgumentException) {
            throw RuntimeProtocolException("Unsupported snapshot freshness")
        }

    fun acquireResponse(
        id: String,
        generation: WorkspaceGenerationId,
        revision: WorkspaceRevision,
        freshness: SnapshotFreshness,
    ): ByteArray = bytes {
        writeUTF(id)
        writeUTF(generation.value)
        writeUTF(revision.fingerprint)
        writeInt(revision.origins.size)
        revision.origins.forEach { origin ->
            writeUTF(origin.originId.value)
            writeNullableUtf(origin.revision)
            writeUTF(origin.stateFingerprint)
            writeNullableUtf(origin.expectedRevision)
        }
        writeUTF(freshness.value)
    }

    @OptIn(IndexinoInternalApi::class)
    fun decodeAcquireResponse(response: ByteArray): RuntimeSnapshotLease =
        DataInputStream(ByteArrayInputStream(response)).use { input ->
            val id = input.readUTF()
            val generation = WorkspaceGenerationId.of(input.readUTF())
            val fingerprint = input.readUTF()
            val origins =
                List(input.readInt()) {
                    SourceOriginRevision(
                        originId = SourceOriginId.of(input.readUTF()),
                        revision = input.readNullableUtf(),
                        stateFingerprint = input.readUTF(),
                        expectedRevision = input.readNullableUtf(),
                    )
                }
            RuntimeSnapshotLease(
                id = id,
                generation = generation,
                revision = WorkspaceRevision(fingerprint, origins),
                freshness = decodeSnapshotFreshness(input.readUTF()),
            )
        }

    @OptIn(IndexinoInternalApi::class)
    fun findSymbolsResponse(page: QueryPage<Symbol>): ByteArray = bytes {
        writeQueryPage(page) { symbol -> writeSymbol(symbol) }
    }

    @OptIn(IndexinoInternalApi::class)
    fun decodeFindSymbolsResponse(response: ByteArray): QueryPage<Symbol> =
        DataInputStream(ByteArrayInputStream(response)).use { input ->
            readQueryPage(input) { readSymbol(input) }
        }

    @OptIn(IndexinoInternalApi::class)
    fun findReferencesResponse(page: QueryPage<Reference>): ByteArray = bytes {
        writeQueryPage(page) { reference -> writeReference(reference) }
    }

    @OptIn(IndexinoInternalApi::class)
    fun decodeFindReferencesResponse(response: ByteArray): QueryPage<Reference> =
        DataInputStream(ByteArrayInputStream(response)).use { input ->
            readQueryPage(input) { readReference(input) }
        }

    @OptIn(IndexinoInternalApi::class)
    fun findCallsResponse(page: QueryPage<CallSite>): ByteArray = bytes {
        writeQueryPage(page) { call -> writeCall(call) }
    }

    @OptIn(IndexinoInternalApi::class)
    fun decodeFindCallsResponse(response: ByteArray): QueryPage<CallSite> =
        DataInputStream(ByteArrayInputStream(response)).use { input ->
            readQueryPage(input) { readCall(input) }
        }

    @OptIn(IndexinoInternalApi::class)
    fun runCheckResponse(page: QueryPage<Finding>): ByteArray = bytes {
        writeQueryPage(page) { finding -> writeFinding(finding) }
    }

    @OptIn(IndexinoInternalApi::class)
    fun decodeRunCheckResponse(response: ByteArray): QueryPage<Finding> =
        DataInputStream(ByteArrayInputStream(response)).use { input ->
            readQueryPage(input) { readFinding(input) }
        }

    fun decodeReferenceQuery(input: DataInputStream): ReferenceQuery =
        ReferenceQuery.to(SymbolId.of(input.readUTF()))

    fun decodeCallQuery(input: DataInputStream): CallQuery {
        val calleeName = input.readNullableUtf()
        val enclosingSymbolId = input.readNullableUtf()?.let(SymbolId::of)
        val callSiteId = input.readNullableUtf()?.let(CallSiteId::of)
        val file = if (input.readBoolean()) input.readSourceFile() else null
        return when {
            calleeName != null -> CallQuery.to(calleeName)
            enclosingSymbolId != null -> CallQuery.enclosedBy(enclosingSymbolId)
            callSiteId != null -> CallQuery.byId(callSiteId)
            file != null -> CallQuery.inFile(file)
            else -> throw RuntimeProtocolException("Call query has no selector")
        }
    }

    fun decodeSymbolQuery(input: DataInputStream): SymbolQuery {
        val name = input.readNullableUtf()
        val file = if (input.readBoolean()) input.readSourceFile() else null
        var query = name?.let(SymbolQuery::named) ?: SymbolQuery.inFile(checkNotNull(file))
        input.readNullableUtf()?.let { kind -> query = query.withKind(kind) }
        input.readNullableUtf()?.let { language -> query = query.withLanguage(language) }
        return query.withMatch(NameMatchMode.valueOf(input.readUTF()))
    }

    fun decodeQueryOptions(input: DataInputStream): QueryOptions {
        val limit = input.readInt()
        val offset = input.readInt()
        val cursor = input.readNullableUtf()
        return if (cursor == null) QueryOptions.page(limit, offset)
        else QueryOptions.after(limit, cursor)
    }

    private fun DataOutputStream.writeSymbolQuery(query: SymbolQuery) {
        writeNullableUtf(query.name)
        writeBoolean(query.file != null)
        query.file?.let { file -> writeSourceFile(file) }
        writeNullableUtf(query.kind)
        writeNullableUtf(query.language)
        writeUTF(query.match.name)
    }

    private fun DataOutputStream.writeQueryOptions(options: QueryOptions) {
        writeInt(options.limit)
        writeInt(options.offset)
        writeNullableUtf(options.afterCursor)
    }

    @OptIn(IndexinoInternalApi::class)
    private fun <T> DataOutputStream.writeQueryPage(
        page: QueryPage<T>,
        writeItem: DataOutputStream.(T) -> Unit,
    ) {
        writeInt(page.offset)
        writeInt(page.limit)
        writeBoolean(page.hasMore)
        writeNullableUtf(page.nextCursor)
        writeNullableInt(page.totalCount)
        writeInt(page.items.size)
        page.items.forEach { item -> writeItem(item) }
    }

    @OptIn(IndexinoInternalApi::class)
    private fun <T> readQueryPage(input: DataInputStream, readItem: () -> T): QueryPage<T> {
        val offset = input.readInt()
        val limit = input.readInt()
        val hasMore = input.readBoolean()
        val cursor = input.readNullableUtf()
        val total = input.readNullableInt()
        val items = List(input.readInt()) { readItem() }
        return QueryPage(items, offset, limit, hasMore, cursor, total)
    }

    @OptIn(IndexinoInternalApi::class)
    private fun DataOutputStream.writeSymbol(symbol: Symbol) {
        writeUTF(symbol.id.value)
        writeUTF(symbol.name)
        writeUTF(symbol.kind)
        writeUTF(symbol.language)
        writeSourceLocation(symbol.location)
        writeBoolean(symbol.range != null)
        symbol.range?.let { range ->
            writeSourceLocation(range.start)
            writeSourceLocation(range.end)
        }
        writeNullableUtf(symbol.ownerId?.value)
        writeNullableUtf(symbol.signature)
        writeNullableInt(symbol.arity)
        writeInt(symbol.aliases.size)
        symbol.aliases.forEach(::writeUTF)
    }

    @OptIn(IndexinoInternalApi::class)
    private fun readSymbol(input: DataInputStream): Symbol {
        val id = SymbolId.of(input.readUTF())
        val name = input.readUTF()
        val kind = input.readUTF()
        val language = input.readUTF()
        val location = input.readSourceLocation()
        val range =
            if (input.readBoolean())
                SourceRange.of(input.readSourceLocation(), input.readSourceLocation())
            else null
        val ownerId = input.readNullableUtf()?.let(SymbolId::of)
        val signature = input.readNullableUtf()
        val arity = input.readNullableInt()
        val aliases = List(input.readInt()) { input.readUTF() }
        return Symbol(id, name, kind, language, location, range, ownerId, signature, arity, aliases)
    }

    @OptIn(IndexinoInternalApi::class)
    private fun DataOutputStream.writeReference(reference: Reference) {
        writeUTF(reference.symbolId.value)
        writeUTF(reference.referencedName)
        writeUTF(reference.language)
        writeSourceLocation(reference.location)
        writeNullableUtf(reference.qualifier)
        writeInt(reference.candidateSymbolIds.size)
        reference.candidateSymbolIds.forEach { candidate -> writeUTF(candidate.value) }
        writeNullableInt(reference.arity)
    }

    @OptIn(IndexinoInternalApi::class)
    private fun readReference(input: DataInputStream): Reference =
        Reference(
            symbolId = SymbolId.of(input.readUTF()),
            referencedName = input.readUTF(),
            language = input.readUTF(),
            location = input.readSourceLocation(),
            qualifier = input.readNullableUtf(),
            candidateSymbolIds = List(input.readInt()) { SymbolId.of(input.readUTF()) },
            arity = input.readNullableInt(),
        )

    @OptIn(IndexinoInternalApi::class)
    private fun DataOutputStream.writeCall(call: CallSite) {
        writeUTF(call.id.value)
        writeUTF(call.calleeName)
        writeInt(call.candidateSymbolIds.size)
        call.candidateSymbolIds.forEach { candidate -> writeUTF(candidate.value) }
        writeNullableUtf(call.receiver)
        writeNullableUtf(call.enclosingSymbolId?.value)
        writeNullableUtf(call.parentCallId?.value)
        writeSourceLocation(call.range.start)
        writeSourceLocation(call.range.end)
        writeInt(call.arguments.size)
        call.arguments.forEach { argument -> writeCallArgument(argument) }
        writeUTF(call.confidence.value)
    }

    @OptIn(IndexinoInternalApi::class)
    private fun readCall(input: DataInputStream): CallSite {
        val id = CallSiteId.of(input.readUTF())
        val calleeName = input.readUTF()
        val candidates = List(input.readInt()) { SymbolId.of(input.readUTF()) }
        val receiver = input.readNullableUtf()
        val enclosingSymbolId = input.readNullableUtf()?.let(SymbolId::of)
        val parentCallId = input.readNullableUtf()?.let(CallSiteId::of)
        val range = SourceRange.of(input.readSourceLocation(), input.readSourceLocation())
        val arguments = List(input.readInt()) { input.readCallArgument() }
        return CallSite(
            id = id,
            calleeName = calleeName,
            candidateSymbolIds = candidates,
            receiver = receiver,
            enclosingSymbolId = enclosingSymbolId,
            parentCallId = parentCallId,
            range = range,
            arguments = arguments,
            confidence = decodeConfidence(input.readUTF()),
        )
    }

    @OptIn(IndexinoInternalApi::class)
    private fun DataOutputStream.writeCallArgument(argument: CallArgument) {
        writeInt(argument.position)
        writeNullableUtf(argument.resolvedName)
        writeUTF(argument.kind.value)
        writeSourceLocation(argument.range.start)
        writeSourceLocation(argument.range.end)
        writeInt(argument.nestedCallIds.size)
        argument.nestedCallIds.forEach { nested -> writeUTF(nested.value) }
    }

    @OptIn(IndexinoInternalApi::class)
    private fun DataInputStream.readCallArgument(): CallArgument =
        CallArgument(
            position = readInt(),
            resolvedName = readNullableUtf(),
            kind = decodeArgumentKind(readUTF()),
            range = SourceRange.of(readSourceLocation(), readSourceLocation()),
            nestedCallIds = List(readInt()) { CallSiteId.of(readUTF()) },
        )

    private fun decodeConfidence(value: String): ResolutionConfidence =
        when (value) {
            ResolutionConfidence.RESOLVED.value -> ResolutionConfidence.RESOLVED
            ResolutionConfidence.HEURISTIC.value -> ResolutionConfidence.HEURISTIC
            ResolutionConfidence.UNRESOLVED.value -> ResolutionConfidence.UNRESOLVED
            else -> throw RuntimeProtocolException("Unsupported call confidence")
        }

    private fun decodeArgumentKind(value: String): ArgumentKind =
        when (value) {
            ArgumentKind.VALUE.value -> ArgumentKind.VALUE
            ArgumentKind.LAMBDA.value -> ArgumentKind.LAMBDA
            ArgumentKind.TRAILING_LAMBDA.value -> ArgumentKind.TRAILING_LAMBDA
            else -> throw RuntimeProtocolException("Unsupported argument kind")
        }

    @OptIn(IndexinoInternalApi::class)
    private fun DataOutputStream.writeFinding(finding: Finding) {
        writeUTF(finding.plugin.value)
        writeUTF(finding.checkId)
        writeUTF(finding.message)
        writeBoolean(finding.range != null)
        finding.range?.let { range ->
            writeSourceLocation(range.start)
            writeSourceLocation(range.end)
        }
        writeInt(finding.properties.size)
        finding.properties.toSortedMap().forEach { (key, value) ->
            writeUTF(key)
            writeUTF(value)
        }
    }

    @OptIn(IndexinoInternalApi::class)
    private fun readFinding(input: DataInputStream): Finding {
        val plugin = PluginId.of(input.readUTF())
        val checkId = input.readUTF()
        val message = input.readUTF()
        val range =
            if (input.readBoolean()) {
                SourceRange.of(input.readSourceLocation(), input.readSourceLocation())
            } else {
                null
            }
        val properties = buildMap {
            repeat(input.readInt()) { put(input.readUTF(), input.readUTF()) }
        }
        return Finding(plugin, checkId, message, range, properties)
    }

    private fun DataOutputStream.writeSourceFile(file: SourceFile) {
        writeUTF(file.originId.value)
        writeUTF(file.path)
        writeUTF(file.displayPath)
    }

    private fun DataInputStream.readSourceFile(): SourceFile =
        SourceFile.of(SourceOriginId.of(readUTF()), readUTF(), readUTF())

    private fun DataOutputStream.writeSourceLocation(location: SourceLocation) {
        writeSourceFile(location.file)
        writeInt(location.line)
        writeNullableInt(location.column)
        writeNullableInt(location.offset)
    }

    private fun DataInputStream.readSourceLocation(): SourceLocation =
        SourceLocation.of(readSourceFile(), readInt(), readNullableInt(), readNullableInt())

    private fun DataOutputStream.writeNullableInt(value: Int?) {
        writeBoolean(value != null)
        if (value != null) writeInt(value)
    }

    private fun DataInputStream.readNullableInt(): Int? = if (readBoolean()) readInt() else null

    fun emptyResponse(): ByteArray = ByteArray(0)

    private fun decodeSnapshotFreshness(value: String): SnapshotFreshness =
        when (value) {
            SnapshotFreshness.CURRENT.value -> SnapshotFreshness.CURRENT
            SnapshotFreshness.DIRTY.value -> SnapshotFreshness.DIRTY
            SnapshotFreshness.UNKNOWN.value -> SnapshotFreshness.UNKNOWN
            else -> throw RuntimeProtocolException("Unsupported snapshot freshness")
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
