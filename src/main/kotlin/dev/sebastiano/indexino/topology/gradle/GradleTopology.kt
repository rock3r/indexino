package dev.sebastiano.indexino.topology.gradle

import dev.sebastiano.indexino.topology.TopologyResult
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

internal object GradleTopology {
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
        val rootScope = normalizedModule == ":"
        val modules =
            if (rootScope) {
                // Whole-build selection (root + every included module). Do not change this to
                // honour includeDeps=false — that would shrink CLI `index --gradle-module :` to
                // nearly empty for typical multi-module repos.
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
            // Root selection is a superset of the root project's dependency closure, so report
            // includeDeps=true even when the request asked for false. Over-reporting inclusion is
            // the safe direction for facade mismatch checks; under-reporting would publish a false
            // provenance record.
            includeDeps = if (rootScope) true else includeDeps,
            scope = normalizedModule,
        )
    }

    fun normalizeModule(raw: String): String = if (raw.startsWith(":")) raw else ":$raw"
}
