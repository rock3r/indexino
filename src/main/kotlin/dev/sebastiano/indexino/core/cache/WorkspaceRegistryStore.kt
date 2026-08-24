package dev.sebastiano.indexino.core.cache

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class WorkspaceRegistryEntry(
    val workspaceId: String,
    val path: String,
    val gitCommonDir: String? = null,
    val lastUsedEpochMillis: Long = Instant.now().toEpochMilli(),
)

@Serializable
internal data class WorkspaceRegistry(
    val workspaces: Map<String, WorkspaceRegistryEntry> = emptyMap()
)

/** Tracks workspace paths and git identity for sibling worktree fork discovery. */
internal class WorkspaceRegistryStore(private val cacheRoot: Path) {
    private val registryPath = cacheRoot.resolve("registry").resolve("workspaces.json")
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun upsert(workspaceId: String, workspacePath: Path, gitCommonDir: String?) {
        Files.createDirectories(registryPath.parent)
        val canonicalPath =
            runCatching { workspacePath.toRealPath().toString() }
                .getOrElse { workspacePath.toAbsolutePath().normalize().toString() }
        val current = read()
        val updated =
            current.copy(
                workspaces =
                    current.workspaces +
                        (workspaceId to
                            WorkspaceRegistryEntry(
                                workspaceId = workspaceId,
                                path = canonicalPath,
                                gitCommonDir = gitCommonDir,
                            ))
            )
        write(updated)
    }

    fun remove(workspaceId: String) {
        val current = read()
        if (workspaceId !in current.workspaces) return
        write(current.copy(workspaces = current.workspaces - workspaceId))
    }

    fun entries(): Collection<WorkspaceRegistryEntry> = read().workspaces.values

    fun entry(workspaceId: String): WorkspaceRegistryEntry? = read().workspaces[workspaceId]

    private fun read(): WorkspaceRegistry {
        if (!Files.isRegularFile(registryPath)) return WorkspaceRegistry()
        return json.decodeFromString(WorkspaceRegistry.serializer(), Files.readString(registryPath))
    }

    private fun write(registry: WorkspaceRegistry) {
        Files.createDirectories(registryPath.parent)
        val staging = registryPath.resolveSibling("workspaces.tmp-${System.nanoTime()}")
        Files.writeString(staging, json.encodeToString(registry))
        try {
            Files.move(
                staging,
                registryPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(staging, registryPath, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(staging)
        }
    }
}
