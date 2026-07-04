package com.kotlincodeindex.topology.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

object ModuleSourceRoots {
    private val conventionalSourceDirs = listOf(
        "src/main/kotlin",
        "src/commonMain/kotlin",
        "src/jvmMain/kotlin",
        "src/androidMain/kotlin",
    )

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
        val fromConventional = conventionalSourceDirs.flatMap { relative ->
            val root = moduleDir.resolve(relative)
            if (!root.exists()) {
                emptyList()
            } else {
                root.walk()
                    .filter { it.isRegularFile() && it.fileName.toString().endsWith(".kt") }
                    .map { it.relativeTo(workspace).toString().replace('\\', '/') }
                    .toList()
            }
        }
        return fromConventional.distinct().sorted()
    }
}
