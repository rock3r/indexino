package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.core.path.IndexPathResolver
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.record.CodeIndexRecordCodec
import dev.sebastiano.indexino.core.record.PluginFactRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.topology.BuildSystem
import dev.sebastiano.indexino.topology.TopologyRequest
import dev.sebastiano.indexino.topology.TopologyResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SelectionContextPluginFactsBuildTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `fresh build stores the golden plugin facts`() {
        val workspace = workspace("fresh")
        val storeRoot = tempDir.resolve("fresh-store")
        val result = build(workspace, storeRoot)
        assertEquals(SUCCESS, result.execution.exitCode, "fresh build failed")
        assertEquals(KOTLIN_FILE_COUNT, result.sharedSyntaxHandoffs)
        assertMatchesGolden(pluginFactLines(workspace, storeRoot, requireCommit(result.execution)))
    }

    @Test
    fun `incremental build stores the golden plugin facts`() = withTestCache {
        val workspace = workspace("incremental")
        val edited = workspace.resolve(EDITED_FILE)
        val finalText = edited.readText()
        edited.writeText(initialSource(finalText))
        val storeRoot = tempDir.resolve("incremental-store")
        val initial = build(workspace, storeRoot)
        assertEquals(SUCCESS, initial.execution.exitCode, "initial build failed")

        edited.writeText(finalText)
        val updated = build(workspace, storeRoot)
        assertEquals(SUCCESS, updated.execution.exitCode, "incremental build failed")
        assertEquals(1, updated.sharedSyntaxHandoffs, "only the edited file is parsed")
        assertMatchesGolden(pluginFactLines(workspace, storeRoot, requireCommit(updated.execution)))
    }

    @Test
    fun `interrupted build recovers to the golden plugin facts`() = withTestCache {
        val workspace = workspace("interrupted")
        val edited = workspace.resolve(EDITED_FILE)
        val finalText = edited.readText()
        edited.writeText(initialSource(finalText))
        val storeRoot = tempDir.resolve("interrupted-store")
        val initial = build(workspace, storeRoot)
        assertEquals(SUCCESS, initial.execution.exitCode, "initial build failed")
        val initialFacts = pluginFactLines(workspace, storeRoot, requireCommit(initial.execution))

        edited.writeText(finalText)
        assertFailsWith<IllegalArgumentException>("failed build must surface its error") {
            build(workspace, storeRoot) { message ->
                if (message.startsWith("index phase=producer:kotlin-psi-symbols state=completed")) {
                    require(false) { "stop after the Kotlin producer" }
                }
            }
        }
        assertEquals(
            initialFacts,
            pluginFactLines(workspace, storeRoot, requireCommit(initial.execution)),
            "A failed build must restore the previous plugin facts",
        )

        val recovered = build(workspace, storeRoot)
        assertEquals(SUCCESS, recovered.execution.exitCode, "recovery build failed")
        assertEquals(1, recovered.sharedSyntaxHandoffs)
        assertMatchesGolden(
            pluginFactLines(workspace, storeRoot, requireCommit(recovered.execution))
        )
    }

    private fun withTestCache(block: () -> Unit) {
        val previous = System.getProperty("indexino.cache.dir")
        val cache = tempDir.resolve("cache").also { it.createDirectories() }
        System.setProperty("indexino.cache.dir", cache.toString())
        try {
            block()
        } finally {
            if (previous == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previous)
        }
    }

    private fun build(
        workspace: Path,
        storeRoot: Path,
        progress: (String) -> Unit = {},
    ): BuildResult {
        val runner =
            IndexBuildRunner(
                project = workspace,
                topologyRequest =
                    TopologyRequest(buildSystem = BuildSystem.BAZEL, bazelTarget = "//:main"),
                applications = listOf(SELECTION_CONTEXT_PLUGIN),
                bazelQueryExecutor = null,
                bazelProcessRunner = null,
                progress = progress,
                machineProgress = null,
                storeRootOverride = storeRoot,
                topologyOverride =
                    TopologyResult(
                        sourceFiles = sourceFiles(workspace),
                        topology = "fixture",
                        includeDeps = false,
                        scope = "//:main",
                    ),
                captureOriginStates = false,
            )
        val execution = runner.runDetailed()
        return BuildResult(execution, runner.latestSharedSyntaxHandoffs)
    }

    private fun pluginFactLines(workspace: Path, storeRoot: Path, commit: String): List<String> =
        XodusCodeIndexStore.open(
                IndexPathResolver(workspace, storeRootOverride = storeRoot).resolveBaseStore(commit)
            )
            .use { store ->
                store
                    .prefixScan("plugin:")
                    .filter { (_, record) -> record is PluginFactRecord }
                    .map { (key, record) -> key.value + "\t" + encode(record) }
                    .toList()
                    .sorted()
            }

    private fun encode(record: CodeIndexRecord): String =
        CodeIndexRecordCodec.encode(record).decodeToString()

    private fun requireCommit(execution: IndexBuildExecution): String {
        check(execution.exitCode == SUCCESS) { "build failed: ${execution.exitCode}" }
        return checkNotNull(execution.manifest).commit
    }

    private fun assertMatchesGolden(actual: List<String>) {
        val path = Path.of(GOLDEN_PATH)
        if (!Files.exists(path)) {
            writeActual(actual)
            assertTrue(Files.exists(path), "Missing baseline golden: $path; actual: $ACTUAL_PATH")
        }
        val expected = Files.readAllLines(path).filter(String::isNotBlank)
        if (expected != actual) writeActual(actual)
        assertEquals(expected, actual, "Plugin facts changed; actual: $ACTUAL_PATH")
    }

    private fun writeActual(actual: List<String>) {
        val path = Path.of(ACTUAL_PATH)
        Files.createDirectories(path.parent)
        Files.write(path, actual)
    }

    private fun sourceFiles(workspace: Path): List<String> =
        Files.walk(workspace).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .map { workspace.relativize(it).toString().replace('\\', '/') }
                .sorted()
                .toList()
        }

    private fun workspace(name: String): Path {
        val root = Files.createDirectories(tempDir.resolve("$name-workspace")).toRealPath()
        fixtureFiles().forEach { (path, content) ->
            root.resolve(path).also { it.parent.createDirectories() }.writeText(content)
        }
        return root
    }

    private fun initialSource(finalText: String): String {
        check("val plainState = 0" in finalText) { "edited fixture lost its edit marker" }
        return finalText.replace(
            "val plainState = 0",
            "val plainState = 0\n\n@Composable\nfun InitialOnly() {\n    Button()\n}",
        )
    }

    private fun fixtureFiles(): Map<String, String> =
        linkedMapOf(
            "src/main/kotlin/fixture/compose/Compose.kt" to
                """
                package fixture.compose

                @Target(AnnotationTarget.FUNCTION)
                annotation class Composable

                fun SelectionContainer(content: () -> Unit) = content()

                fun DisableSelection(content: () -> Unit) = content()

                fun Button() {}

                fun ActionButton() {}
                """
                    .trimIndent(),
            "src/main/kotlin/fixture/screens/Screen.kt" to
                """
                package fixture.screens

                import fixture.compose.ActionButton
                import fixture.compose.Button
                import fixture.compose.Composable
                import fixture.compose.SelectionContainer

                @Composable
                fun Screen() {
                    SelectionContainer {
                        Button()
                        ActionButton()
                    }
                }

                @Composable
                fun Plain() {
                    Button()
                }
                """
                    .trimIndent(),
            "src/main/kotlin/fixture/screens/Nested.kt" to
                """
                package fixture.screens

                import fixture.compose.Button
                import fixture.compose.Composable
                import fixture.compose.DisableSelection
                import fixture.compose.SelectionContainer as Selection

                @Composable
                fun Nested() {
                    Selection {
                        DisableSelection {
                            Button()
                        }
                    }
                }
                """
                    .trimIndent(),
            EDITED_FILE to
                """
                package fixture.screens

                import fixture.compose.Button
                import fixture.compose.Composable
                import fixture.compose.SelectionContainer

                @Composable
                fun Edited() {
                    SelectionContainer {
                        Button()
                        Helper()
                    }
                }

                @Composable
                fun Helper() {}

                val plainState = 0
                """
                    .trimIndent(),
        )

    private data class BuildResult(
        val execution: IndexBuildExecution,
        val sharedSyntaxHandoffs: Int,
    )

    private companion object {
        const val KOTLIN_FILE_COUNT = 4
        const val SELECTION_CONTEXT_PLUGIN = "dev.sebastiano.selection-context"
        const val EDITED_FILE = "src/main/kotlin/fixture/screens/Edited.kt"
        const val GOLDEN_PATH =
            "src/test/resources/fixtures/selection-context-build/plugin-facts.txt"
        const val ACTUAL_PATH = "build/selection-context-build/actual-plugin-facts.txt"
        const val SUCCESS = 0
    }
}
