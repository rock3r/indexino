package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.Reference
import dev.sebastiano.indexino.model.SourceFile
import dev.sebastiano.indexino.model.SourceLocation
import dev.sebastiano.indexino.model.SourceOriginId
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
    fun SymbolRecord.toPublicSymbol(symbolsByName: Map<String, List<SymbolRecord>>): Symbol {
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
                    val owners = symbolsByName[owner].orEmpty()
                    owners
                        .firstOrNull { it.fqn == owner && it.relativeFile == relativeFile }
                        ?.definitionId()
                        ?: owners.firstOrNull { it.relativeFile == relativeFile }?.definitionId()
                        ?: owners.firstOrNull { it.fqn == owner }?.definitionId()
                        ?: owners.firstOrNull()?.definitionId()
                        ?: externalId(owner)
                },
            signature = signature,
            arity = arity,
            aliases = aliases,
        )
    }

    @OptIn(IndexinoInternalApi::class)
    fun ReferenceRecord.toPublicReference(
        symbolsByName: Map<String, List<SymbolRecord>>
    ): Reference {
        val candidates = candidateSymbols(symbolsByName)
        val directMatches = candidates.filter { it.fqn == symbolFqn || symbolFqn in it.aliases }
        val direct =
            when (directMatches.size) {
                0 -> externalId(symbolFqn)
                1 -> directMatches.single().definitionId()
                else -> ambiguousId(symbolFqn)
            }
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

    fun ReferenceRecord.matchesSymbolId(
        symbolId: SymbolId,
        symbolsByName: Map<String, List<SymbolRecord>>,
    ): Boolean {
        // Match the IDs materialization would actually emit so external directs still round-trip
        // when other candidate names resolve locally (S9 may later rank multi-origin duplicates).
        val materialized = toPublicReference(symbolsByName)
        return materialized.symbolId == symbolId || symbolId in materialized.candidateSymbolIds
    }

    fun ReferenceRecord.canTarget(symbol: SymbolRecord): Boolean {
        val targetNames = candidateSymbolFqns + symbolFqn
        val symbolNames = symbol.aliases + symbol.fqn
        val nameMatches = targetNames.any(symbolNames::contains)
        return nameMatches && arityCompatibleWith(symbol)
    }

    private fun ReferenceRecord.candidateSymbols(
        symbolsByName: Map<String, List<SymbolRecord>>
    ): List<SymbolRecord> {
        val seen = LinkedHashSet<SymbolRecord>()
        for (name in candidateSymbolFqns + symbolFqn) {
            val matches = symbolsByName[name] ?: continue
            for (symbol in matches) {
                if (arityCompatibleWith(symbol)) {
                    seen += symbol
                }
            }
        }
        return seen.toList()
    }

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

    private fun sourceLocation(path: String, line: Int, column: Int?): SourceLocation {
        val file = SourceFile.of(WORKSPACE_ORIGIN, path, path)
        return SourceLocation.of(file, line, column, null)
    }

    private companion object {
        private val WORKSPACE_ORIGIN: SourceOriginId = SourceOriginId.of("workspace")
    }
}
