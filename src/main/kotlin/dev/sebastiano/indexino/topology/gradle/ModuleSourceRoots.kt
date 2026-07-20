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
        return if (segments.isEmpty()) {
            workspace
        } else {
            workspace.resolve(segments.joinToString("/"))
        }
    }

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
            (sourceKind == "res" && extension == "xml")
    }

    private val CODE_SOURCE_DIRS = setOf("kotlin", "java")
    private const val MIN_SOURCE_PATH_SEGMENTS = 3
}
