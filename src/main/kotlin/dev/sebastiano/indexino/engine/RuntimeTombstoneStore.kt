package dev.sebastiano.indexino.engine

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal class RuntimeTombstone(
    val code: String,
    val message: String,
    val workspace: String,
    val occurredAtMillis: Long,
)

internal object RuntimeTombstoneStore {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun write(path: Path, workspace: Path) {
        val tombstone =
            RuntimeTombstone(
                code = "WORKSPACE_LOST",
                message =
                    "Indexino shut down because the bound workspace disappeared or was replaced. " +
                        "Before deleting a workspace, run indexino daemon stop --project <path>.",
                workspace = workspace.toString(),
                occurredAtMillis = System.currentTimeMillis(),
            )
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
        try {
            Files.writeString(
                temporary,
                json.encodeToString(RuntimeTombstone.serializer(), tombstone),
            )
            Files.move(
                temporary,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun read(path: Path): RuntimeTombstone? =
        if (Files.isRegularFile(path)) {
            json.decodeFromString(RuntimeTombstone.serializer(), Files.readString(path))
        } else {
            null
        }
}
