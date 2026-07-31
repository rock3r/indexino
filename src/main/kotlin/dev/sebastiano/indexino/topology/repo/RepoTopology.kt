package dev.sebastiano.indexino.topology.repo

import dev.sebastiano.indexino.topology.ExternalSourceMount
import dev.sebastiano.indexino.topology.TopologyResult
import dev.sebastiano.indexino.topology.gradle.ModuleSourceRoots
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal object RepoTopology {
    fun resolveSources(workspace: Path, manifestPath: Path): TopologyResult {
        val canonicalWorkspace = workspace.toRealPath()
        val resolvedManifest = RepoManifestParser.parse(manifestPath)
        val projectRoots =
            resolvedManifest.projects.map { project ->
                val root = canonicalWorkspace.resolve(project.path).normalize()
                require(root.startsWith(canonicalWorkspace)) {
                    "repo project path escapes workspace: ${project.path}"
                }
                require(root.toFile().isDirectory) {
                    "repo project mount is unavailable: ${project.name} at $root"
                }
                project to root
            }
        val mounts = projectRoots.map { (project, root) ->
            val nestedMounts =
                projectRoots.map { it.second }.filter { it != root && it.startsWith(root) }.toSet()
            val moduleRoots = findModuleRoots(root, nestedMounts)
            ExternalSourceMount(
                root = root,
                sourceFiles =
                    moduleRoots
                        .flatMap { moduleRoot ->
                            ModuleSourceRoots.collectKotlinSources(moduleRoot, root)
                        }
                        .distinct()
                        .sorted(),
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
            resolvedTopologyDigest = resolvedManifest.digest,
        )
    }

    private fun findModuleRoots(root: Path, nestedMounts: Set<Path>): List<Path> {
        val moduleRoots = mutableListOf<Path>()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    directory: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    val name = directory.fileName.toString()
                    if (
                        directory in nestedMounts ||
                            (directory != root && name in IGNORED_DIRECTORY_NAMES)
                    ) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    if (name == "src") {
                        moduleRoots.add(directory.parent)
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return moduleRoots
    }

    private val IGNORED_DIRECTORY_NAMES =
        setOf(".git", ".gradle", "build", "node_modules", "out", "target")
}
