package dev.sebastiano.indexino.core.manifest

import dev.sebastiano.indexino.core.git.GitHeadResolver
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.Serializable

@Serializable
internal data class IndexManifestOrigin(
    val originId: String,
    val revision: String?,
    val stateFingerprint: String,
    val expectedRevision: String? = null,
    val dirty: Boolean = false,
    val available: Boolean = true,
)

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
    val pluginCoordinates: Map<String, String> = emptyMap(),
    val origins: List<IndexManifestOrigin> = emptyList(),
    val resolvedTopologyDigest: String? = null,
)

internal fun IndexManifest.workspaceRevisionFingerprint(): String {
    val graph =
        origins
            .ifEmpty {
                listOf(
                    IndexManifestOrigin(
                        originId = "workspace",
                        revision = commit.takeUnless(GitHeadResolver::isFilesystemRevision),
                        stateFingerprint = sourcesContentHash,
                    )
                )
            }
            .sortedBy { it.originId }
            .joinToString("\u0001") { origin ->
                listOf(
                        origin.originId,
                        origin.revision.orEmpty(),
                        origin.stateFingerprint,
                        origin.expectedRevision.orEmpty(),
                    )
                    .joinToString("\u0002")
            }
    val input =
        listOf(
                commit,
                scope,
                topology,
                includeDeps.toString(),
                sourcesContentHash,
                resolvedTopologyDigest.orEmpty(),
                graph,
            )
            .joinToString("\u0000")
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(input.toByteArray()))
}

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
