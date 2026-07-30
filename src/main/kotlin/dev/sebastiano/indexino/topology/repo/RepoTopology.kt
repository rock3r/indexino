package dev.sebastiano.indexino.topology.repo

import dev.sebastiano.indexino.topology.ExternalSourceMount
import dev.sebastiano.indexino.topology.TopologyResult
import dev.sebastiano.indexino.topology.gradle.ModuleSourceRoots
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

internal object RepoTopology {
    fun resolveSources(workspace: Path, manifestPath: Path): TopologyResult {
        val canonicalWorkspace = workspace.toRealPath()
        val mounts =
            RepoManifestParser.parse(manifestPath.readText()).projects.mapNotNull { project ->
                val root = canonicalWorkspace.resolve(project.path).normalize()
                require(root.startsWith(canonicalWorkspace)) {
                    "repo project path escapes workspace: ${project.path}"
                }
                if (!root.exists()) return@mapNotNull null
                ExternalSourceMount(
                    root = root,
                    sourceFiles = ModuleSourceRoots.collectKotlinSources(root, root),
                    originId = "repo:${project.name}",
                    expectedRevision = project.revision,
                )
            }
        return TopologyResult(
            sourceFiles = emptyList(),
            topology = "repo-manifest",
            includeDeps = true,
            scope = manifestPath.toRealPath().toString(),
            externalSources = mounts,
        )
    }
}
