package dev.sebastiano.indexino.topology.bazel

import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

internal object BuildFileGlob {
    private const val RECURSIVE_GLOB_PREFIX = "**/"
    private const val RECURSIVE_GLOB_PREFIX_LENGTH = RECURSIVE_GLOB_PREFIX.length

    fun expandGlob(packageDir: Path, pattern: String): List<String> {
        if (!pattern.contains('*') && !pattern.contains('?')) {
            val file = packageDir.resolve(pattern)
            return if (file.isRegularFile()) listOf(pattern) else emptyList()
        }
        if (!packageDir.exists()) {
            return emptyList()
        }
        val patterns = bazelGlobPatternVariants(pattern)
        val matchers = patterns.map { packageDir.fileSystem.getPathMatcher("glob:$it") }
        return Files.walk(packageDir, FileVisitOption.FOLLOW_LINKS).use { stream ->
            stream
                .filter { it.isRegularFile() }
                .filter { !isInSubpackage(packageDir, it) }
                .map { packageDir.relativize(it).toString().replace('\\', '/') }
                .filter { relative -> matchers.any { it.matches(Path.of(relative)) } }
                .sorted()
                .toList()
        }
    }

    private fun bazelGlobPatternVariants(pattern: String): List<String> {
        if (!pattern.contains(RECURSIVE_GLOB_PREFIX)) {
            return listOf(pattern)
        }
        val variants = linkedSetOf(pattern)
        var searchFrom = 0
        while (true) {
            val start = pattern.indexOf(RECURSIVE_GLOB_PREFIX, searchFrom)
            if (start < 0) {
                break
            }
            variants += pattern.removeRange(start, start + RECURSIVE_GLOB_PREFIX_LENGTH)
            searchFrom = start + 1
        }
        return variants.toList()
    }

    private fun isInSubpackage(packageDir: Path, file: Path): Boolean {
        var current = file.parent
        while (current != null && current != packageDir) {
            if (hasBuildFile(current)) {
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun hasBuildFile(packageDir: Path): Boolean =
        packageDir.resolve("BUILD.bazel").isRegularFile() ||
            packageDir.resolve("BUILD").isRegularFile()
}
