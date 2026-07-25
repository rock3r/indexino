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
import dev.sebastiano.indexino.model.SourceFile
import dev.sebastiano.indexino.model.SourceLocation
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.Symbol
import dev.sebastiano.indexino.model.SymbolId
import dev.sebastiano.indexino.model.SymbolQuery
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import dev.sebastiano.indexino.model.WorkspaceRevision
import java.security.MessageDigest
import java.util.HexFormat
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

    public suspend fun findSymbols(query: SymbolQuery, options: QueryOptions): QueryPage<Symbol> {
        ensureOpen()
        val symbols = symbolRecords()
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
                .map { it.toPublicSymbol(symbols) }
                .toList()
        return records.page(options)
    }

    public suspend fun findReferences(
        query: ReferenceQuery,
        options: QueryOptions,
    ): QueryPage<Reference> {
        ensureOpen()
        val symbols = symbolRecords()
        val records =
            store
                .prefixScan("ref:")
                .map { it.second }
                .filterIsInstance<ReferenceRecord>()
                .sortedWith(
                    compareBy(
                        ReferenceRecord::relativeFile,
                        ReferenceRecord::line,
                        ReferenceRecord::column,
                        ReferenceRecord::referencedName,
                        ReferenceRecord::symbolFqn,
                    )
                )
                .map { it.toPublicReference(symbols) }
                .filter { it.symbolId == query.symbolId || query.symbolId in it.candidateSymbolIds }
                .toList()
        return records.page(options)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                store.close()
            } finally {
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

    @OptIn(IndexinoInternalApi::class)
    private fun SymbolRecord.toPublicSymbol(symbols: List<SymbolRecord>): Symbol {
        val location = sourceLocation(relativeFile, line, null)
        return Symbol(
            id = definitionId(),
            name = name,
            kind = kind,
            language = language,
            location = location,
            range = null,
            ownerId =
                ownerFqn?.let { owner ->
                    symbols.firstOrNull { it.fqn == owner }?.definitionId() ?: externalId(owner)
                },
            signature = signature,
            arity = arity,
            aliases = aliases,
        )
    }

    @OptIn(IndexinoInternalApi::class)
    private fun ReferenceRecord.toPublicReference(symbols: List<SymbolRecord>): Reference {
        val candidates = symbols.filter { canTarget(it) }
        val direct =
            candidates
                .firstOrNull { it.fqn == symbolFqn || symbolFqn in it.aliases }
                ?.definitionId() ?: externalId(symbolFqn)
        val candidateIds =
            candidates.map { it.definitionId() }.ifEmpty { candidateSymbolFqns.map(::externalId) }
        return Reference(
            symbolId = direct,
            referencedName = referencedName,
            language = language,
            location = sourceLocation(relativeFile, line, column.takeIf { it >= 1 }),
            qualifier = qualifier,
            candidateSymbolIds = candidateIds,
            arity = arity,
        )
    }

    private fun ReferenceRecord.canTarget(symbol: SymbolRecord): Boolean {
        val targetNames = candidateSymbolFqns + symbolFqn
        val symbolNames = symbol.aliases + symbol.fqn
        val nameMatches = targetNames.any(symbolNames::contains)
        val arityMatches = arity == null || symbol.arity == null || arity == symbol.arity
        return nameMatches && arityMatches
    }

    private fun symbolRecords(): List<SymbolRecord> =
        sequenceOf("sym:", "res:")
            .flatMap(store::prefixScan)
            .map { it.second }
            .filterIsInstance<SymbolRecord>()
            .toList()

    private fun SymbolRecord.definitionId(): SymbolId =
        SymbolId.of(
            "indexino:symbol:v1:" +
                sha256(
                    listOf(
                            generation.value,
                            fqn,
                            relativeFile,
                            line.toString(),
                            signature.orEmpty(),
                            kind,
                        )
                        .joinToString("\u0000")
                )
        )

    private fun externalId(fqn: String): SymbolId =
        SymbolId.of("indexino:external:v1:${sha256(fqn)}")

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    private fun sourceLocation(path: String, line: Int, column: Int?): SourceLocation {
        val file = SourceFile.of(WORKSPACE_ORIGIN, path, path)
        return SourceLocation.of(file, line, column, null)
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
                freshnessAtAcquisition = SnapshotFreshness.CURRENT,
                onClose = onClose,
            )
    }
}
