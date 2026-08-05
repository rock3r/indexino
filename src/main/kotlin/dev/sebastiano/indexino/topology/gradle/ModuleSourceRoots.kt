package dev.sebastiano.indexino.topology.gradle

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

internal object ModuleSourceRoots {
    private val sourceExtensions = setOf("kt", "java")

    fun moduleDirectory(workspace: Path, modulePath: String): Path {
        val segments = modulePath.removePrefix(":").split(":").filter { it.isNotBlank() }
        // Topology discovery must never return paths that escape a root via ".." (contract).
        // This check is load-bearing for the CLI, which never constructs IndexScope. Also reject
        // '/' and '\\' inside a colon-segment so values like ":../../outside" cannot slip through.
        require(segments.all(::isSafeModuleSegment)) {
            "Gradle module path must not contain '.', '..', or path-separator segments: $modulePath"
        }
        return if (segments.isEmpty()) {
            workspace
        } else {
            workspace.resolve(segments.joinToString("/"))
        }
    }

    private fun isSafeModuleSegment(segment: String): Boolean =
        segment != "." && segment != ".." && '/' !in segment && '\\' !in segment

    fun collectKotlinSources(moduleDir: Path, workspace: Path): List<String> {
        if (!moduleDir.exists()) {
            return emptyList()
        }
        val sourceRoot = moduleDir.resolve("src")
        if (!sourceRoot.exists()) {
            return emptyList()
        }
        return sourceRoot
            .walk()
            .filter { it.isRegularFile() && isIndexable(it, sourceRoot) }
            .map { it.relativeTo(workspace).toString().replace('\\', '/') }
            .distinct()
            .sorted()
            .toList()
    }

    private fun isIndexable(path: Path, sourceRoot: Path): Boolean {
        val relative = path.relativeTo(sourceRoot).toString().replace('\\', '/')
        val segments = relative.split('/')
        if (segments.size < MIN_SOURCE_PATH_SEGMENTS) {
            return false
        }
        val sourceSet = segments[0]
        if (sourceSet.contains("test", ignoreCase = true)) {
            return false
        }
        val sourceKind = segments[1]
        val extension = path.fileName.toString().substringAfterLast('.', "")
        return (sourceKind in CODE_SOURCE_DIRS && extension in sourceExtensions) ||
            (sourceKind in RESOURCE_SOURCE_DIRS && extension.isNotBlank())
    }

    private val CODE_SOURCE_DIRS = setOf("kotlin", "java")
    private val RESOURCE_SOURCE_DIRS = setOf("res", "composeResources")
    private const val MIN_SOURCE_PATH_SEGMENTS = 3
}
