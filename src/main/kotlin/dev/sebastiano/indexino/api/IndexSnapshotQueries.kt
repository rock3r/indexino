package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.core.record.CallArgumentRecord
import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.model.ArgumentKind
import dev.sebastiano.indexino.model.CallArgument
import dev.sebastiano.indexino.model.CallSite
import dev.sebastiano.indexino.model.CallSiteId
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.Reference
import dev.sebastiano.indexino.model.ResolutionConfidence
import dev.sebastiano.indexino.model.SourceFile
import dev.sebastiano.indexino.model.SourceLocation
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.SourceRange
import dev.sebastiano.indexino.model.Symbol
import dev.sebastiano.indexino.model.SymbolId
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import java.security.MessageDigest
import java.util.HexFormat

internal class IndexSnapshotQueries(private val generation: WorkspaceGenerationId) {
    fun indexSymbolsByName(symbols: List<SymbolRecord>): Map<String, List<SymbolRecord>> {
        val indexed = LinkedHashMap<String, MutableList<SymbolRecord>>()
        for (symbol in symbols) {
            indexed.getOrPut(symbol.fqn) { mutableListOf() }.add(symbol)
            for (alias in symbol.aliases) {
                indexed.getOrPut(alias) { mutableListOf() }.add(symbol)
            }
        }
        return indexed
    }

    fun SymbolRecord.definitionId(): SymbolId =
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

    @OptIn(IndexinoInternalApi::class)
    fun SymbolRecord.toPublicSymbol(ownerId: SymbolId?): Symbol {
        val location = sourceLocation(relativeFile, line, null)
        return Symbol(
            id = definitionId(),
            name = name,
            kind = kind,
            language = language,
            location = location,
            range = null,
            ownerId = ownerId,
            signature = signature,
            arity = arity,
            aliases = aliases,
        )
    }

    fun CallSiteRecord.callSiteId(): CallSiteId =
        CallSiteId.of(
            "indexino:call:v1:" + sha256(listOf(generation.value, identity).joinToString("\u0000"))
        )

    @OptIn(IndexinoInternalApi::class)
    fun CallSiteRecord.toPublicCallSite(): CallSite =
        CallSite(
            id = callSiteId(),
            calleeName = calleeName,
            candidateSymbolIds = candidateSymbolFqns.map(::externalSymbolId),
            receiver = receiver,
            enclosingSymbolId = enclosingSymbolFqn?.let(::externalSymbolId),
            parentCallId = parentCallIdentity?.let { identity -> callSiteId(identity) },
            range =
                sourceRange(
                    relativeFile,
                    startLine,
                    startColumn,
                    startOffset,
                    endLine,
                    endColumn,
                    endOffset,
                ),
            arguments = arguments.map { it.toPublicCallArgument(relativeFile) },
            confidence = confidence(),
        )

    @OptIn(IndexinoInternalApi::class)
    private fun CallArgumentRecord.toPublicCallArgument(relativeFile: String): CallArgument =
        CallArgument(
            position = position,
            resolvedName = resolvedName,
            kind =
                when (kind) {
                    "LAMBDA" -> ArgumentKind.LAMBDA
                    "TRAILING_LAMBDA" -> ArgumentKind.TRAILING_LAMBDA
                    else -> ArgumentKind.VALUE
                },
            range =
                sourceRange(
                    relativeFile,
                    startLine,
                    startColumn,
                    startOffset,
                    endLine,
                    endColumn,
                    endOffset,
                ),
            nestedCallIds = nestedCallIdentities.map(::callSiteId),
        )

    private fun CallSiteRecord.confidence(): ResolutionConfidence =
        when (confidence) {
            "RESOLVED" -> ResolutionConfidence.RESOLVED
            "HEURISTIC" -> ResolutionConfidence.HEURISTIC
            else -> ResolutionConfidence.UNRESOLVED
        }

    private fun callSiteId(identity: String): CallSiteId =
        CallSiteId.of(
            "indexino:call:v1:" + sha256(listOf(generation.value, identity).joinToString("\u0000"))
        )

    fun externalSymbolId(fqn: String): SymbolId = externalId(fqn)

    fun ambiguousSymbolId(fqn: String): SymbolId = ambiguousId(fqn)

    @OptIn(IndexinoInternalApi::class)
    fun ReferenceRecord.toPublicReference(candidates: List<SymbolRecord>): Reference {
        val directMatches = candidates.filter { it.fqn == symbolFqn || symbolFqn in it.aliases }
        val direct =
            when (directMatches.size) {
                0 -> externalId(symbolFqn)
                1 -> directMatches.single().definitionId()
                else -> ambiguousId(symbolFqn)
            }
        val candidateIds =
            buildList {
                    addAll(candidates.map { it.definitionId() })
                    for (candidateName in candidateSymbolFqns) {
                        val resolvesLocally = candidates.any {
                            it.fqn == candidateName || candidateName in it.aliases
                        }
                        if (!resolvesLocally) add(externalId(candidateName))
                    }
                }
                .distinct()
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

    fun ReferenceRecord.matchesSymbolId(
        symbolId: SymbolId,
        candidates: List<SymbolRecord>,
    ): Boolean {
        // Match the IDs materialization would actually emit so external directs still round-trip
        // when other candidate names resolve locally (S9 may later rank multi-origin duplicates).
        val materialized = toPublicReference(candidates)
        return materialized.symbolId == symbolId || symbolId in materialized.candidateSymbolIds
    }

    fun ReferenceRecord.canTarget(symbol: SymbolRecord): Boolean {
        val targetNames = candidateSymbolFqns + symbolFqn
        val symbolNames = symbol.aliases + symbol.fqn
        val nameMatches = targetNames.any(symbolNames::contains)
        return nameMatches && arityCompatibleWith(symbol)
    }

    fun ReferenceRecord.isArityCompatibleWith(symbol: SymbolRecord): Boolean =
        arityCompatibleWith(symbol)

    private fun ReferenceRecord.arityCompatibleWith(symbol: SymbolRecord): Boolean =
        when {
            // Name-only / member refs stay over-inclusive so property vs getter uncertainty remains
            // an explicit candidate set rather than a false exact resolution.
            arity == null -> true
            // Arity-bearing call sites cannot target properties or other non-callables.
            symbol.arity == null -> false
            // Exact equality only: default/vararg parameters are not modeled yet (record for S2+).
            else -> arity == symbol.arity
        }

    private fun externalId(fqn: String): SymbolId =
        SymbolId.of(
            "indexino:external:v1:" + sha256(listOf(generation.value, fqn).joinToString("\u0000"))
        )

    private fun ambiguousId(fqn: String): SymbolId =
        SymbolId.of(
            "indexino:ambiguous:v1:" + sha256(listOf(generation.value, fqn).joinToString("\u0000"))
        )

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    private fun sourceRange(
        path: String,
        startLine: Int,
        startColumn: Int,
        startOffset: Int,
        endLine: Int,
        endColumn: Int,
        endOffset: Int,
    ): SourceRange {
        val file = SourceFile.of(WORKSPACE_ORIGIN, path, path)
        return SourceRange.of(
            SourceLocation.of(file, startLine, startColumn, startOffset),
            SourceLocation.of(file, endLine, endColumn, endOffset),
        )
    }

    private fun sourceLocation(path: String, line: Int, column: Int?): SourceLocation {
        val file = SourceFile.of(WORKSPACE_ORIGIN, path, path)
        return SourceLocation.of(file, line, column, null)
    }

    private companion object {
        private val WORKSPACE_ORIGIN: SourceOriginId = SourceOriginId.of("workspace")
    }
}
