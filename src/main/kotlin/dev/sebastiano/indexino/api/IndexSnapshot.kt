@file:Suppress("RedundantSuspendModifier", "TooManyFunctions")

package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.model.IndexFailureCategory
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.NameMatchMode
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.QueryPage
import dev.sebastiano.indexino.model.Reference
import dev.sebastiano.indexino.model.ReferenceQuery
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.Symbol
import dev.sebastiano.indexino.model.SymbolId
import dev.sebastiano.indexino.model.SymbolQuery
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import dev.sebastiano.indexino.model.WorkspaceRevision
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean

public class IndexSnapshot
private constructor(
    private val store: CodeIndexStore,
    public val revision: WorkspaceRevision,
    public val generation: WorkspaceGenerationId,
    public val freshnessAtAcquisition: SnapshotFreshness,
    private val onClose: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val queries = IndexSnapshotQueries(generation)

    public suspend fun findSymbols(query: SymbolQuery, options: QueryOptions): QueryPage<Symbol> {
        ensureOpen()
        validateQueryOptions(options)
        return mapUnexpectedFailures {
            orderedPage(
                options = options,
                comparator =
                    compareBy(
                        SymbolRecord::fqn,
                        SymbolRecord::relativeFile,
                        SymbolRecord::line,
                        { it.signature.orEmpty() },
                        SymbolRecord::name,
                    ),
                scan = { accept ->
                    store.forEachPrefix("sym:") { _, record ->
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

    public suspend fun findReferences(
        query: ReferenceQuery,
        options: QueryOptions,
    ): QueryPage<Reference> {
        ensureOpen()
        validateQueryOptions(options)
        return mapUnexpectedFailures {
            val targetSymbol = findSymbolById(query.symbolId)
            val unknownTargetNames =
                if (targetSymbol == null) unknownReferenceNamesForId(query.symbolId)
                else UnknownReferenceNames()
            val unknownTargetCandidates = candidatesByName(unknownTargetNames.all)
            orderedPage(
                options = options,
                comparator =
                    compareBy(
                        ReferenceRecord::relativeFile,
                        ReferenceRecord::line,
                        ReferenceRecord::column,
                        ReferenceRecord::referencedName,
                        ReferenceRecord::symbolFqn,
                    ),
                scan = { accept ->
                    store.forEachPrefix("ref:") { _, record ->
                        if (record is ReferenceRecord) {
                            val matches =
                                with(queries) {
                                    if (targetSymbol != null) {
                                        record.canTarget(targetSymbol)
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

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                mapUnexpectedFailures { store.close() }
            } finally {
                // Unpin even when store.close fails — a leaked generation pin is worse than a
                // mapped close failure.
                onClose()
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
                (requestedFile.originId == WORKSPACE_ORIGIN && relativeFile == requestedFile.path)
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

    private fun findSymbolById(symbolId: SymbolId): SymbolRecord? {
        var result: SymbolRecord? = null
        store.forEachPrefix("sym:") { _, record ->
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
        store.forEachPrefix("ref:") { _, record ->
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
        store.forEachPrefix("sym:") { _, record ->
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
        store.forEachPrefix("sym:") { _, record ->
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
            candidates.toList()
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
        store.forEachPrefix("sym:") { _, record ->
            if (record is SymbolRecord) {
                val owners = buildSet {
                    if (record.fqn in symbolsByOwner) add(record.fqn)
                    record.aliases.filterTo(this) { it in symbolsByOwner }
                }
                for (owner in owners) {
                    for (symbol in symbolsByOwner.getValue(owner)) {
                        candidates.getValue(symbol).consider(owner, symbol.relativeFile, record)
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

        fun consider(owner: String, symbolFile: String, candidate: SymbolRecord) {
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

    private fun <T> mapUnexpectedFailures(block: () -> T): T =
        try {
            block()
        } catch (thrown: IndexinoException) {
            throw thrown
        } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
            // Public entry points throw final IndexinoException. Unexpected failures map to
            // INTERNAL with cause retained — do not classify by exception message/type. Specific
            // codes (e.g. workspace_path_unresolvable at connect) are for known failure modes.
            throw indexinoFailure(
                category = IndexFailureCategory.INTERNAL,
                code = "internal",
                message = thrown.message?.takeIf { it.isNotBlank() } ?: thrown.javaClass.simpleName,
                retryable = false,
                cause = thrown,
            )
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
        // Host policy for this in-process facade. Not a public ABI constant until the owner
        // settles exact default page limits in docs/PUBLIC-API-DESIGN.html.
        private const val HOST_QUERY_LIMIT_MAXIMUM: Int = 10_000
        private const val HOST_QUERY_WINDOW_MAXIMUM: Int = 10_000
        private val WORKSPACE_ORIGIN: SourceOriginId = SourceOriginId.of("workspace")

        internal fun create(
            store: CodeIndexStore,
            revision: WorkspaceRevision,
            generation: WorkspaceGenerationId,
            onClose: () -> Unit = {},
        ): IndexSnapshot =
            IndexSnapshot(
                store = store,
                revision = revision,
                generation = generation,
                // S1 has no watcher / acquisition-time rehash, so currency cannot be proven.
                // CURRENT becomes reportable with AWAIT_CURRENT (S3) and watcher reconciliation
                // (S7).
                freshnessAtAcquisition = SnapshotFreshness.UNKNOWN,
                onClose = onClose,
            )
    }
}
