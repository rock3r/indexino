package dev.sebastiano.indexino.core.manifest

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.Serializable

@Serializable
internal data class IndexManifest(
    val commit: String,
    val indexerVersion: String,
    val scope: String,
    val topology: String,
    val includeDeps: Boolean = true,
    val sourceFileCount: Int,
    val sourcesContentHash: String,
    val builtAt: String,
    val applications: List<String> = emptyList(),
)

internal object ManifestIO {
    private val json =
        kotlinx.serialization.json.Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    fun read(path: Path): IndexManifest =
        json.decodeFromString(IndexManifest.serializer(), path.readText())

    fun write(path: Path, manifest: IndexManifest) {
        path.parent?.toFile()?.mkdirs()
        path.writeText(json.encodeToString(IndexManifest.serializer(), manifest))
    }
}
