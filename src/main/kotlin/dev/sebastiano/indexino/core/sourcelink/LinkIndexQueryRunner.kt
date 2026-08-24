package dev.sebastiano.indexino.core.sourcelink

import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifestStore
import dev.sebastiano.indexino.core.cache.WorkspaceRegistryStore
import dev.sebastiano.indexino.core.cache.WorktreeOverlayStoreOpener
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.model.ArtifactDigest
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.LinkedSourceResult
import dev.sebastiano.indexino.model.ResolvedComponentCoordinate
import dev.sebastiano.indexino.model.ResolvedComponentIdentity
import dev.sebastiano.indexino.model.SourceLinkDiagnostic
import dev.sebastiano.indexino.model.SourceLinkEvidence
import dev.sebastiano.indexino.model.SourceLocation
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.SourceOriginRevision
import java.nio.file.Path

internal object LinkIndexQueryRunner {
    fun linkedResultsForRegistration(
        cacheRoot: Path,
        registration: SerializedSourceLinkRegistration,
        linkedWorkspace: Path,
        symbolName: String,
    ): List<LinkedSourceResult> {
        val matches = mutableListOf<LinkedSourceResult>()
        val store = openLinkedStore(cacheRoot, linkedWorkspace, registration.linkedGeneration)
        try {
            store.forEachPrefix("sym:") { _, record ->
                val symbol = record as? SymbolRecord
                if (symbol != null && symbolMatches(symbol, symbolName)) {
                    matches += linkedResultForSymbol(registration, symbol)
                }
                true
            }
        } finally {
            store.close()
        }
        return matches
    }

    fun workspaceForGeneration(cacheRoot: Path, generation: String): Path? =
        WorkspaceRegistryStore(cacheRoot)
            .entries()
            .firstOrNull { entry ->
                WorkspaceGenerationManifestStore(cacheRoot, entry.workspaceId)
                    .readGeneration(generation) != null
            }
            ?.path
            ?.let(Path::of)

    private fun symbolMatches(record: SymbolRecord, symbolName: String): Boolean =
        record.fqn.contains(symbolName) || record.name == symbolName

    @OptIn(IndexinoInternalApi::class)
    private fun linkedResultForSymbol(
        registration: SerializedSourceLinkRegistration,
        record: SymbolRecord,
    ): LinkedSourceResult {
        val evidence = sourceLinkEvidenceFromValue(registration.evidence)
        val location =
            SourceLocation.of(
                dev.sebastiano.indexino.model.SourceFile.of(
                    SourceOriginId.of(record.originId),
                    record.relativeFile,
                    record.relativeFile,
                ),
                record.line,
                record.column,
                null,
            )
        return LinkedSourceResult.of(
            component =
                ResolvedComponentIdentity.of(
                    coordinate = ResolvedComponentCoordinate.of(registration.coordinate),
                    artifactDigest = ArtifactDigest.of(registration.artifactDigest),
                    variant = registration.variant,
                    substitution = registration.substitution,
                ),
            sourceRevision =
                SourceOriginRevision(
                    originId = SourceOriginId.of(registration.sourceOriginId),
                    revision = registration.revision,
                    stateFingerprint = registration.linkedGeneration,
                    expectedRevision = registration.tag,
                ),
            evidence = evidence,
            diagnostics =
                registration.diagnostics.map { SourceLinkDiagnostic.of(it.code, it.message) },
            location = location,
            symbolName = record.fqn,
        )
    }

    private fun openLinkedStore(
        cacheRoot: Path,
        workspace: Path,
        generation: String,
    ): CodeIndexStore {
        val workspaceId = InProcessCacheLayout.workspaceId(workspace)
        val manifest =
            WorkspaceGenerationManifestStore(cacheRoot, workspaceId).readGeneration(generation)
                ?: error("Missing linked generation $generation")
        return WorktreeOverlayStoreOpener.openForQuery(
            cacheRoot,
            workspace,
            clientId = "link-index",
            manifest,
        )
    }
}

internal fun sourceLinkEvidenceFromValue(value: String): SourceLinkEvidence =
    when (value) {
        SourceLinkEvidence.VERIFIED.value -> SourceLinkEvidence.VERIFIED
        SourceLinkEvidence.RECONSTRUCTED.value -> SourceLinkEvidence.RECONSTRUCTED
        SourceLinkEvidence.DECLARED.value -> SourceLinkEvidence.DECLARED
        else -> SourceLinkEvidence.MISMATCH
    }
