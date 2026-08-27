package dev.sebastiano.indexino.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndexCommandRoastLauncherTest {
    private val tempDirs = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
        tempDirs.clear()
        System.clearProperty("indexino.roastLauncher")
    }

    @Test
    fun `closed world index streams progress in process`() {
        val workspace = createWorkspace()
        val result =
            runRoastCli(
                "index",
                "--project",
                workspace.toString(),
                "--build-system",
                "gradle",
                "--gradle-module",
                ":app",
                "--no-auto-refresh",
            )

        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(result.stderr.contains("JavaSourceProducer"))
        assertFalse(result.stderr.contains("WARN: Attempt to load key '"))
    }

    private fun createWorkspace(): Path {
        val workspace = createTempDirectory("roast-index-cli-")
        tempDirs.add(workspace)
        Files.writeString(workspace.resolve("settings.gradle.kts"), "include(\":app\")")
        val sourceRoot = workspace.resolve("app/src/main/java/sample")
        Files.createDirectories(sourceRoot)
        Files.writeString(sourceRoot.resolve("Panel.java"), "package sample; public class Panel {}")
        runGit(workspace, "init")
        runGit(workspace, "config", "user.email", "test@example.com")
        runGit(workspace, "config", "user.name", "Test User")
        runGit(workspace, "add", ".")
        runGit(workspace, "commit", "-m", "fixture")
        return workspace
    }

    private fun runRoastCli(vararg args: String): CliResult {
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process =
            ProcessBuilder(
                    javaExecutable,
                    "-Dindexino.roastLauncher=true",
                    "-cp",
                    System.getProperty("java.class.path"),
                    "dev.sebastiano.indexino.cli.MainCommandKt",
                    *args,
                )
                .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return CliResult(exitCode, stdout, stderr)
    }

    private fun runGit(workspace: Path, vararg args: String) {
        val process =
            ProcessBuilder(
                    *listOf("git", "-C", workspace.toString(), "-c", "commit.gpgsign=false", *args)
                        .toTypedArray()
                )
                .redirectErrorStream(true)
                .start()
        check(process.waitFor() == 0)
    }

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)
}
