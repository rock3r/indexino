package dev.sebastiano.indexino.topology

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import kotlin.io.path.invariantSeparatorsPathString

/** Resolves topology source paths into independently owned, origin-relative source groups. */
internal object SourceOriginResolver {
    private const val EXTERNAL_ORIGIN_HASH_LENGTH = 16

    fun resolve(workspace: Path, sourceFiles: List<String>): List<ResolvedSourceOrigin> {
        val canonicalWorkspace = workspace.toRealPath()
        val gitRoots = mutableMapOf<Path, Path?>()
        val origins = linkedMapOf<Path, MutableList<String>>()
        sourceFiles.forEach { sourceFile ->
            val sourcePath = canonicalWorkspace.resolve(sourceFile).normalize()
            require(sourcePath.startsWith(canonicalWorkspace)) {
                "Topology source path escapes workspace: $sourceFile"
            }
            val sourceDirectory = sourcePath.parent
            val gitRoot =
                cachedGitRoot(sourceDirectory, gitRoots)
                    ?: gitRoots.getOrPut(sourceDirectory) { findGitRoot(sourceDirectory) }
            val originRoot =
                gitRoot?.takeIf { it.startsWith(canonicalWorkspace) } ?: canonicalWorkspace
            origins.getOrPut(originRoot) { mutableListOf() } +=
                originRoot.relativize(sourcePath).invariantSeparatorsPathString
        }
        return origins.entries
            .map { (root, files) ->
                ResolvedSourceOrigin(
                    id = originId(canonicalWorkspace, root),
                    root = root,
                    sourceFiles = files.sorted(),
                )
            }
            .sortedWith(
                compareBy<ResolvedSourceOrigin> { it.id != WORKSPACE_ORIGIN_ID }.thenBy { it.id }
            )
    }

    private fun cachedGitRoot(directory: Path, gitRoots: Map<Path, Path?>): Path? {
        val cachedRoot =
            gitRoots.values
                .filterNotNull()
                .filter(directory::startsWith)
                .maxByOrNull(Path::getNameCount) ?: return null
        var ancestor = directory
        while (ancestor != cachedRoot) {
            if (Files.exists(ancestor.resolve(".git"))) return null
            ancestor = ancestor.parent ?: return null
        }
        return cachedRoot
    }

    fun externalOriginId(root: Path): String {
        val canonicalRoot = root.toRealPath()
        val identity =
            listOf(gitRemoteUrl(canonicalRoot).orEmpty(), canonicalRoot.toString())
                .joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        return "external:" +
            digest
                .joinToString("") { "%02x".format(Locale.ROOT, it) }
                .take(EXTERNAL_ORIGIN_HASH_LENGTH)
    }

    private fun gitRemoteUrl(root: Path): String? {
        val process =
            runCatching {
                    ProcessBuilder("git", "-C", root.toString(), "remote", "get-url", "origin")
                        .redirectErrorStream(true)
                        .start()
                }
                .getOrNull() ?: return null
        val output = process.inputStream.bufferedReader().readText().trim()
        return output.takeIf { process.waitFor() == 0 && it.isNotBlank() }
    }

    private fun findGitRoot(directory: Path): Path? {
        val process =
            runCatching {
                    ProcessBuilder(
                            "git",
                            "-C",
                            directory.toString(),
                            "rev-parse",
                            "--show-toplevel",
                        )
                        .redirectErrorStream(true)
                        .start()
                }
                .getOrNull() ?: return null
        val output = process.inputStream.bufferedReader().readText().trim()
        return output
            .takeIf { process.waitFor() == 0 && it.isNotBlank() }
            ?.let(Path::of)
            ?.toRealPath()
    }

    private fun originId(workspace: Path, originRoot: Path): String =
        if (originRoot == workspace) WORKSPACE_ORIGIN_ID
        else "git:${workspace.relativize(originRoot).invariantSeparatorsPathString}"

    private const val WORKSPACE_ORIGIN_ID = "workspace"
}

internal data class ResolvedSourceOrigin(
    val id: String,
    val root: Path,
    val sourceFiles: List<String>,
)
