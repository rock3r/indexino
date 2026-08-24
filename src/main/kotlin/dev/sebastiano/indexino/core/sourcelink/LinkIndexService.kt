package dev.sebastiano.indexino.core.sourcelink

import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifestStore
import dev.sebastiano.indexino.model.ArtifactDigest
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.LinkGenerationId
import dev.sebastiano.indexino.model.LinkedSourceQuery
import dev.sebastiano.indexino.model.LinkedSourceResult
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.QueryPage
import dev.sebastiano.indexino.model.ResolvedComponentCoordinate
import dev.sebastiano.indexino.model.ResolvedComponentIdentity
import dev.sebastiano.indexino.model.SourceLinkDiagnostic
import dev.sebastiano.indexino.model.SourceLinkEvidence
import dev.sebastiano.indexino.model.SourceLinkMappingRule
import dev.sebastiano.indexino.model.SourceLinkRegistration
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/** Resolves linked source registrations and cross-repository query results. */
internal class LinkIndexService(private val cacheRoot: Path, private val consumerWorkspace: Path) {
    fun configPath(): Path = consumerWorkspace.resolve(".indexino").resolve("source-links.toml")

    fun loadConfigEntries(): List<SourceLinkConfigEntry> =
        SourceLinkConfigParser.parseFile(configPath())

    fun resolveRegistrations(
        entries: List<SourceLinkConfigEntry>,
        linkedGenerations: Map<ResolvedComponentCoordinate, LinkedWorkspaceGeneration>,
    ): List<SourceLinkRegistration> = entries.mapNotNull { entry ->
        registrationForEntry(entry, linkedGenerations[entry.component])
    }

    private fun registrationForEntry(
        entry: SourceLinkConfigEntry,
        linked: LinkedWorkspaceGeneration?,
    ): SourceLinkRegistration? {
        if (linked == null) return null
        val checkoutRoot = consumerWorkspace.resolve(entry.checkout).normalize()
        if (!Files.isDirectory(checkoutRoot)) {
            return registrationForMissingCheckout(entry, linked)
        }
        val dirty = isDirty(checkoutRoot)
        val revision = gitHeadOrNull(checkoutRoot)
        val checkout =
            entry.toCheckout(checkoutRoot = checkoutRoot, dirty = dirty, revision = revision)
        val component =
            ResolvedComponentIdentity.of(
                coordinate = entry.component,
                artifactDigest = entry.binarySha256,
                variant = entry.variant,
                substitution = entry.substitution,
            )
        val classification =
            SourceLinkEvidenceClassifier.classify(
                component = component,
                checkout = checkout,
                checkoutRoot = checkoutRoot,
                publishedSourceCompanionDigest = linked.publishedSourceCompanionDigest,
                declaredOnly = entry.declaredOnly,
            )
        val mapping =
            SourceLinkMappingRule.packagePrefix(
                binaryPrefix = entry.sourceRoots.first().substringBeforeLast('/').ifBlank { "com" },
                sourceRoot = entry.sourceRoots.first(),
            )
        return SourceLinkRegistration.of(
            component = component,
            checkout = checkout,
            sourceOriginId = SourceOriginId.of(linked.originId),
            linkedGeneration = linked.generation,
            mappingRule = mapping,
            evidence = classification.evidence,
            diagnostics = classification.diagnostics,
        )
    }

    fun resolveLinkedGenerations(
        entries: List<SourceLinkConfigEntry>
    ): Map<ResolvedComponentCoordinate, LinkedWorkspaceGeneration> =
        entries
            .mapNotNull { entry -> linkedGenerationForEntry(entry)?.let { entry.component to it } }
            .toMap()

    private fun linkedGenerationForEntry(entry: SourceLinkConfigEntry): LinkedWorkspaceGeneration? {
        val providerRoot = consumerWorkspace.resolve(entry.linkedWorkspace).normalize()
        if (!Files.isDirectory(providerRoot)) return null
        val workspaceId = InProcessCacheLayout.workspaceId(providerRoot)
        val manifest =
            WorkspaceGenerationManifestStore(cacheRoot, workspaceId).current() ?: return null
        return LinkedWorkspaceGeneration(
            generation = WorkspaceGenerationId.of(manifest.generation),
            originId = manifest.originId,
            publishedSourceCompanionDigest = entry.publishedSourceCompanionDigest,
        )
    }

    fun publishFromConfig(): LinkGenerationId? {
        val entries = loadConfigEntries()
        if (entries.isEmpty()) return null
        val linkedGenerations = resolveLinkedGenerations(entries)
        val registrations = resolveRegistrations(entries, linkedGenerations)
        if (registrations.isEmpty()) return null
        return publish(registrations).first
    }

    fun publish(
        registrations: List<SourceLinkRegistration>
    ): Pair<LinkGenerationId, SourceLinkRegistrySnapshot> {
        val (linkGeneration, edges) = LinkGenerationComputer.compute(registrations)
        val store =
            SourceLinkRegistryStore(
                cacheRoot
                    .resolve("workspaces")
                    .resolve(InProcessCacheLayout.workspaceId(consumerWorkspace))
            )
        store.publish(linkGeneration, registrations, edges)
        return linkGeneration to
            SourceLinkRegistrySnapshot(
                linkGeneration = linkGeneration.value,
                registrations = registrations.map(SourceLinkRegistryStore::serializeRegistration),
                edges = edges.map(SourceLinkRegistryStore::serializeEdge),
            )
    }

    fun readCurrentLinkGeneration(): LinkGenerationId? =
        SourceLinkRegistryStore(
                cacheRoot
                    .resolve("workspaces")
                    .resolve(InProcessCacheLayout.workspaceId(consumerWorkspace))
            )
            .readCurrent()
            ?.linkGeneration
            ?.let(LinkGenerationId::of)

    @OptIn(IndexinoInternalApi::class)
    fun findLinkedSources(
        snapshot: SourceLinkRegistrySnapshot,
        query: LinkedSourceQuery,
        options: QueryOptions,
    ): QueryPage<LinkedSourceResult> {
        val registrations =
            snapshot.registrations.filter { registration ->
                query.componentCoordinate?.value?.let { it == registration.coordinate } ?: true
            }
        val results = mutableListOf<LinkedSourceResult>()
        for (registration in registrations) {
            if (!registrationAllowsQuery(registration)) {
                continue
            }
            val linkedWorkspace =
                LinkIndexQueryRunner.workspaceForGeneration(
                    cacheRoot,
                    registration.linkedGeneration,
                )
            if (linkedWorkspace != null) {
                results +=
                    LinkIndexQueryRunner.linkedResultsForRegistration(
                        cacheRoot,
                        registration,
                        linkedWorkspace,
                        query.symbolName,
                    )
            }
        }
        val limit = options.limit
        val start = options.offset.coerceAtMost(results.size)
        val end = (start + limit).coerceAtMost(results.size)
        return QueryPage(
            items = results.subList(start, end),
            offset = options.offset,
            limit = limit,
            hasMore = end < results.size,
            nextCursor = null,
            totalCount = results.size,
        )
    }

    private fun registrationAllowsQuery(registration: SerializedSourceLinkRegistration): Boolean {
        val evidence = sourceLinkEvidenceFromValue(registration.evidence)
        return evidence.allowsExactCrossRepositorySemantics() ||
            evidence.allowsQualifiedIndexedQueries() ||
            evidence.isNavigationHintOnly()
    }

    private fun registrationForMissingCheckout(
        entry: SourceLinkConfigEntry,
        linked: LinkedWorkspaceGeneration,
    ): SourceLinkRegistration {
        val component =
            ResolvedComponentIdentity.of(
                coordinate = entry.component,
                artifactDigest = entry.binarySha256,
                variant = entry.variant,
                substitution = entry.substitution,
            )
        val checkout =
            entry.toCheckout(
                checkoutRoot = consumerWorkspace.resolve(entry.checkout),
                dirty = false,
                revision = null,
            )
        return SourceLinkRegistration.of(
            component = component,
            checkout = checkout,
            sourceOriginId = SourceOriginId.of(linked.originId),
            linkedGeneration = linked.generation,
            mappingRule =
                SourceLinkMappingRule.packagePrefix(
                    entry.sourceRoots.first(),
                    entry.sourceRoots.first(),
                ),
            evidence = SourceLinkEvidence.MISMATCH,
            diagnostics =
                listOf(
                    SourceLinkDiagnostic.of(
                        "source.missing",
                        "Linked checkout ${entry.checkout} is unavailable",
                    )
                ),
        )
    }

    private fun isDirty(checkoutRoot: Path): Boolean {
        val process =
            ProcessBuilder("git", "-C", checkoutRoot.toString(), "status", "--porcelain")
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output.isNotBlank()
    }

    private fun gitHeadOrNull(checkoutRoot: Path): String? {
        if (
            !checkoutRoot.resolve(".git").isRegularFile() &&
                !Files.isDirectory(checkoutRoot.resolve(".git"))
        ) {
            return null
        }
        return runCatching {
                ProcessBuilder("git", "-C", checkoutRoot.toString(), "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start()
                    .inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
    }
}

internal data class LinkedWorkspaceGeneration(
    val generation: WorkspaceGenerationId,
    val originId: String,
    val publishedSourceCompanionDigest: ArtifactDigest? = null,
)
