package dev.sebastiano.indexino.acceptance

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.path.IndexPathResolver
import dev.sebastiano.indexino.core.record.PluginFactRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.engine.BuildExitCodes
import dev.sebastiano.indexino.engine.IndexBuildRunner
import dev.sebastiano.indexino.topology.BuildSystem
import dev.sebastiano.indexino.topology.TopologyRequest
import dev.sebastiano.indexino.topology.TopologyResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.system.measureNanoTime

/**
 * Opt-in, test-classpath-only driver for the Kotlin producer plus the bundled selection-context
 * analyzer against the real engine and Xodus staging store.
 */
internal object SelectionContextIndexingPerformanceDriver {
    private const val MAX_FILES = 2_000
    private const val MAX_FUNCTIONS = 100
    private const val PLUGIN_ID = "dev.sebastiano.selection-context"

    @JvmStatic
    @Suppress("LongMethod")
    fun main(args: Array<String>) {
        require(args.size == 4) {
            "usage: SelectionContextIndexingPerformanceDriver <fresh-root> <report.json> <files> " +
                "<functions-per-file>"
        }
        val root = Path.of(args[0]).toAbsolutePath().normalize()
        val report = Path.of(args[1]).toAbsolutePath().normalize()
        val fileCount = args[2].toInt()
        val functionCount = args[3].toInt()
        require(fileCount in 2..MAX_FILES) { "files must be in 2..$MAX_FILES" }
        require(functionCount in 1..MAX_FUNCTIONS) {
            "functions-per-file must be in 1..$MAX_FUNCTIONS"
        }
        require(Files.notExists(root)) { "owned root must not exist: $root" }
        require(!report.startsWith(root)) { "report must be outside owned root: $report" }
        require(report.parent != null && Files.isDirectory(report.parent)) {
            "report parent must already exist: ${report.parent}"
        }

        val previousCache = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", root.resolve("cache").toString())
        try {
            val workspace = root.resolve("workspace").also { it.createDirectories() }
            val sources = generateSources(workspace, fileCount, functionCount)
            val storeRoot = root.resolve("store")
            val refreshCache = root.resolve("refresh-cache")
            val phases = linkedMapOf<String, Long>()
            val progress: (String) -> Unit = { message ->
                PHASE_COMPLETED.matchEntire(message)?.let { match ->
                    phases[match.groupValues[1]] = match.groupValues[2].toLong()
                }
            }
            lateinit var execution: dev.sebastiano.indexino.engine.IndexBuildExecution
            val wallNanos = measureNanoTime {
                execution =
                    IndexBuildRunner(
                            project = workspace,
                            topologyRequest =
                                TopologyRequest(
                                    buildSystem = BuildSystem.BAZEL,
                                    bazelTarget = "//:main",
                                ),
                            applications = listOf(PLUGIN_ID),
                            bazelQueryExecutor = null,
                            bazelProcessRunner = null,
                            progress = progress,
                            machineProgress = null,
                            storeRootOverride = storeRoot,
                            refreshCacheRootOverride = refreshCache,
                            topologyOverride =
                                TopologyResult(
                                    sourceFiles = sources,
                                    topology = "fixture",
                                    includeDeps = false,
                                    scope = "//:main",
                                ),
                            captureOriginStates = false,
                        )
                        .runDetailed()
            }
            check(execution.exitCode == BuildExitCodes.SUCCESS) {
                "build failed with exit code ${execution.exitCode}"
            }
            val commit = checkNotNull(execution.manifest).commit
            val counts =
                XodusCodeIndexStore.open(
                        IndexPathResolver(workspace, storeRootOverride = storeRoot)
                            .resolveBaseStore(commit)
                    )
                    .use { store ->
                        val symbols =
                            store.prefixScan("sym:").count { (_, record) -> record is SymbolRecord }
                        val pluginFacts =
                            store
                                .prefixScan(CodeIndexKey.pluginFactPluginPrefix(PLUGIN_ID))
                                .count { (_, record) -> record is PluginFactRecord }
                        symbols to pluginFacts
                    }
            check(counts.second == fileCount * functionCount) {
                "selection facts were ${counts.second}, expected ${fileCount * functionCount}"
            }
            val json =
                """
                {
                  "coldWallMillis": ${nanosToMillis(wallNanos)},
                  "kotlinProducerMillis": ${phases["producer:kotlin-psi-symbols"] ?: -1},
                  "pluginsPrepareMillis": ${phases["plugins-prepare"] ?: -1},
                  "pluginsMillis": ${phases["plugins"] ?: -1},
                  "changeDetectionMillis": ${phases["change-detection"] ?: -1},
                  "sourceResolutionMillis": ${phases["source-resolution"] ?: -1},
                  "symbolCount": ${counts.first},
                  "selectionFactCount": ${counts.second},
                  "files": $fileCount,
                  "functionsPerFile": $functionCount,
                  "processors": ${Runtime.getRuntime().availableProcessors()}
                }
                """
                    .trimIndent() + "\n"
            report.writeText(json)
            print(json)
        } finally {
            if (previousCache == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCache)
            deleteOwnedRoot(root)
        }
    }

    private fun generateSources(workspace: Path, fileCount: Int, functionCount: Int): List<String> {
        val sourceDirectory =
            workspace.resolve("src/main/kotlin/bench").also { it.createDirectories() }
        sourceDirectory
            .resolve("Compose.kt")
            .writeText(
                """
                package bench

                @Target(AnnotationTarget.FUNCTION)
                annotation class Composable

                fun SelectionContainer(content: () -> Unit) = content()
                """
                    .trimIndent()
            )
        return (0 until fileCount).map { index ->
            val relativePath = "src/main/kotlin/bench/Screen$index.kt"
            sourceDirectory.resolve("Screen$index.kt").writeText(kotlinSource(index, functionCount))
            relativePath
        } + "src/main/kotlin/bench/Compose.kt"
    }

    private fun kotlinSource(index: Int, functionCount: Int): String = buildString {
        appendLine("package bench")
        appendLine()
        appendLine("@Composable")
        appendLine("fun Screen$index() {")
        appendLine("    SelectionContainer {")
        repeat(functionCount) { function -> appendLine("        Node${index}_$function()") }
        appendLine("    }")
        appendLine("}")
        repeat(functionCount) { function ->
            appendLine()
            appendLine("@Composable")
            appendLine("fun Node${index}_$function() {}")
        }
    }

    private fun nanosToMillis(nanos: Long): String =
        "%.3f".format(java.util.Locale.ROOT, nanos / 1_000_000.0)

    private fun deleteOwnedRoot(root: Path) {
        if (Files.notExists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private val PHASE_COMPLETED = Regex("index phase=(\\S+) state=completed durationMillis=(\\d+)")
}
