package dev.sebastiano.indexino.topology.gradle

import dev.sebastiano.indexino.topology.ExternalSourceMount
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

        val settingsContent = settingsFile.readText()
        val includes = SettingsParser.parseIncludes(settingsContent)
        val externalMounts =
            resolveExternalMounts(workspace, settingsFile, settingsContent, onStderr)
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

        val scopedExternalMounts = if (rootScope || includeDeps) externalMounts else emptyList()
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
            externalMounts = externalMounts,
            externalSources =
                scopedExternalMounts.map { mount ->
                    ExternalSourceMount(root = mount, sourceFiles = collectBuildSources(mount))
                },
        )
    }

    private fun collectBuildSources(buildRoot: Path): List<String> {
        val settings =
            listOf("settings.gradle.kts", "settings.gradle").map(buildRoot::resolve).firstOrNull {
                it.exists()
            } ?: return emptyList()
        val modules = listOf(":") + SettingsParser.parseIncludes(settings.readText())
        return modules
            .flatMap { module ->
                ModuleSourceRoots.collectKotlinSources(
                    ModuleSourceRoots.moduleDirectory(buildRoot, module),
                    buildRoot,
                )
            }
            .distinct()
            .sorted()
    }

    private fun resolveExternalMounts(
        workspace: Path,
        settingsFile: Path,
        settingsContent: String,
        onStderr: (String) -> Unit,
    ): List<Path> {
        val canonicalWorkspace = workspace.toRealPath()
        val allowedExternalRoot = canonicalWorkspace.parent ?: canonicalWorkspace
        val visitedBuilds = linkedSetOf(canonicalWorkspace)
        val externalMounts = linkedSetOf<Path>()

        fun visit(settings: Path, content: String) {
            SettingsParser.parseIncludedBuilds(content).forEach { declaredPath ->
                val declaredRoot = settings.parent.resolve(declaredPath).normalize()
                require(declaredRoot.toFile().isDirectory) {
                    "Gradle included build mount is unavailable: $declaredRoot"
                }
                val buildRoot = declaredRoot.toRealPath()
                require(buildRoot.startsWith(allowedExternalRoot)) {
                    "Gradle included build is outside the allowed external root policy: $buildRoot"
                }
                if (!visitedBuilds.add(buildRoot)) return@forEach
                if (buildRoot != canonicalWorkspace) {
                    externalMounts.add(buildRoot)
                    if (!buildRoot.startsWith(canonicalWorkspace)) {
                        onStderr("gradle-parse: external included build $buildRoot")
                    }
                }
                val nestedSettings =
                    listOf("settings.gradle.kts", "settings.gradle")
                        .map(buildRoot::resolve)
                        .firstOrNull { it.exists() } ?: return@forEach
                visit(nestedSettings, nestedSettings.readText())
            }
        }

        visit(settingsFile, settingsContent)
        return externalMounts.toList()
    }

    fun normalizeModule(raw: String): String = if (raw.startsWith(":")) raw else ":$raw"
}
