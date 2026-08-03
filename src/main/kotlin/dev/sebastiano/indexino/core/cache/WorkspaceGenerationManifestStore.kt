package dev.sebastiano.indexino.core.cache

import dev.sebastiano.indexino.core.manifest.IndexManifest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class WorkspaceGenerationOrigin(
    val originId: String,
    val revision: String?,
    val stateFingerprint: String,
    val expectedRevision: String? = null,
    val dirty: Boolean = false,
    val available: Boolean = true,
)

@Serializable
internal data class WorkspaceGenerationManifest(
    val basicFactSchemaVersion: Int = 1,
    val generation: String,
    val workspaceRevisionFingerprint: String,
    val originId: String,
    val revision: String?,
    val stateFingerprint: String,
    val packKeys: List<String>,
    val scopeBuildSystem: String = "",
    val scopeValue: String = "",
    val includesDependencies: Boolean = false,
    val applications: List<String> = emptyList(),
    val origins: List<WorkspaceGenerationOrigin> =
        listOf(WorkspaceGenerationOrigin(originId, revision, stateFingerprint)),
    val compatibilityManifest: IndexManifest? = null,
)

/** Publishes immutable workspace generation manifests through a short current pointer. */
internal class WorkspaceGenerationManifestStore(cacheRoot: Path, workspaceId: String) {
    private val workspaceRoot = cacheRoot.resolve("workspaces").resolve(workspaceId)
    private val currentPointer = workspaceRoot.resolve("current")
    private val unavailableOriginsPath = workspaceRoot.resolve("unavailable-origins.json")
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun markOriginsUnavailable(originIds: Set<String>) {
        if (originIds.isEmpty()) return
        Files.createDirectories(workspaceRoot)
        Files.writeString(unavailableOriginsPath, json.encodeToString(originIds.sorted()))
    }

    fun publish(manifest: WorkspaceGenerationManifest) {
        Files.deleteIfExists(unavailableOriginsPath)
        val manifestPath =
            workspaceRoot
                .resolve("generations")
                .resolve(manifest.generation)
                .resolve("manifest.json")
        Files.createDirectories(manifestPath.parent)
        val stagingManifest = manifestPath.resolveSibling("manifest.tmp-${UUID.randomUUID()}")
        Files.writeString(
            stagingManifest,
            json.encodeToString(WorkspaceGenerationManifest.serializer(), manifest),
        )
        moveAtomically(stagingManifest, manifestPath)

        val stagingPointer = currentPointer.resolveSibling("current.tmp-${UUID.randomUUID()}")
        Files.writeString(stagingPointer, manifest.generation)
        moveAtomically(stagingPointer, currentPointer)
    }

    fun current(): WorkspaceGenerationManifest? {
        if (!Files.isRegularFile(currentPointer)) return null
        val generation = Files.readString(currentPointer).trim()
        if (generation.isBlank()) return null
        val manifestPath =
            workspaceRoot.resolve("generations").resolve(generation).resolve("manifest.json")
        if (!Files.isRegularFile(manifestPath)) return null
        val manifest =
            json.decodeFromString(
                WorkspaceGenerationManifest.serializer(),
                Files.readString(manifestPath),
            )
        val unavailable =
            unavailableOriginsPath
                .takeIf(Files::isRegularFile)
                ?.let { json.decodeFromString<Set<String>>(Files.readString(it)) }
                .orEmpty()
        if (unavailable.isEmpty()) return manifest
        return manifest.copy(
            origins =
                manifest.origins.map { origin ->
                    if (origin.originId in unavailable) origin.copy(available = false) else origin
                },
            compatibilityManifest =
                manifest.compatibilityManifest?.copy(
                    origins =
                        manifest.compatibilityManifest.origins.map { origin ->
                            if (origin.originId in unavailable) origin.copy(available = false)
                            else origin
                        }
                ),
        )
    }

    private fun moveAtomically(source: Path, destination: Path) {
        try {
            Files.move(
                source,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
