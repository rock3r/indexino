package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.topology.bazel.MockBazelQueryExecutor
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QueryCommandTest {
    private val tempDirs = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
        tempDirs.clear()
    }

    @Test
    fun `query command emits jsonl for preset against indexed workspace`() {
        val workspace = createGitWorkspace()
        IndexCommand()
            .runIndexedBuild(
                project = workspace,
                bazelTarget = "//plugins/foo/ui:ui",
                applications = listOf("selection-context"),
                queryExecutor =
                    MockBazelQueryExecutor(listOf("//plugins/foo/ui:src/main/kotlin/Panel.kt")),
            )

        val output = buildString {
            QueryCommand()
                .runQuery(
                    project = workspace,
                    application = "selection-context",
                    preset = "all-call-sites",
                    output = { appendLine(it) },
                )
        }

        assertTrue(output.lines().any { it.contains("\"callee\":") })
        assertTrue(output.lines().any { it.contains("\"target\":\"//plugins/foo/ui:ui\"") })
        assertTrue(output.lines().any { it.contains("\"topology\":\"bazel-query\"") })
    }

    @Test
    fun `query command fails when index manifest is missing`() {
        val workspace = createGitWorkspace()

        assertFailsWith<IllegalStateException> {
            QueryCommand()
                .runQuery(
                    project = workspace,
                    application = "selection-context",
                    preset = "all-call-sites",
                )
        }
    }

    private fun createGitWorkspace(): java.nio.file.Path {
        val workspace = createTempDirectory("query-cmd-test-")
        tempDirs.add(workspace)

        val panelContent =
            """
            @Target(AnnotationTarget.FUNCTION)
            annotation class Composable

            @Composable
            fun Panel() {
                SelectionContainer {
                    ActionButton()
                }
            }

            @Composable
            fun ActionButton() {}
            """
                .trimIndent()

        val panelPath = workspace.resolve("plugins/foo/ui/src/main/kotlin/Panel.kt")
        Files.createDirectories(panelPath.parent)
        Files.writeString(panelPath, panelContent)

        runGit(workspace, "init")
        runGit(workspace, "config", "user.email", "test@example.com")
        runGit(workspace, "config", "user.name", "Test User")
        runGit(workspace, "add", ".")
        runGit(workspace, "commit", "-m", "fixture")

        return workspace
    }

    private fun runGit(workspace: java.nio.file.Path, vararg args: String) {
        val process =
            ProcessBuilder(*listOf("git", "-C", workspace.toString(), *args).toTypedArray())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }
}
