package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.topology.BuildSystem
import dev.sebastiano.indexino.topology.TopologyRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class IndexBuildRunnerTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `detailed result keeps the commit indexed when head advances during the run`() {
        val workspace = tempDir.resolve("workspace")
        copyFixture(Path("src/test/resources/gradle-fixtures/multi-module"), workspace)
        git(workspace, "init")
        git(workspace, "config", "user.email", "test@example.invalid")
        git(workspace, "config", "user.name", "Indexino Test")
        git(workspace, "add", ".")
        git(workspace, "commit", "-m", "initial fixture")
        val indexedCommit = git(workspace, "rev-parse", "HEAD").trim()
        var headAdvanced = false

        val execution =
            IndexBuildRunner(
                    project = workspace,
                    topologyRequest =
                        TopologyRequest(buildSystem = BuildSystem.GRADLE, gradleModule = ":ui"),
                    applications = emptyList(),
                    bazelQueryExecutor = null,
                    bazelProcessRunner = null,
                    progress = {
                        if (!headAdvanced) {
                            headAdvanced = true
                            Files.writeString(workspace.resolve("head-marker.txt"), "advanced")
                            git(workspace, "add", "head-marker.txt")
                            git(workspace, "commit", "-m", "advance head")
                        }
                    },
                    machineProgress = null,
                    storeRootOverride = tempDir.resolve("store"),
                )
                .runDetailed()

        assertEquals(CliExitCodes.SUCCESS, execution.exitCode)
        assertEquals(indexedCommit, execution.manifest?.commit)
    }

    private fun copyFixture(source: Path, destination: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val target = destination.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(path, target)
                }
            }
        }
    }

    private fun git(workspace: Path, vararg arguments: String): String {
        val process =
            ProcessBuilder(
                    "git",
                    "-C",
                    workspace.toString(),
                    "-c",
                    "commit.gpgsign=false",
                    *arguments,
                )
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
        return output
    }
}
