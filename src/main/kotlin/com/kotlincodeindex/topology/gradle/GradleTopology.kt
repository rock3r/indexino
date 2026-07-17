package com.kotlincodeindex.topology.gradle

import com.kotlincodeindex.topology.TopologyResult
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

object GradleTopology {
    fun resolveSources(
        gradleModule: String,
        workspace: Path,
        includeDeps: Boolean = false,
        onStderr: (String) -> Unit = { System.err.println(it) },
    ): TopologyResult {
        val settingsFile =
            listOf("settings.gradle.kts", "settings.gradle")
                .map { workspace.resolve(it) }
                .firstOrNull { it.exists() } ?: error("No settings.gradle(.kts) in $workspace")

        val includes = SettingsParser.parseIncludes(settingsFile.readText())
        if (includes.isEmpty()) {
            onStderr("gradle-parse: no included modules in ${settingsFile.fileName}")
        }

        val normalizedModule = normalizeModule(gradleModule)
        if (normalizedModule !in includes && normalizedModule != ":") {
            onStderr("gradle-parse: module $normalizedModule not in settings includes")
        }

        val graph = GradleModuleGraph(workspace, includes)
        val modules =
            if (normalizedModule == ":") {
                listOf(":") + includes
            } else {
                graph.closure(normalizedModule, includeDeps)
            }

        val sourceFiles =
            modules
                .flatMap { module ->
                    ModuleSourceRoots.collectKotlinSources(
                        ModuleSourceRoots.moduleDirectory(workspace, module),
                        workspace,
                    )
                }
                .distinct()
                .sorted()

        return TopologyResult(
            sourceFiles = sourceFiles,
            topology = "gradle-parse",
            includeDeps = includeDeps,
            scope = normalizedModule,
        )
    }

    fun normalizeModule(raw: String): String = if (raw.startsWith(":")) raw else ":$raw"
}
