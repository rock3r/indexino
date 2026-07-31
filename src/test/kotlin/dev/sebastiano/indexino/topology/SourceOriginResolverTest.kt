package dev.sebastiano.indexino.topology

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SourceOriginResolverTest {
    private val temporaryDirectories = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        temporaryDirectories.forEach { it.toFile().deleteRecursively() }
        temporaryDirectories.clear()
    }

    @Test
    fun `distinguishes external mounts with the same Git remote`() {
        val first = temporaryDirectory("indexino-external-first-")
        val second = temporaryDirectory("indexino-external-second-")
        first.resolve("Source.kt").writeText("class First")
        second.resolve("Source.kt").writeText("class Second")
        initializeGitRepository(first)
        initializeGitRepository(second)
        runGit(first, "remote", "add", "origin", "https://example.invalid/build-logic.git")
        runGit(second, "remote", "add", "origin", "https://example.invalid/build-logic.git")

        assertNotEquals(
            SourceOriginResolver.externalOriginId(first),
            SourceOriginResolver.externalOriginId(second),
        )
    }

    @Test
    fun `assigns nested Git sources to their nearest origin`() {
        val workspace = temporaryDirectory("indexino-origin-workspace-")
        workspace
            .resolve("src/main/kotlin/Root.kt")
            .also { it.parent.createDirectories() }
            .writeText("class Root")
        initializeGitRepository(workspace)
        val child = workspace.resolve("android")
        child
            .resolve("src/main/kotlin/Child.kt")
            .also { it.parent.createDirectories() }
            .writeText("class Child")
        initializeGitRepository(child)

        val origins =
            SourceOriginResolver.resolve(
                workspace = workspace,
                sourceFiles = listOf("src/main/kotlin/Root.kt", "android/src/main/kotlin/Child.kt"),
            )

        assertEquals(
            listOf(
                ResolvedSourceOrigin(
                    id = "workspace",
                    root = workspace.toRealPath(),
                    sourceFiles = listOf("src/main/kotlin/Root.kt"),
                ),
                ResolvedSourceOrigin(
                    id = "git:android",
                    root = child.toRealPath(),
                    sourceFiles = listOf("src/main/kotlin/Child.kt"),
                ),
            ),
            origins,
        )
    }

    private fun temporaryDirectory(prefix: String): Path =
        createTempDirectory(prefix).also(temporaryDirectories::add)

    private fun initializeGitRepository(directory: Path) {
        runGit(directory, "init")
        runGit(directory, "config", "user.email", "test@indexino.invalid")
        runGit(directory, "config", "user.name", "Indexino Test")
        runGit(directory, "add", ".")
        runGit(directory, "commit", "-m", "initial")
    }

    private fun runGit(directory: Path, vararg arguments: String) {
        val process =
            ProcessBuilder(
                    listOf("git", "-C", directory.toString(), "-c", "commit.gpgsign=false") +
                        arguments
                )
                .start()
        check(process.waitFor() == 0) { process.errorStream.bufferedReader().readText() }
    }
}
