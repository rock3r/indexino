package dev.sebastiano.indexino.core.cache

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class WorkspaceGenerationManifest(
    val generation: String,
    val originId: String,
    val revision: String?,
    val stateFingerprint: String,
    val packKeys: List<String>,
)

/** Publishes immutable workspace generation manifests through a short current pointer. */
internal class WorkspaceGenerationManifestStore(cacheRoot: Path, workspaceId: String) {
    private val workspaceRoot = cacheRoot.resolve("workspaces").resolve(workspaceId)
    private val currentPointer = workspaceRoot.resolve("current")
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun publish(manifest: WorkspaceGenerationManifest) {
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
        return json.decodeFromString(
            WorkspaceGenerationManifest.serializer(),
            Files.readString(manifestPath),
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
