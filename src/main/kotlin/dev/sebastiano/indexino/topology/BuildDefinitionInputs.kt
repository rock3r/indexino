package dev.sebastiano.indexino.topology

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.invariantSeparatorsPathString

/**
 * One root's build-definition content and file-name inventory.
 *
 * [definitionDigest] covers the contents of files that can change discovery output: Bazel
 * `BUILD(.bazel)`, `.bzl`, `MODULE.bazel`, `WORKSPACE(.bazel)`, `.bazelproject`, `.bazelrc` and
 * lock files; Gradle settings, build scripts, properties and version catalogs. [inventoryDigest]
 * covers every file name below the root, because BUILD globs and Gradle source roots react to added
 * or removed files without any build-definition edit.
 */
internal class RootFingerprint(
    val root: String,
    val definitionDigest: String,
    val inventoryDigest: String,
)

internal object BuildDefinitionInputs {
    fun capture(root: Path, buildSystem: BuildSystem, cacheRoot: Path?): RootFingerprint? =
        runCatching {
                val canonicalRoot = root.toRealPath()
                val definitions = mutableListOf<String>()
                val inventory = mutableListOf<String>()
                val skip =
                    cacheRoot
                        ?.let { runCatching { it.toRealPath() }.getOrNull() }
                        ?.takeIf { it.startsWith(canonicalRoot) }
                val skippedDirectories =
                    if (buildSystem == BuildSystem.GRADLE) GRADLE_SKIPPED_DIRECTORIES
                    else BAZEL_SKIPPED_DIRECTORIES
                Files.walkFileTree(
                    canonicalRoot,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(
                            directory: Path,
                            attributes: BasicFileAttributes,
                        ): FileVisitResult {
                            if (
                                directory != canonicalRoot &&
                                    directory.fileName.toString() in skippedDirectories
                            ) {
                                return FileVisitResult.SKIP_SUBTREE
                            }
                            if (skip != null && directory == skip) {
                                return FileVisitResult.SKIP_SUBTREE
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(
                            file: Path,
                            attributes: BasicFileAttributes,
                        ): FileVisitResult {
                            val relative =
                                canonicalRoot.relativize(file).invariantSeparatorsPathString
                            val kind =
                                when {
                                    attributes.isSymbolicLink -> SYMLINK
                                    attributes.isRegularFile -> FILE
                                    else -> OTHER
                                }
                            inventory += "$kind:$relative"
                            if (
                                (attributes.isRegularFile || attributes.isSymbolicLink) &&
                                    isDefinition(file.fileName.toString(), buildSystem)
                            ) {
                                definitions += "$relative:${contentDigest(file)}"
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(
                            file: Path,
                            exception: java.io.IOException,
                        ): FileVisitResult {
                            val relative =
                                canonicalRoot.relativize(file).invariantSeparatorsPathString
                            inventory += "unreadable:$relative"
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
                RootFingerprint(
                    root = canonicalRoot.toString(),
                    definitionDigest = digest(definitions),
                    inventoryDigest = digest(inventory),
                )
            }
            .getOrNull()

    fun matches(stored: RootFingerprint, buildSystem: BuildSystem, cacheRoot: Path?): Boolean {
        val current = capture(Path.of(stored.root), buildSystem, cacheRoot) ?: return false
        return current.root == stored.root &&
            current.definitionDigest == stored.definitionDigest &&
            current.inventoryDigest == stored.inventoryDigest
    }

    private fun isDefinition(fileName: String, buildSystem: BuildSystem): Boolean =
        when (buildSystem) {
            BuildSystem.BAZEL ->
                fileName in BAZEL_DEFINITIONS ||
                    fileName.startsWith(".bazelrc") ||
                    fileName.endsWith(".bzl")
            BuildSystem.GRADLE -> fileName in GRADLE_DEFINITIONS
            else -> false
        }

    private fun contentDigest(file: Path): String =
        runCatching {
                val bytes = Files.readAllBytes(file)
                "${bytes.size}:" + hex(digest(bytes))
            }
            .getOrElse { "unreadable:${it.javaClass.simpleName}" }

    private fun digest(values: List<String>): String =
        hex(digest(values.sorted().joinToString("\n").toByteArray()))

    private fun digest(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hex(bytes: ByteArray): String = HexFormat.of().formatHex(bytes)

    private const val FILE = "f"
    private const val SYMLINK = "l"
    private const val OTHER = "o"

    /**
     * VCS metadata and IDE state never define a package. Gradle also writes generated artifacts
     * into conventional output directories; Bazel keeps them outside the workspace, so a `build`
     * directory there can still be a real package.
     */
    private val BAZEL_SKIPPED_DIRECTORIES = setOf(".git", ".gradle", ".idea", ".indexino")

    private val GRADLE_SKIPPED_DIRECTORIES =
        setOf(".git", ".gradle", ".idea", ".indexino", "build", "out", "target", "node_modules")

    private val BAZEL_DEFINITIONS =
        setOf(
            "BUILD",
            "BUILD.bazel",
            "MODULE.bazel",
            "MODULE.bazel.lock",
            "WORKSPACE",
            "WORKSPACE.bazel",
            "WORKSPACE.bazel.lock",
            ".bazelproject",
        )

    private val GRADLE_DEFINITIONS =
        setOf(
            "settings.gradle",
            "settings.gradle.kts",
            "build.gradle",
            "build.gradle.kts",
            "gradle.properties",
            "libs.versions.toml",
            "gradle-wrapper.properties",
        )
}
