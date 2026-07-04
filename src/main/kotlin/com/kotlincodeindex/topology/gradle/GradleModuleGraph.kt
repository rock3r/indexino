package com.kotlincodeindex.topology.gradle

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class GradleModuleGraph(
    private val workspace: Path,
    private val includedModules: List<String>,
) {
    private val dependencyMap: Map<String, List<String>> by lazy { buildDependencyMap() }

    fun closure(rootModule: String, includeDeps: Boolean): List<String> {
        if (!includeDeps) {
            return listOf(rootModule)
        }
        val visited = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(rootModule)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) {
                continue
            }
            for (dep in dependencyMap[current].orEmpty()) {
                if (dep in includedModules) {
                    queue.add(dep)
                }
            }
        }
        return visited.toList()
    }

    private fun buildDependencyMap(): Map<String, List<String>> {
        val map = mutableMapOf<String, List<String>>()
        for (module in includedModules) {
            val moduleDir = ModuleSourceRoots.moduleDirectory(workspace, module)
            val buildFile = listOf("build.gradle.kts", "build.gradle")
                .map { moduleDir.resolve(it) }
                .firstOrNull { it.exists() }
            val deps = buildFile?.readText()?.let { BuildGradleParser.parseProjectDependencies(it) }.orEmpty()
            map[module] = deps
        }
        return map
    }
}
