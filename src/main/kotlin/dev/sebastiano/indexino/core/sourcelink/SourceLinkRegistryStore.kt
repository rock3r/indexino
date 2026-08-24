package dev.sebastiano.indexino.core.sourcelink

import dev.sebastiano.indexino.model.DependencyToGenerationEdge
import dev.sebastiano.indexino.model.LinkGenerationId
import dev.sebastiano.indexino.model.ResolvedComponentIdentity
import dev.sebastiano.indexino.model.SourceLinkRegistration
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class SourceLinkRegistrySnapshot(
    val linkGeneration: String,
    val registrations: List<SerializedSourceLinkRegistration> = emptyList(),
    val edges: List<SerializedDependencyToGenerationEdge> = emptyList(),
)

@Serializable
internal data class SerializedSourceLinkRegistration(
    val coordinate: String,
    val artifactDigest: String,
    val variant: String? = null,
    val substitution: String? = null,
    val repositoryIdentity: String,
    val checkoutPath: String,
    val revision: String? = null,
    val tag: String? = null,
    val dirty: Boolean = false,
    val submoduleRevisions: Map<String, String> = emptyMap(),
    val sourceRoots: List<String>,
    val sourceOriginId: String,
    val linkedGeneration: String,
    val mappingBinaryPrefix: String,
    val mappingSourceRoot: String,
    val evidence: String,
    val diagnostics: List<SerializedSourceLinkDiagnostic> = emptyList(),
)

@Serializable
internal data class SerializedSourceLinkDiagnostic(val code: String, val message: String)

@Serializable
internal data class SerializedDependencyToGenerationEdge(
    val coordinate: String,
    val artifactDigest: String,
    val variant: String? = null,
    val substitution: String? = null,
    val linkedGeneration: String,
    val linkGeneration: String,
    val evidence: String,
)

/** Persists source-link registrations without copying linked repository sources. */
internal class SourceLinkRegistryStore(private val workspaceRoot: Path) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun registryPath(): Path = workspaceRoot.resolve("source-links").resolve("registry.json")

    fun generationPath(linkGeneration: LinkGenerationId): Path =
        workspaceRoot
            .resolve("source-links")
            .resolve("generations")
            .resolve(linkGeneration.value)
            .resolve("snapshot.json")

    fun readCurrent(): SourceLinkRegistrySnapshot? {
        val path = registryPath()
        if (!Files.isRegularFile(path)) return null
        return json.decodeFromString(
            SourceLinkRegistrySnapshot.serializer(),
            Files.readString(path),
        )
    }

    fun publish(
        linkGeneration: LinkGenerationId,
        registrations: List<SourceLinkRegistration>,
        edges: List<DependencyToGenerationEdge>,
    ) {
        val snapshot =
            SourceLinkRegistrySnapshot(
                linkGeneration = linkGeneration.value,
                registrations = registrations.map(::serializeRegistration),
                edges = edges.map(::serializeEdge),
            )
        val generationPath = generationPath(linkGeneration)
        Files.createDirectories(generationPath.parent)
        writeAtomically(generationPath, json.encodeToString(snapshot))
        writeAtomically(registryPath(), json.encodeToString(snapshot))
    }

    private fun writeAtomically(path: Path, content: String) {
        val staging = path.resolveSibling("${path.fileName}.tmp-${System.nanoTime()}")
        Files.writeString(staging, content)
        try {
            Files.move(
                staging,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(staging, path, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(staging)
        }
    }

    companion object {
        fun serializeRegistration(
            registration: SourceLinkRegistration
        ): SerializedSourceLinkRegistration =
            SerializedSourceLinkRegistration(
                coordinate = registration.component.coordinate.value,
                artifactDigest = registration.component.artifactDigest.value,
                variant = registration.component.variant,
                substitution = registration.component.substitution,
                repositoryIdentity = registration.checkout.repositoryIdentity,
                checkoutPath = registration.checkout.checkoutPath,
                revision = registration.checkout.revision,
                tag = registration.checkout.tag,
                dirty = registration.checkout.dirty,
                submoduleRevisions = registration.checkout.submoduleRevisions,
                sourceRoots = registration.checkout.sourceRoots,
                sourceOriginId = registration.sourceOriginId.value,
                linkedGeneration = registration.linkedGeneration.value,
                mappingBinaryPrefix = registration.mappingRule.binaryPrefix,
                mappingSourceRoot = registration.mappingRule.sourceRoot,
                evidence = registration.evidence.value,
                diagnostics =
                    registration.diagnostics.map {
                        SerializedSourceLinkDiagnostic(it.code, it.message)
                    },
            )

        fun serializeEdge(edge: DependencyToGenerationEdge): SerializedDependencyToGenerationEdge =
            SerializedDependencyToGenerationEdge(
                coordinate = edge.component.coordinate.value,
                artifactDigest = edge.component.artifactDigest.value,
                variant = edge.component.variant,
                substitution = edge.component.substitution,
                linkedGeneration = edge.linkedGeneration.value,
                linkGeneration = edge.linkGeneration.value,
                evidence = edge.evidence.value,
            )

        fun componentKey(component: ResolvedComponentIdentity): String =
            listOf(
                    component.coordinate.value,
                    component.artifactDigest.value,
                    component.variant.orEmpty(),
                    component.substitution.orEmpty(),
                )
                .joinToString("\u0000")
    }
}
