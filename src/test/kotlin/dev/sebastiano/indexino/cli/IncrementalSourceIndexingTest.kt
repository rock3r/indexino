package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.core.manifest.ManifestIO
import dev.sebastiano.indexino.core.path.IndexPathResolver
import dev.sebastiano.indexino.core.record.FileHashRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.producer.FileHashProducer
import dev.sebastiano.indexino.topology.BuildSystem
import dev.sebastiano.indexino.topology.TopologyRequest
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncrementalSourceIndexingTest {
    private val tempDirs = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `concurrent edit cannot publish a hash newer than the analyzed facts`() {
        val workspace = createWorkspace()
        val request = TopologyRequest(buildSystem = BuildSystem.GRADLE, gradleModule = ":app")
        val changedSource = workspace.resolve("app/src/main/java/sample/Changed.java")
        val analyzedContent = "package sample; public class Changed { int analyzed; }"
        val concurrentContent = "package sample; public class Changed { int concurrent; }"
        Files.writeString(changedSource, analyzedContent)
        var edited = false

        assertEquals(
            0,
            IndexCommand()
                .runIndexedBuild(
                    workspace,
                    request,
                    emptyList(),
                    progress = { message ->
                        if (!edited && message == "FileHashProducer") {
                            Files.writeString(changedSource, concurrentContent)
                            edited = true
                        }
                    },
                ),
        )

        val commit = runGit(workspace, "rev-parse", "HEAD").trim()
        val resolver = IndexPathResolver(workspace)
        val firstStore =
            XodusCodeIndexStore.open(resolver.resolveBaseStore(commit), readOnly = true)
        try {
            val symbols =
                firstStore
                    .prefixScan("sym:")
                    .map { it.second }
                    .filterIsInstance<SymbolRecord>()
                    .toList()
            val hash =
                firstStore
                    .prefixScan("file:")
                    .map { it.second }
                    .filterIsInstance<FileHashRecord>()
                    .single { it.relativePath.endsWith("Changed.java") }
            assertTrue(symbols.any { it.fqn == "sample.Changed#analyzed" })
            assertFalse(symbols.any { it.fqn == "sample.Changed#concurrent" })
            assertEquals(FileHashProducer.contentHash(analyzedContent), hash.contentHash)
        } finally {
            firstStore.close()
        }

        assertEquals(0, IndexCommand().runIndexedBuild(workspace, request, emptyList()))
        val reopenedStore =
            XodusCodeIndexStore.open(resolver.resolveBaseStore(commit), readOnly = true)
        try {
            val symbols =
                reopenedStore
                    .prefixScan("sym:")
                    .map { it.second }
                    .filterIsInstance<SymbolRecord>()
                    .toList()
            assertFalse(symbols.any { it.fqn == "sample.Changed#analyzed" })
            assertTrue(symbols.any { it.fqn == "sample.Changed#concurrent" })
        } finally {
            reopenedStore.close()
        }
    }

    @Test
    fun `content edit reindexes only the changed source file`() {
        val workspace = createWorkspace()
        val request = TopologyRequest(buildSystem = BuildSystem.GRADLE, gradleModule = ":app")
        val command = IndexCommand()
        assertEquals(0, command.runIndexedBuild(workspace, request, emptyList()))

        Files.writeString(
            workspace.resolve("app/src/main/java/sample/Changed.java"),
            "package sample; public class Changed { int updated; }",
        )
        val progress = mutableListOf<String>()
        assertEquals(
            0,
            command.runIndexedBuild(workspace, request, emptyList(), progress = progress::add),
        )

        assertTrue(progress.any { it.endsWith("Changed.java") }, progress.toString())
        assertFalse(progress.any { it.endsWith("Untouched.java") }, progress.toString())

        val commit = runGit(workspace, "rev-parse", "HEAD").trim()
        val store =
            XodusCodeIndexStore.open(
                IndexPathResolver(workspace).resolveBaseStore(commit),
                readOnly = true,
            )
        try {
            val symbols =
                store.prefixScan("sym:").map { it.second }.filterIsInstance<SymbolRecord>().toList()
            assertTrue(symbols.any { it.fqn == "sample.Changed#updated" })
            assertTrue(symbols.any { it.fqn == "sample.Untouched" })
        } finally {
            store.close()
        }
    }

    @Test
    fun `rename removes stale declarations and indexes the new file`() {
        val workspace = createWorkspace()
        val request = TopologyRequest(buildSystem = BuildSystem.GRADLE, gradleModule = ":app")
        val command = IndexCommand()
        assertEquals(0, command.runIndexedBuild(workspace, request, emptyList()))

        val oldPath = workspace.resolve("app/src/main/java/sample/Changed.java")
        val newPath = workspace.resolve("app/src/main/java/sample/Renamed.java")
        Files.move(oldPath, newPath)
        Files.writeString(newPath, "package sample; public class Renamed {}")
        assertEquals(0, command.runIndexedBuild(workspace, request, emptyList()))

        val commit = runGit(workspace, "rev-parse", "HEAD").trim()
        val store =
            XodusCodeIndexStore.open(
                IndexPathResolver(workspace).resolveBaseStore(commit),
                readOnly = true,
            )
        try {
            val symbols =
                store.prefixScan("sym:").map { it.second }.filterIsInstance<SymbolRecord>().toList()
            assertFalse(symbols.any { it.fqn == "sample.Changed" })
            assertTrue(symbols.any { it.fqn == "sample.Renamed" })
            assertTrue(symbols.any { it.fqn == "sample.Untouched" })
        } finally {
            store.close()
        }
    }

    @Test
    fun `indexer version change forces all source producers to rebuild`() {
        val workspace = createWorkspace()
        val request = TopologyRequest(buildSystem = BuildSystem.GRADLE, gradleModule = ":app")
        val command = IndexCommand()
        assertEquals(0, command.runIndexedBuild(workspace, request, emptyList()))
        val commit = runGit(workspace, "rev-parse", "HEAD").trim()
        val resolver = IndexPathResolver(workspace)
        val manifestPath = resolver.resolveManifest(commit)
        ManifestIO.write(
            manifestPath,
            ManifestIO.read(manifestPath).copy(indexerVersion = "0.1.0-SNAPSHOT"),
        )
        val writableStore = XodusCodeIndexStore.open(resolver.resolveBaseStore(commit))
        try {
            writableStore
                .prefixScan("sym:")
                .map { it.first }
                .toList()
                .forEach(writableStore::delete)
        } finally {
            writableStore.close()
        }

        val progress = mutableListOf<String>()
        assertEquals(
            0,
            command.runIndexedBuild(workspace, request, emptyList(), progress = progress::add),
        )

        assertTrue(progress.any { it.endsWith("Changed.java") }, progress.toString())
        assertTrue(progress.any { it.endsWith("Untouched.java") }, progress.toString())
        val store = XodusCodeIndexStore.open(resolver.resolveBaseStore(commit), readOnly = true)
        try {
            val symbols =
                store.prefixScan("sym:").map { it.second }.filterIsInstance<SymbolRecord>().toList()
            assertTrue(symbols.any { it.fqn == "sample.Changed" })
            assertTrue(symbols.any { it.fqn == "sample.Untouched" })
        } finally {
            store.close()
        }
    }

    @Test
    fun `basic fact schema change forces all source producers to rebuild`() {
        val workspace = createWorkspace()
        val request = TopologyRequest(buildSystem = BuildSystem.GRADLE, gradleModule = ":app")
        val command = IndexCommand()
        assertEquals(0, command.runIndexedBuild(workspace, request, emptyList()))
        val commit = runGit(workspace, "rev-parse", "HEAD").trim()
        val manifestPath = IndexPathResolver(workspace).resolveManifest(commit)
        val currentManifest = Files.readString(manifestPath)
        val oldSchemaManifest =
            if ("\"basicFactSchemaVersion\"" in currentManifest) {
                currentManifest.replace(
                    Regex("\"basicFactSchemaVersion\":\\d+"),
                    "\"basicFactSchemaVersion\":1",
                )
            } else {
                currentManifest.dropLast(1) + ",\"basicFactSchemaVersion\":1}"
            }
        Files.writeString(manifestPath, oldSchemaManifest)

        val progress = mutableListOf<String>()
        assertEquals(
            0,
            command.runIndexedBuild(workspace, request, emptyList(), progress = progress::add),
        )

        assertTrue(progress.any { it.endsWith("Changed.java") }, progress.toString())
        assertTrue(progress.any { it.endsWith("Untouched.java") }, progress.toString())
    }

    private fun createWorkspace(): java.nio.file.Path {
        val workspace = createTempDirectory("incremental-index-")
        tempDirs.add(workspace)
        Files.writeString(workspace.resolve("settings.gradle.kts"), "include(\":app\")")
        val sourceRoot = workspace.resolve("app/src/main/java/sample")
        Files.createDirectories(sourceRoot)
        Files.writeString(
            sourceRoot.resolve("Changed.java"),
            "package sample; public class Changed {}",
        )
        Files.writeString(
            sourceRoot.resolve("Untouched.java"),
            "package sample; public class Untouched {}",
        )
        runGit(workspace, "init")
        runGit(workspace, "config", "user.email", "test@example.com")
        runGit(workspace, "config", "user.name", "Test User")
        runGit(workspace, "add", ".")
        runGit(workspace, "commit", "-m", "fixture")
        return workspace
    }

    private fun runGit(workspace: java.nio.file.Path, vararg args: String): String {
        val process =
            ProcessBuilder(
                    *listOf("git", "-C", workspace.toString(), "-c", "commit.gpgsign=false", *args)
                        .toTypedArray()
                )
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output
    }
}
