package com.kotlincodeindex.cli

import com.kotlincodeindex.core.manifest.ManifestIO
import com.kotlincodeindex.core.path.IndexPathResolver
import com.kotlincodeindex.core.record.FileHashRecord
import com.kotlincodeindex.core.xodus.XodusCodeIndexStore
import com.kotlincodeindex.topology.bazel.BazelProcessRunner
import com.kotlincodeindex.topology.bazel.BazelQueryOutcome
import com.kotlincodeindex.topology.bazel.MockBazelQueryExecutor
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Files

class IndexCommandTest {
    private val tempDirs = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { dir ->
            dir.toFile().deleteRecursively()
        }
        tempDirs.clear()
    }

    @Test
    fun `index command builds store and manifest in temp workspace`() {
        val workspace = createGitWorkspace()
        val mockOutput = Path("src/test/resources/fixtures/bazel/mock-query-output.txt")
            .readText()
            .lines()

        val stderr = StringBuilder()
        val exitCode = IndexCommand().runIndexedBuild(
            project = workspace,
            bazelTarget = "//plugins/foo/ui:ui",
            applications = emptyList(),
            queryExecutor = MockBazelQueryExecutor(mockOutput),
            progress = { stderr.appendLine(it) },
        )

        assertEquals(0, exitCode)

        val commit = ProcessBuilder("git", "-C", workspace.toString(), "rev-parse", "HEAD")
            .redirectErrorStream(true)
            .start()
            .inputStream
            .bufferedReader()
            .readText()
            .trim()

        val resolver = IndexPathResolver(workspace)
        val manifest = ManifestIO.read(resolver.resolveManifest(commit))
        assertEquals(commit, manifest.commit)
        assertEquals("//plugins/foo/ui:ui", manifest.scope)
        assertEquals("bazel-query", manifest.topology)
        assertEquals(2, manifest.sourceFileCount)
        assertTrue(manifest.sourcesContentHash.startsWith("sha256:"))
        assertTrue(manifest.builtAt.isNotBlank())
        assertEquals(emptyList(), manifest.applications)

        val store = XodusCodeIndexStore.open(resolver.resolveBaseStore(commit), readOnly = true)
        try {
            val fileRecords = store.prefixScan("file:").toList()
            assertEquals(2, fileRecords.size)
            fileRecords.forEach { (_, record) ->
                assertTrue(record is FileHashRecord)
            }
        } finally {
            store.close()
        }

        assertTrue(stderr.toString().contains("FileHashProducer"))
    }

    @Test
    fun `manifest includeDeps false when bazel query falls back to labels srcs`() {
        val workspace = createGitWorkspace()
        val fallbackRunner = object : BazelProcessRunner {
            override fun run(query: String, workspace: java.nio.file.Path): BazelQueryOutcome {
                return when {
                    query.contains("deps(") -> BazelQueryOutcome(1, listOf("ERROR: partial checkout"))
                    query.contains("labels(srcs,") -> BazelQueryOutcome(
                        0,
                        listOf(
                            "//plugins/foo/ui:src/main/kotlin/Panel.kt",
                            "//plugins/foo/ui:src/main/kotlin/Other.kt",
                        ),
                    )
                    else -> error("unexpected query: $query")
                }
            }
        }

        val exitCode = IndexCommand().runIndexedBuild(
            project = workspace,
            bazelTarget = "//plugins/foo/ui:ui",
            applications = emptyList(),
            queryExecutor = null,
            processRunner = fallbackRunner,
        )
        assertEquals(0, exitCode)

        val commit = ProcessBuilder("git", "-C", workspace.toString(), "rev-parse", "HEAD")
            .redirectErrorStream(true)
            .start()
            .inputStream
            .bufferedReader()
            .readText()
            .trim()

        val manifest = ManifestIO.read(IndexPathResolver(workspace).resolveManifest(commit))
        assertEquals("bazel-query", manifest.topology)
        assertEquals(false, manifest.includeDeps)
    }

    private fun createGitWorkspace(): java.nio.file.Path {
        val workspace = createTempDirectory("index-cmd-test-")
        tempDirs.add(workspace)

        val fixtureRoot = Path("src/test/resources/fixtures/bazel")
        copyRecursively(fixtureRoot, workspace)

        runGit(workspace, "init")
        runGit(workspace, "config", "user.email", "test@example.com")
        runGit(workspace, "config", "user.name", "Test User")
        runGit(workspace, "add", ".")
        runGit(workspace, "commit", "-m", "fixture")

        return workspace
    }

    private fun copyRecursively(source: java.nio.file.Path, target: java.nio.file.Path) {
        Files.walk(source).forEach { path ->
            val relative = source.relativize(path)
            val dest = target.resolve(relative)
            if (Files.isDirectory(path)) {
                Files.createDirectories(dest)
            } else {
                Files.createDirectories(dest.parent)
                Files.copy(path, dest)
            }
        }
    }

    private fun runGit(workspace: java.nio.file.Path, vararg args: String) {
        val process = ProcessBuilder(*listOf("git", "-C", workspace.toString(), *args).toTypedArray())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }
}
