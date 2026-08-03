package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.core.manifest.IndexManifest
import dev.sebastiano.indexino.core.manifest.workspaceRevisionFingerprint
import dev.sebastiano.indexino.topology.bazel.MockBazelQueryExecutor
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
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
                applications = listOf("dev.sebastiano.selection-context"),
                queryExecutor =
                    MockBazelQueryExecutor(listOf("//plugins/foo/ui:src/main/kotlin/Panel.kt")),
            )

        val output = buildString {
            QueryCommand()
                .runQuery(
                    project = workspace,
                    application = "dev.sebastiano.selection-context",
                    checkId = "interactive-in-selection",
                    output = { appendLine(it) },
                )
        }

        assertTrue(output.lines().any { it.contains("\"checkId\":\"interactive-in-selection\"") })
        assertTrue(
            output.lines().any {
                it.contains("ActionButton is interactive inside SelectionContainer")
            }
        )
    }

    @Test
    fun `fallback workspace revision includes resolved topology digest`() {
        val base =
            IndexManifest(
                commit = "commit",
                indexerVersion = "version",
                scope = "scope",
                topology = "topology",
                sourceFileCount = 0,
                sourcesContentHash = "sources",
                builtAt = "now",
            )

        assertNotEquals(
            base.workspaceRevisionFingerprint(),
            base.copy(resolvedTopologyDigest = "topology-digest").workspaceRevisionFingerprint(),
        )
    }

    @Test
    fun `query command fails when index manifest is missing`() {
        val workspace = createGitWorkspace()

        assertFailsWith<IllegalStateException> {
            QueryCommand()
                .runQuery(
                    project = workspace,
                    application = "dev.sebastiano.selection-context",
                    checkId = "interactive-in-selection",
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
            ProcessBuilder(
                    *listOf("git", "-C", workspace.toString(), "-c", "commit.gpgsign=false", *args)
                        .toTypedArray()
                )
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }
}
