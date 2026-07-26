@file:Suppress("RedundantSuspendModifier")

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
import dev.sebastiano.indexino.model.SymbolQuery
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import dev.sebastiano.indexino.model.WorkspaceRevision
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
            val symbols = symbolRecords()
            val symbolsByName = queries.indexSymbolsByName(symbols)
            val records =
                symbols
                    .asSequence()
                    .filter { it.matches(query) }
                    .sortedWith(
                        compareBy(
                            SymbolRecord::fqn,
                            SymbolRecord::relativeFile,
                            SymbolRecord::line,
                            { it.signature.orEmpty() },
                            SymbolRecord::name,
                        )
                    )
                    .map { with(queries) { it.toPublicSymbol(symbolsByName) } }
                    .toList()
            records.page(options)
        }
    }

    public suspend fun findReferences(
        query: ReferenceQuery,
        options: QueryOptions,
    ): QueryPage<Reference> {
        ensureOpen()
        validateQueryOptions(options)
        return mapUnexpectedFailures {
            val symbols = symbolRecords()
            val symbolsByName = queries.indexSymbolsByName(symbols)
            val targetSymbol =
                with(queries) { symbols.firstOrNull { it.definitionId() == query.symbolId } }
            val records =
                store
                    .prefixScan("ref:")
                    .map { it.second }
                    .filterIsInstance<ReferenceRecord>()
                    .filter { reference ->
                        with(queries) {
                            if (targetSymbol != null) {
                                reference.canTarget(targetSymbol)
                            } else {
                                reference.matchesSymbolId(query.symbolId, symbolsByName)
                            }
                        }
                    }
                    .sortedWith(
                        compareBy(
                            ReferenceRecord::relativeFile,
                            ReferenceRecord::line,
                            ReferenceRecord::column,
                            ReferenceRecord::referencedName,
                            ReferenceRecord::symbolFqn,
                        )
                    )
                    .map { with(queries) { it.toPublicReference(symbolsByName) } }
                    .toList()
            records.page(options)
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

    private fun symbolRecords(): List<SymbolRecord> =
        // Resource definitions stay in producer storage for the CLI, but the embedded API must not
        // expose them through Symbol queries before the S10 resource model lands.
        store.prefixScan("sym:").map { it.second }.filterIsInstance<SymbolRecord>().toList()

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
    }

    @OptIn(IndexinoInternalApi::class)
    private fun <T> List<T>.page(options: QueryOptions): QueryPage<T> {
        val offset =
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
        val end = (offset.toLong() + options.limit).coerceAtMost(size.toLong()).toInt()
        val items = if (offset >= size) emptyList() else subList(offset, end)
        val hasMore = end < size
        return QueryPage(
            items = items,
            offset = offset,
            limit = options.limit,
            hasMore = hasMore,
            nextCursor = "$CURSOR_PREFIX$end".takeIf { hasMore },
            totalCount = size,
        )
    }

    internal companion object {
        private const val CURSOR_PREFIX: String = "indexino:v1:"
        // Host policy for this in-process facade. Not a public ABI constant until the owner
        // settles exact default page limits in docs/PUBLIC-API-DESIGN.html.
        private const val HOST_QUERY_LIMIT_MAXIMUM: Int = 10_000
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
