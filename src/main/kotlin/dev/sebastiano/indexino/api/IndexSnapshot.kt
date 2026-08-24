@file:Suppress("LargeClass", "RedundantSuspendModifier", "TooManyFunctions")

package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.core.BASIC_FACT_SCHEMA_VERSION
import dev.sebastiano.indexino.core.plugin.StorePluginFactView
import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.ResourceDefinitionRecord
import dev.sebastiano.indexino.core.record.ResourceUsageRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.sourcelink.LinkIndexService
import dev.sebastiano.indexino.core.sourcelink.SourceLinkRegistryStore
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.engine.PluginRegistry
import dev.sebastiano.indexino.engine.RuntimeProtocolException
import dev.sebastiano.indexino.engine.RuntimeSnapshotClient
import dev.sebastiano.indexino.model.BasicFactQueries
import dev.sebastiano.indexino.model.BasicFactSchemaVersion
import dev.sebastiano.indexino.model.CallQuery
import dev.sebastiano.indexino.model.CallSite
import dev.sebastiano.indexino.model.CheckRequest
import dev.sebastiano.indexino.model.Finding
import dev.sebastiano.indexino.model.IndexFailureCategory
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.LinkGenerationId
import dev.sebastiano.indexino.model.LinkedSourceQuery
import dev.sebastiano.indexino.model.LinkedSourceResult
import dev.sebastiano.indexino.model.NameMatchMode
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.QueryPage
import dev.sebastiano.indexino.model.Reference
import dev.sebastiano.indexino.model.ReferenceQuery
import dev.sebastiano.indexino.model.ResourceDefinition
import dev.sebastiano.indexino.model.ResourceId
import dev.sebastiano.indexino.model.ResourceQuery
import dev.sebastiano.indexino.model.ResourceUsage
import dev.sebastiano.indexino.model.SourceFile
import dev.sebastiano.indexino.model.SourceLinkQueries
import dev.sebastiano.indexino.model.SourceLocation
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.Symbol
import dev.sebastiano.indexino.model.SymbolId
import dev.sebastiano.indexino.model.SymbolQuery
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import dev.sebastiano.indexino.model.WorkspaceRevision
import dev.sebastiano.indexino.plugin.api.CheckContextV1
import java.nio.file.Path
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

public class IndexSnapshot
private constructor(
    private val store: CodeIndexStore?,
    public val revision: WorkspaceRevision,
    override val generation: WorkspaceGenerationId,
    override val basicFactSchemaVersion: BasicFactSchemaVersion =
        BasicFactSchemaVersion.of(BASIC_FACT_SCHEMA_VERSION),
    public val freshnessAtAcquisition: SnapshotFreshness,
    private val onClose: () -> Unit,
    private val pluginRegistry: PluginRegistry?,
    private val remoteClient: RuntimeSnapshotClient? = null,
    private val remoteLeaseId: String? = null,
    private val consumerWorkspace: Path? = null,
    override val linkGeneration: LinkGenerationId? = null,
) : BasicFactQueries, SourceLinkQueries, AutoCloseable {
    private val closed = AtomicBoolean()
    private val queries = IndexSnapshotQueries(generation)
    private val checkResults = ConcurrentHashMap<CheckRequest, CompletableDeferred<List<Finding>>>()
    private val localStore: CodeIndexStore
        get() = checkNotNull(store)

    override suspend fun findSymbols(query: SymbolQuery, options: QueryOptions): QueryPage<Symbol> {
        ensureOpen()
        remoteClient?.let { client ->
            return mapUnexpectedFailures {
                client.findSymbols(checkNotNull(remoteLeaseId), query, options)
            }
        }
        validateQueryOptions(options)
        return mapUnexpectedFailures {
            orderedPage(
                options = options,
                comparator =
                    compareBy(
                        SymbolRecord::fqn,
                        SymbolRecord::originId,
                        SymbolRecord::relativeFile,
                        SymbolRecord::line,
                        SymbolRecord::column,
                        { it.signature.orEmpty() },
                        SymbolRecord::name,
                    ),
                scan = { accept ->
                    localStore.forEachPrefix("sym:") { _, record ->
                        if (record is SymbolRecord && record.matches(query)) {
                            accept(record)
                        }
                        true
                    }
                },
                transform = { records ->
                    val ownerIds = ownerIdsFor(records)
                    records.map { record ->
                        with(queries) { record.toPublicSymbol(ownerIds.getValue(record)) }
                    }
                },
            )
        }
    }

    override suspend fun findReferences(
        query: ReferenceQuery,
        options: QueryOptions,
    ): QueryPage<Reference> {
        ensureOpen()
        remoteClient?.let { client ->
            return mapUnexpectedFailures {
                client.findReferences(checkNotNull(remoteLeaseId), query, options)
            }
        }
        validateQueryOptions(options)
        return mapUnexpectedFailures {
            val targetSymbol = findSymbolById(query.symbolId)
            val unknownTargetNames =
                if (targetSymbol == null) unknownReferenceNamesForId(query.symbolId)
                else UnknownReferenceNames()
            val unknownTargetCandidates = candidatesByName(unknownTargetNames.all)
            val targetCandidates =
                targetSymbol
                    ?.let { candidatesByName((it.aliases + it.fqn).toSet()).values.flatten() }
                    .orEmpty()
            orderedPage(
                options = options,
                comparator =
                    compareBy(
                        ReferenceRecord::originId,
                        ReferenceRecord::relativeFile,
                        ReferenceRecord::line,
                        ReferenceRecord::column,
                        ReferenceRecord::referencedName,
                        ReferenceRecord::symbolFqn,
                    ),
                scan = { accept ->
                    localStore.forEachPrefix("ref:") { _, record ->
                        if (record is ReferenceRecord) {
                            val matches =
                                with(queries) {
                                    if (targetSymbol != null) {
                                        record.canTarget(targetSymbol) &&
                                            record.matchesTargetOrigin(
                                                targetCandidates,
                                                targetSymbol.originId,
                                            )
                                    } else {
                                        record.matchesUnknownSymbolId(
                                            externalNames = unknownTargetNames.external,
                                            ambiguousNames = unknownTargetNames.ambiguous,
                                            candidatesByName = unknownTargetCandidates,
                                        )
                                    }
                                }
                            if (matches) {
                                accept(record)
                            }
                        }
                        true
                    }
                },
                transform = { records ->
                    val candidates = candidatesFor(records)
                    records.map { record ->
                        with(queries) { record.toPublicReference(checkNotNull(candidates[record])) }
                    }
                },
            )
        }
    }

    override suspend fun findResources(
        query: ResourceQuery,
        options: QueryOptions,
    ): QueryPage<ResourceDefinition> {
        ensureOpen()
        remoteClient?.let { client ->
            return mapUnexpectedFailures {
                client.findResources(checkNotNull(remoteLeaseId), query, options)
            }
        }
        validateQueryOptions(options)
        return mapUnexpectedFailures {
            orderedPage(
                options = options,
                comparator =
                    compareBy(
                        { it.packageName.orEmpty() },
                        ResourceDefinitionRecord::type,
                        ResourceDefinitionRecord::name,
                        ResourceDefinitionRecord::qualifiers,
                        ResourceDefinitionRecord::originId,
                        ResourceDefinitionRecord::relativeFile,
                        ResourceDefinitionRecord::line,
                    ),
                scan = { accept ->
                    localStore.forEachPrefix("resdef:") { _, record ->
                        if (record is ResourceDefinitionRecord && record.matches(query))
                            accept(record)
                        true
                    }
                },
                transform = { records -> records.map(::toPublicResourceDefinition) },
            )
        }
    }

    override suspend fun findResourceUsages(
        query: ResourceQuery,
        options: QueryOptions,
    ): QueryPage<ResourceUsage> {
        ensureOpen()
        remoteClient?.let { client ->
            return mapUnexpectedFailures {
                client.findResourceUsages(checkNotNull(remoteLeaseId), query, options)
            }
        }
        validateQueryOptions(options)
        return mapUnexpectedFailures {
            orderedPage(
                options = options,
                comparator =
                    compareBy(
                        { it.packageName.orEmpty() },
                        ResourceUsageRecord::type,
                        ResourceUsageRecord::name,
                        ResourceUsageRecord::originId,
                        ResourceUsageRecord::relativeFile,
                        ResourceUsageRecord::line,
                        ResourceUsageRecord::column,
                    ),
                scan = { accept ->
                    localStore.forEachPrefix("resuse:") { _, record ->
                        if (record is ResourceUsageRecord && record.matches(query)) accept(record)
                        true
                    }
                },
                transform = { records -> records.map(::toPublicResourceUsage) },
            )
        }
    }

    override suspend fun findCalls(query: CallQuery, options: QueryOptions): QueryPage<CallSite> {
        ensureOpen()
        remoteClient?.let { client ->
            return mapUnexpectedFailures {
                client.findCalls(checkNotNull(remoteLeaseId), query, options)
            }
        }
        validateQueryOptions(options)
        return mapUnexpectedFailures {
            val enclosing = query.enclosingSymbolId?.let(::findSymbolById)
            val unresolvedEnclosingId = query.enclosingSymbolId != null && enclosing == null
            orderedPage(
                options = options,
                comparator =
                    compareBy(
                        CallSiteRecord::originId,
                        CallSiteRecord::relativeFile,
                        CallSiteRecord::startOffset,
                        CallSiteRecord::endOffset,
                        CallSiteRecord::calleeName,
                        CallSiteRecord::identity,
                    ),
                scan = { accept ->
                    if (!unresolvedEnclosingId) {
                        localStore.forEachPrefix("call:") { _, record ->
                            if (record is CallSiteRecord && record.matches(query, enclosing))
                                accept(record)
                            true
                        }
                    }
                },
                transform = { records ->
                    val candidates = callCandidatesFor(records)
                    records.map { record ->
                        with(queries) { record.toPublicCallSite(candidates.getValue(record)) }
                    }
                },
            )
        }
    }

    @OptIn(IndexinoInternalApi::class)
    public suspend fun runCheck(request: CheckRequest, options: QueryOptions): QueryPage<Finding> {
        ensureOpen()
        remoteClient?.let { client ->
            return mapUnexpectedFailuresSuspend {
                client.runCheck(checkNotNull(remoteLeaseId), request, options)
            }
        }
        validateCheckQueryOptions(options)
        return mapUnexpectedFailuresSuspend {
            val candidate = CompletableDeferred<List<Finding>>()
            val deferred = checkResults.putIfAbsent(request, candidate) ?: candidate
            if (deferred === candidate) {
                try {
                    val registered =
                        checkNotNull(pluginRegistry).checks.firstOrNull {
                            it.pluginId == request.pluginId && it.check.id == request.checkId
                        }
                            ?: throw indexinoFailure(
                                category = IndexFailureCategory.INVALID_REQUEST,
                                code = "unknown_check",
                                message =
                                    "No check '${request.checkId}' is registered for ${request.pluginId.value}",
                                retryable = false,
                            )
                    candidate.complete(
                        registered.check
                            .run(
                                CheckContextV1(
                                    queries = this,
                                    facts = StorePluginFactView(localStore, request.pluginId.value),
                                    active = { !closed.get() },
                                )
                            )
                            .toList()
                    )
                } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
                    candidate.completeExceptionally(thrown)
                    checkResults.remove(request, candidate)
                }
            }
            val findings = deferred.await()
            val start = options.offset.coerceAtMost(findings.size)
            val end = (start + options.limit).coerceAtMost(findings.size)
            QueryPage(
                items = findings.subList(start, end),
                offset = options.offset,
                limit = options.limit,
                hasMore = end < findings.size,
                nextCursor = null,
                totalCount = findings.size,
            )
        }
    }

    @OptIn(IndexinoInternalApi::class)
    override suspend fun findLinkedSources(
        query: LinkedSourceQuery,
        options: QueryOptions,
    ): QueryPage<LinkedSourceResult> {
        ensureOpen()
        val workspace =
            consumerWorkspace
                ?: return QueryPage(
                    items = emptyList(),
                    offset = options.offset,
                    limit = options.limit,
                    hasMore = false,
                    nextCursor = null,
                    totalCount = 0,
                )
        val registrySnapshot =
            SourceLinkRegistryStore(
                    InProcessCacheLayout.cacheRoot()
                        .resolve("workspaces")
                        .resolve(InProcessCacheLayout.workspaceId(workspace))
                )
                .readCurrent()
                ?: return QueryPage(
                    items = emptyList(),
                    offset = options.offset,
                    limit = options.limit,
                    hasMore = false,
                    nextCursor = null,
                    totalCount = 0,
                )
        return LinkIndexService(InProcessCacheLayout.cacheRoot(), workspace)
            .findLinkedSources(registrySnapshot, query, options)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                store?.let { localStore -> mapUnexpectedFailures { localStore.close() } }
            } finally {
                // Unpin even when store.close fails — a leaked generation pin is worse than a
                // mapped close failure.
                mapUnexpectedFailures(onClose)
            }
        }
    }

    private fun ensureOpen() {
        if (closed.get()) {
            throw indexinoFailure(
                category = IndexFailureCategory.CLOSED,
                code = "snapshot_closed",
                message = "IndexSnapshot is closed",
                retryable = false,
            )
        }
    }

    private fun SymbolRecord.matches(query: SymbolQuery): Boolean {
        val requestedFile = query.file
        val fileMatches =
            requestedFile == null ||
                (originId == requestedFile.originId.value && relativeFile == requestedFile.path)
        val kindMatches = query.kind == null || kind == query.kind
        val languageMatches = query.language == null || language == query.language
        val requestedName = query.name
        val nameMatches =
            requestedName == null ||
                when (query.match) {
                    NameMatchMode.EXACT -> name == requestedName || requestedName in aliases
                    NameMatchMode.PREFIX ->
                        name.startsWith(requestedName) ||
                            fqn.startsWith(requestedName) ||
                            aliases.any { it.startsWith(requestedName) }
                    NameMatchMode.FQN -> fqn == requestedName
                }
        return fileMatches && kindMatches && languageMatches && nameMatches
    }

    private fun CallSiteRecord.matches(query: CallQuery, enclosing: SymbolRecord?): Boolean {
        val requestedFile = query.file
        val fileMatches =
            requestedFile == null ||
                (originId == requestedFile.originId.value && relativeFile == requestedFile.path)
        val enclosingMatches =
            enclosing == null ||
                (originId == enclosing.originId &&
                    enclosingSymbolFqn in (enclosing.aliases + enclosing.fqn))
        return fileMatches &&
            (query.calleeName == null || calleeName == query.calleeName) &&
            (query.callSiteId == null || with(queries) { callSiteId() == query.callSiteId }) &&
            enclosingMatches
    }

    private fun findSymbolById(symbolId: SymbolId): SymbolRecord? {
        var result: SymbolRecord? = null
        localStore.forEachPrefix("sym:") { _, record ->
            if (record is SymbolRecord && with(queries) { record.definitionId() == symbolId }) {
                result = record
                false
            } else {
                true
            }
        }
        return result
    }

    private fun unknownReferenceNamesForId(symbolId: SymbolId): UnknownReferenceNames {
        val external = LinkedHashSet<String>()
        val ambiguous = LinkedHashSet<String>()
        localStore.forEachPrefix("ref:") { _, record ->
            if (record is ReferenceRecord) {
                val recordNames = record.candidateSymbolFqns + record.symbolFqn
                with(queries) {
                    recordNames.filterTo(external) { externalSymbolId(it) == symbolId }
                    if (ambiguousSymbolId(record.symbolFqn) == symbolId) {
                        ambiguous += record.symbolFqn
                    }
                }
            }
            true
        }
        return UnknownReferenceNames(external = external, ambiguous = ambiguous)
    }

    private fun candidatesByName(names: Set<String>): Map<String, List<SymbolRecord>> {
        if (names.isEmpty()) return emptyMap()
        val candidates = names.associateWith { mutableListOf<SymbolRecord>() }
        localStore.forEachPrefix("sym:") { _, record ->
            if (record is SymbolRecord) {
                for (name in names) {
                    if (record.fqn == name || name in record.aliases) {
                        candidates.getValue(name) += record
                    }
                }
            }
            true
        }
        return candidates
    }

    private fun callCandidatesFor(
        calls: List<CallSiteRecord>
    ): Map<CallSiteRecord, List<SymbolRecord>> {
        val candidatesByName =
            candidatesByName(
                calls.flatMapTo(LinkedHashSet()) {
                    it.candidateSymbolFqns + listOfNotNull(it.enclosingSymbolFqn)
                }
            )
        return calls.associateWith { call ->
            val calleeCandidates =
                call.candidateSymbolFqns.flatMap { name ->
                    val candidates =
                        candidatesByName[name].orEmpty().filter { candidate ->
                            candidate.arity == null ||
                                candidate.arity == call.arguments.size ||
                                (candidate.language == "kotlin" &&
                                    candidate.arity > call.arguments.size) ||
                                (candidate.isVararg && candidate.arity - 1 <= call.arguments.size)
                        }
                    candidates.filter { it.originId == call.originId }.ifEmpty { candidates }
                }
            val enclosingCandidates =
                call.enclosingSymbolFqn
                    ?.let { enclosing ->
                        candidatesByName[enclosing].orEmpty().filter { candidate ->
                            candidate.originId == call.originId
                        }
                    }
                    .orEmpty()
            (calleeCandidates + enclosingCandidates).distinct()
        }
    }

    private fun ReferenceRecord.matchesTargetOrigin(
        candidates: List<SymbolRecord>,
        targetOriginId: String,
    ): Boolean {
        val sameOrigin = candidates.any {
            it.originId == originId && with(queries) { isArityCompatibleWith(it) }
        }
        return !sameOrigin || originId == targetOriginId
    }

    private fun ReferenceRecord.matchesUnknownSymbolId(
        externalNames: Set<String>,
        ambiguousNames: Set<String>,
        candidatesByName: Map<String, List<SymbolRecord>>,
    ): Boolean {
        fun localCandidates(name: String): List<SymbolRecord> =
            candidatesByName[name].orEmpty().filter { with(queries) { isArityCompatibleWith(it) } }

        if (symbolFqn in ambiguousNames && localCandidates(symbolFqn).size > 1) return true
        if (symbolFqn in externalNames && localCandidates(symbolFqn).isEmpty()) return true
        return candidateSymbolFqns.any { name ->
            name in externalNames && localCandidates(name).isEmpty()
        }
    }

    private class UnknownReferenceNames(
        val external: Set<String> = emptySet(),
        val ambiguous: Set<String> = emptySet(),
    ) {
        val all: Set<String> = external + ambiguous
    }

    private fun candidatesFor(
        references: List<ReferenceRecord>
    ): Map<ReferenceRecord, List<SymbolRecord>> {
        if (references.isEmpty()) return emptyMap()
        val namesByReference = references.associateWith { it.candidateSymbolFqns + it.symbolFqn }
        val matchesByReference =
            LinkedHashMap<ReferenceRecord, LinkedHashMap<String, MutableList<SymbolRecord>>>()
        localStore.forEachPrefix("sym:") { _, record ->
            if (record is SymbolRecord) {
                for ((reference, names) in namesByReference) {
                    if (with(queries) { reference.isArityCompatibleWith(record) }) {
                        for (name in names) {
                            if (name == record.fqn || name in record.aliases) {
                                matchesByReference
                                    .getOrPut(reference) { LinkedHashMap() }
                                    .getOrPut(name) { mutableListOf() }
                                    .add(record)
                            }
                        }
                    }
                }
            }
            true
        }
        return references.associateWith { reference ->
            val candidates = LinkedHashSet<SymbolRecord>()
            for (name in namesByReference.getValue(reference)) {
                matchesByReference[reference]?.get(name)?.let(candidates::addAll)
            }
            val sameOrigin = candidates.filter { it.originId == reference.originId }
            (sameOrigin.ifEmpty { candidates }).toList()
        }
    }

    private fun ownerIdsFor(symbols: List<SymbolRecord>): Map<SymbolRecord, SymbolId?> {
        val symbolsByOwner =
            symbols
                .mapNotNull { symbol -> symbol.ownerFqn?.let { it to symbol } }
                .groupBy({ it.first }, { it.second })
        if (symbolsByOwner.isEmpty()) return symbols.associateWith { null }

        val candidates: MutableMap<SymbolRecord, OwnerCandidates> =
            symbolsByOwner.values.flatten().associateWith { OwnerCandidates() }.toMutableMap()
        localStore.forEachPrefix("sym:") { _, record ->
            if (record is SymbolRecord) {
                val owners = buildSet {
                    if (record.fqn in symbolsByOwner) add(record.fqn)
                    record.aliases.filterTo(this) { it in symbolsByOwner }
                }
                for (owner in owners) {
                    for (symbol in symbolsByOwner.getValue(owner)) {
                        candidates
                            .getValue(symbol)
                            .consider(owner, symbol.originId, symbol.relativeFile, record)
                    }
                }
            }
            true
        }
        return symbols.associateWith { symbol ->
            symbol.ownerFqn?.let { owner ->
                with(queries) {
                    candidates.getValue(symbol).best()?.definitionId() ?: externalSymbolId(owner)
                }
            }
        }
    }

    private class OwnerCandidates {
        private var sameFileExact: SymbolRecord? = null
        private var sameFileAlias: SymbolRecord? = null
        private var exact: SymbolRecord? = null
        private var alias: SymbolRecord? = null

        fun consider(
            owner: String,
            symbolOriginId: String,
            symbolFile: String,
            candidate: SymbolRecord,
        ) {
            if (candidate.originId != symbolOriginId) return
            when {
                candidate.fqn == owner && candidate.relativeFile == symbolFile ->
                    sameFileExact = sameFileExact ?: candidate
                candidate.relativeFile == symbolFile -> sameFileAlias = sameFileAlias ?: candidate
                candidate.fqn == owner -> exact = exact ?: candidate
                else -> alias = alias ?: candidate
            }
        }

        fun best(): SymbolRecord? = sameFileExact ?: sameFileAlias ?: exact ?: alias
    }

    private suspend fun <T> mapUnexpectedFailuresSuspend(block: suspend () -> T): T =
        try {
            block()
        } catch (thrown: IndexinoException) {
            throw thrown
        } catch (thrown: RuntimeProtocolException) {
            thrown.failure?.let { failure -> throw IndexinoException(failure, thrown) }
            throw unexpectedFailure(thrown)
        } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
            throw unexpectedFailure(thrown)
        }

    private fun <T> mapUnexpectedFailures(block: () -> T): T =
        try {
            block()
        } catch (thrown: IndexinoException) {
            throw thrown
        } catch (thrown: RuntimeProtocolException) {
            thrown.failure?.let { failure -> throw IndexinoException(failure, thrown) }
            throw unexpectedFailure(thrown)
        } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
            throw unexpectedFailure(thrown)
        }

    private fun unexpectedFailure(thrown: Throwable): IndexinoException =
        indexinoFailure(
            category = IndexFailureCategory.INTERNAL,
            code = "internal",
            message = thrown.message?.takeIf { it.isNotBlank() } ?: thrown.javaClass.simpleName,
            retryable = false,
            cause = thrown,
        )

    @OptIn(IndexinoInternalApi::class)
    private fun validateCheckQueryOptions(options: QueryOptions) {
        if (options.limit > HOST_QUERY_LIMIT_MAXIMUM) {
            throw indexinoFailure(
                category = IndexFailureCategory.INVALID_REQUEST,
                code = "limit_exceeds_maximum",
                message =
                    "limit ${options.limit} exceeds the host maximum of $HOST_QUERY_LIMIT_MAXIMUM",
                retryable = false,
            )
        }
        parsePageOffset(options)
    }

    @OptIn(IndexinoInternalApi::class)
    private fun validateQueryOptions(options: QueryOptions) {
        if (options.limit > HOST_QUERY_LIMIT_MAXIMUM) {
            throw indexinoFailure(
                category = IndexFailureCategory.INVALID_REQUEST,
                code = "limit_exceeds_maximum",
                message =
                    "limit ${options.limit} exceeds the host maximum of $HOST_QUERY_LIMIT_MAXIMUM",
                retryable = false,
            )
        }
        validatePageWindow(parsePageOffset(options), options.limit)
    }

    @OptIn(IndexinoInternalApi::class)
    private fun toPublicResourceDefinition(record: ResourceDefinitionRecord): ResourceDefinition =
        ResourceDefinition(
            ResourceId.of(record.packageName, record.type, record.name),
            record.qualifiers,
            SourceLocation.of(
                SourceFile.of(
                    SourceOriginId.of(record.originId),
                    record.relativeFile,
                    record.relativeFile,
                ),
                record.line,
                record.column,
                record.offset,
            ),
        )

    @OptIn(IndexinoInternalApi::class)
    private fun toPublicResourceUsage(record: ResourceUsageRecord): ResourceUsage =
        ResourceUsage(
            ResourceId.of(record.packageName, record.type, record.name),
            SourceLocation.of(
                SourceFile.of(
                    SourceOriginId.of(record.originId),
                    record.relativeFile,
                    record.relativeFile,
                ),
                record.line,
                record.column,
                record.offset,
            ),
            record.language,
        )

    private fun ResourceDefinitionRecord.matches(query: ResourceQuery): Boolean =
        query.id?.let { it.packageName == packageName && it.type == type && it.name == name }
            ?: ((query.packageName == null || query.packageName == packageName) &&
                (query.type == null || query.type == type) &&
                (query.name == null || query.name == name))

    private fun ResourceUsageRecord.matches(query: ResourceQuery): Boolean =
        query.id?.let { it.packageName == packageName && it.type == type && it.name == name }
            ?: ((query.packageName == null || query.packageName == packageName) &&
                (query.type == null || query.type == type) &&
                (query.name == null || query.name == name))

    @OptIn(IndexinoInternalApi::class)
    private fun <T, R> orderedPage(
        options: QueryOptions,
        comparator: Comparator<T>,
        scan: ((T) -> Unit) -> Unit,
        transform: (List<T>) -> List<R>,
    ): QueryPage<R> {
        val offset = parsePageOffset(options)
        validatePageWindow(offset, options.limit)
        val windowSize = offset + options.limit + 1
        val retained = PriorityQueue<T>(windowSize, comparator.reversed())
        scan { candidate ->
            if (retained.size < windowSize) {
                retained.add(candidate)
            } else if (comparator.compare(candidate, retained.peek()) < 0) {
                retained.remove()
                retained.add(candidate)
            }
        }
        val ordered = retained.sortedWith(comparator)
        val end = minOf(offset + options.limit, ordered.size)
        if (ordered.size > end && end == HOST_QUERY_WINDOW_MAXIMUM) {
            throw indexinoFailure(
                category = IndexFailureCategory.INVALID_REQUEST,
                code = "result_window_exceeds_maximum",
                message =
                    "Query result window exceeds the host maximum of $HOST_QUERY_WINDOW_MAXIMUM",
                retryable = false,
            )
        }
        val pageItems = if (offset >= ordered.size) emptyList() else ordered.subList(offset, end)
        val items = transform(pageItems)
        val hasMore = ordered.size > end
        return QueryPage(
            items = items,
            offset = offset,
            limit = options.limit,
            hasMore = hasMore,
            nextCursor = "$CURSOR_PREFIX$end".takeIf { hasMore },
            totalCount = null,
        )
    }

    private fun parsePageOffset(options: QueryOptions): Int =
        options.afterCursor?.let { cursor ->
            val decoded = cursor.removePrefix(CURSOR_PREFIX)
            if (decoded == cursor || decoded.toIntOrNull()?.let { it >= 0 } != true) {
                throw indexinoFailure(
                    category = IndexFailureCategory.INVALID_REQUEST,
                    code = "invalid_cursor",
                    message = "Query cursor is invalid",
                    retryable = false,
                )
            }
            decoded.toInt()
        } ?: options.offset

    private fun validatePageWindow(offset: Int, limit: Int) {
        if (offset > HOST_QUERY_WINDOW_MAXIMUM) {
            throw indexinoFailure(
                category = IndexFailureCategory.INVALID_REQUEST,
                code = "offset_exceeds_maximum",
                message = "offset $offset exceeds the host maximum of $HOST_QUERY_WINDOW_MAXIMUM",
                retryable = false,
            )
        }
        if (offset.toLong() + limit > HOST_QUERY_WINDOW_MAXIMUM) {
            throw indexinoFailure(
                category = IndexFailureCategory.INVALID_REQUEST,
                code = "page_window_exceeds_maximum",
                message = "Query offset and limit exceed the host result maximum",
                retryable = false,
            )
        }
    }

    internal companion object {
        private const val CURSOR_PREFIX: String = "indexino:v1:"
        private const val BASIC_FACT_SCHEMA_VERSION: Int = 3
        // Host policy for this in-process facade. Not a public ABI constant until the owner
        // settles exact default page limits in docs/PUBLIC-API-DESIGN.html.
        private const val HOST_QUERY_LIMIT_MAXIMUM: Int = 10_000
        private const val HOST_QUERY_WINDOW_MAXIMUM: Int = 10_000

        internal fun createRemote(
            client: RuntimeSnapshotClient,
            leaseId: String,
            revision: WorkspaceRevision,
            generation: WorkspaceGenerationId,
            freshnessAtAcquisition: SnapshotFreshness,
            onClose: () -> Unit,
        ): IndexSnapshot =
            IndexSnapshot(
                store = null,
                revision = revision,
                generation = generation,
                freshnessAtAcquisition = freshnessAtAcquisition,
                onClose = onClose,
                pluginRegistry = null,
                remoteClient = client,
                remoteLeaseId = leaseId,
            )

        internal fun create(
            store: CodeIndexStore,
            revision: WorkspaceRevision,
            generation: WorkspaceGenerationId,
            freshnessAtAcquisition: SnapshotFreshness = SnapshotFreshness.UNKNOWN,
            onClose: () -> Unit = {},
            consumerWorkspace: Path? = null,
            linkGeneration: LinkGenerationId? = null,
        ): IndexSnapshot =
            IndexSnapshot(
                store = store,
                revision = revision,
                generation = generation,
                freshnessAtAcquisition = freshnessAtAcquisition,
                onClose = onClose,
                pluginRegistry = PluginRegistry.load(IndexSnapshot::class.java.classLoader),
                consumerWorkspace = consumerWorkspace,
                linkGeneration = linkGeneration,
            )
    }
}
