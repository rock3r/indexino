package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.core.path.IndexPathResolver
import dev.sebastiano.indexino.core.record.ResourceDefinitionRecord
import dev.sebastiano.indexino.core.record.ResourceUsageRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.producer.IndexedSource
import dev.sebastiano.indexino.producer.JsonlIndexBuildProgressReporter
import dev.sebastiano.indexino.topology.BuildSystem
import dev.sebastiano.indexino.topology.SourceOriginResolver
import dev.sebastiano.indexino.topology.TopologyRequest
import dev.sebastiano.indexino.topology.TopologyResult
import dev.sebastiano.indexino.topology.bazel.BazelQueryExecutor
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class IndexBuildRunnerTest {
    // One fixture covers rollback, publication ordering, and fresh fallback.
    @Test
    @Suppress("LongMethod")
    fun `changed source builds against its snapshot while origin resolution waits to publish`() {
        val workspace = tempDir.resolve("overlap-workspace")
        val source = workspace.resolve("src/main/java/Panel.java")
        Files.createDirectories(source.parent)
        source.writeText("class Before {}")
        val storeRoot = tempDir.resolve("overlap-store")
        val topology =
            TopologyResult(
                sourceFiles = listOf("src/main/java/Panel.java"),
                topology = "bazel-query",
                includeDeps = false,
                scope = "//:main",
            )
        fun runner(previous: IndexBuildExecution? = null, progress: (String) -> Unit = {}) =
            IndexBuildRunner(
                project = workspace,
                topologyRequest =
                    TopologyRequest(buildSystem = BuildSystem.BAZEL, bazelTarget = "//:main"),
                applications = emptyList(),
                bazelQueryExecutor = null,
                bazelProcessRunner = null,
                progress = progress,
                machineProgress = null,
                storeRootOverride = storeRoot,
                topologyOverride = topology,
                inheritedSources = previous?.sources,
                inheritedSourceHashes = previous?.sourceHashes.orEmpty(),
                inheritedOriginStates = previous?.originStates,
                recursiveWatchRoot = previous?.let { workspace },
                hintedPaths = previous?.let { setOf(source) }.orEmpty(),
                captureOriginStates = true,
            )
        val initial = runner().runDetailed()
        assertEquals(CliExitCodes.SUCCESS, initial.exitCode)
        val manifestPath =
            IndexPathResolver(workspace, storeRootOverride = storeRoot)
                .resolveManifest(checkNotNull(initial.manifest).commit)
        val previousManifest = manifestPath.readText()
        source.writeText("class After {}")

        val failingOriginStarted = CountDownLatch(1)
        val releaseFailingOrigin = CountDownLatch(1)
        val producerFailed = CountDownLatch(1)
        val failure = IllegalArgumentException("producer stopped")
        val failedBuild = CompletableFuture.supplyAsync {
            assertSame(
                failure,
                assertFailsWith<IllegalArgumentException> {
                    runner(initial) { message ->
                            when {
                                message == "index phase=origin-resolution state=started" -> {
                                    failingOriginStarted.countDown()
                                    check(releaseFailingOrigin.await(15, TimeUnit.SECONDS))
                                }
                                message.startsWith(
                                    "index phase=producer:file-hash state=completed"
                                ) -> {
                                    producerFailed.countDown()
                                    throw failure
                                }
                            }
                        }
                        .runDetailed()
                },
            )
        }
        try {
            assertTrue(failingOriginStarted.await(5, TimeUnit.SECONDS))
            assertTrue(producerFailed.await(10, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { failedBuild.get(100, TimeUnit.MILLISECONDS) }
        } finally {
            releaseFailingOrigin.countDown()
        }
        failedBuild.get(20, TimeUnit.SECONDS)
        assertEquals(previousManifest, manifestPath.readText())
        XodusCodeIndexStore.open(
                IndexPathResolver(workspace, storeRootOverride = storeRoot)
                    .resolveBaseStore(checkNotNull(initial.manifest).commit)
            )
            .use { indexed ->
                assertEquals(
                    setOf("Before"),
                    indexed
                        .prefixScan("sym:")
                        .map { (_, record) -> (record as SymbolRecord).name }
                        .toSet(),
                )
            }

        val originStarted = CountDownLatch(1)
        val releaseOrigin = CountDownLatch(1)
        val storeStarted = CountDownLatch(1)
        val producersFinished = CountDownLatch(1)
        val result = CompletableFuture.supplyAsync {
            runner(initial) { message ->
                    when (message) {
                        "index phase=origin-resolution state=started" -> {
                            originStarted.countDown()
                            check(releaseOrigin.await(15, TimeUnit.SECONDS))
                        }
                        "index phase=store-build state=started" -> storeStarted.countDown()
                        else -> {
                            if (
                                message.startsWith("index phase=producer:file-hash state=completed")
                            )
                                producersFinished.countDown()
                        }
                    }
                }
                .runDetailed()
        }
        try {
            assertTrue(originStarted.await(5, TimeUnit.SECONDS))
            assertTrue(storeStarted.await(3, TimeUnit.SECONDS), "store must overlap origin reads")
            assertTrue(producersFinished.await(10, TimeUnit.SECONDS))
            assertEquals(
                previousManifest,
                manifestPath.readText(),
                "must not publish before origin join",
            )
        } finally {
            releaseOrigin.countDown()
        }
        val execution = result.get(20, TimeUnit.SECONDS)
        assertEquals(CliExitCodes.SUCCESS, execution.exitCode)
        assertEquals(
            initial.manifest.origins.map { it.originId },
            execution.manifest?.origins?.map { it.originId },
        )
        assertTrue(manifestPath.readText() != previousManifest)
        XodusCodeIndexStore.open(
                IndexPathResolver(workspace, storeRootOverride = storeRoot)
                    .resolveBaseStore(checkNotNull(execution.manifest).commit)
            )
            .use { indexed ->
                assertEquals(
                    setOf("After"),
                    indexed
                        .prefixScan("sym:")
                        .map { (_, record) -> (record as SymbolRecord).name }
                        .toSet(),
                )
            }
        val unchangedOriginStarted = CountDownLatch(1)
        val releaseUnchangedOrigin = CountDownLatch(1)
        val unchangedStoreStarted = CountDownLatch(1)
        val unchanged = CompletableFuture.supplyAsync {
            runner(execution) { message ->
                    when (message) {
                        "index phase=origin-resolution state=started" -> {
                            unchangedOriginStarted.countDown()
                            check(releaseUnchangedOrigin.await(15, TimeUnit.SECONDS))
                        }
                        "index phase=store-build state=started" -> unchangedStoreStarted.countDown()
                    }
                }
                .runDetailed()
        }
        try {
            assertTrue(unchangedOriginStarted.await(5, TimeUnit.SECONDS))
            assertFalse(unchangedStoreStarted.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseUnchangedOrigin.countDown()
        }
        assertTrue(unchanged.get(20, TimeUnit.SECONDS).reusedFreshIndex)
    }

    @Test
    fun `hinted refresh inherits unchanged source hashes while updating edited facts`() {
        val workspace = tempDir.resolve("hinted-capture-workspace")
        val sourceRoot = workspace.resolve("src/main/kotlin")
        Files.createDirectories(sourceRoot)
        Files.writeString(sourceRoot.resolve("Unchanged.kt"), "class Unchanged")
        val edited = sourceRoot.resolve("Edited.kt")
        Files.writeString(edited, "class Before")
        val topology =
            TopologyResult(
                sourceFiles = listOf("src/main/kotlin/Unchanged.kt", "src/main/kotlin/Edited.kt"),
                topology = "bazel-query",
                includeDeps = false,
                scope = "//:main",
            )
        val store = tempDir.resolve("hinted-capture-store")
        fun run(previous: IndexBuildExecution? = null): IndexBuildExecution =
            IndexBuildRunner(
                    project = workspace,
                    topologyRequest =
                        TopologyRequest(buildSystem = BuildSystem.BAZEL, bazelTarget = "//:main"),
                    applications = emptyList(),
                    bazelQueryExecutor =
                        BazelQueryExecutor { _, _ -> error("Unexpected Bazel query") },
                    bazelProcessRunner = null,
                    progress = {},
                    machineProgress = null,
                    storeRootOverride = store,
                    topologyOverride = topology,
                    inheritedSourceHashes = previous?.sourceHashes.orEmpty(),
                    hintedPaths = if (previous == null) emptySet() else setOf(edited),
                )
                .runDetailed()
        val first = run()
        assertEquals(CliExitCodes.SUCCESS, first.exitCode)
        Files.writeString(edited, "class After")
        val second = run(first)
        assertEquals(CliExitCodes.SUCCESS, second.exitCode)
        assertEquals(1, second.changes?.changedSources?.size)
        assertEquals(
            first.sourceHashes.entries.single { it.key.path.endsWith("Unchanged.kt") }.value,
            second.sourceHashes.entries.single { it.key.path.endsWith("Unchanged.kt") }.value,
        )
        val commit = dev.sebastiano.indexino.core.git.GitHeadResolver.resolve(workspace)
        XodusCodeIndexStore.open(
                IndexPathResolver(workspace, storeRootOverride = store).resolveBaseStore(commit)
            )
            .use {
                assertEquals(
                    setOf("Unchanged", "After"),
                    it.prefixScan("sym:").map { row -> (row.second as SymbolRecord).name }.toSet(),
                )
            }
    }

    @Test
    fun `known source topology override avoids Bazel while retaining source content checks`() {
        val workspace = tempDir.resolve("bazel-watcher-workspace")
        val source = workspace.resolve("src/main/kotlin/Panel.kt")
        Files.createDirectories(source.parent)
        Files.writeString(source, "class Panel")
        val query = BazelQueryExecutor { _, _ -> error("Bazel should not run for a known edit") }
        val diagnostics = mutableListOf<String>()
        fun run(override: TopologyResult?) =
            IndexBuildRunner(
                    project = workspace,
                    topologyRequest =
                        TopologyRequest(buildSystem = BuildSystem.BAZEL, bazelTarget = "//:main"),
                    applications = emptyList(),
                    bazelQueryExecutor = query,
                    bazelProcessRunner = null,
                    progress = diagnostics::add,
                    machineProgress = null,
                    storeRootOverride = tempDir.resolve("store"),
                    topologyOverride = override,
                )
                .runDetailed()
        val cached =
            TopologyResult(
                sourceFiles = listOf("src/main/kotlin/Panel.kt"),
                topology = "bazel-query",
                includeDeps = false,
                scope = "//:main",
            )
        assertFailsWith<IllegalStateException> { run(null) }
        assertEquals(CliExitCodes.SUCCESS, run(cached).exitCode)
        assertContains(diagnostics, "index topology=reused watcher-source-edit")
        Files.delete(source)
        assertFailsWith<java.nio.file.NoSuchFileException> { run(cached) }
    }

    @Test
    fun `diagnostic callback failure cannot prevent checkpoint and store cleanup`() {
        for (phase in listOf("checkpoint-release", "store-close")) {
            val workspace = tempDir.resolve("cleanup-$phase-workspace")
            val source = workspace.resolve("src/main/kotlin/Panel.kt")
            Files.createDirectories(source.parent)
            Files.writeString(source, "class Panel")
            val storeRoot = tempDir.resolve("cleanup-$phase-store")
            val result =
                IndexBuildRunner(
                        project = workspace,
                        topologyRequest =
                            TopologyRequest(
                                buildSystem = BuildSystem.BAZEL,
                                bazelTarget = "//:main",
                            ),
                        applications = emptyList(),
                        bazelQueryExecutor = null,
                        bazelProcessRunner = null,
                        progress = { message ->
                            if (message == "index phase=$phase state=started")
                                error("Diagnostic sink unavailable")
                        },
                        machineProgress = null,
                        storeRootOverride = storeRoot,
                        topologyOverride =
                            TopologyResult(
                                listOf("src/main/kotlin/Panel.kt"),
                                topology = "bazel-query",
                                includeDeps = false,
                                scope = "//:main",
                            ),
                    )
                    .runDetailed()
            assertEquals(CliExitCodes.SUCCESS, result.exitCode)
            val resolver = IndexPathResolver(workspace, storeRootOverride = storeRoot)
            val commit = checkNotNull(result.manifest).commit
            val manifestPath = resolver.resolveManifest(commit)
            assertTrue(
                Files.notExists(manifestPath.resolveSibling("${manifestPath.fileName}.rollback"))
            )
            XodusCodeIndexStore.open(resolver.resolveBaseStore(commit)).use { indexed ->
                assertTrue(
                    indexed.prefixScan("sym:").any { (_, record) ->
                        (record as SymbolRecord).name == "Panel"
                    }
                )
            }
        }
    }

    @Test
    fun `failed producer restores prior builder and permits retry`() {
        val workspace = tempDir.resolve("rollback-workspace")
        Files.createDirectories(workspace.resolve("src/main/kotlin"))
        workspace.resolve("settings.gradle.kts").writeText("rootProject.name = \"checkpoint\"")
        val source = workspace.resolve("src/main/kotlin/Marker.kt")
        source.writeText("class Before")
        git(workspace, "init")
        git(workspace, "config", "user.email", "test@example.invalid")
        git(workspace, "config", "user.name", "Indexino Test")
        git(workspace, "add", ".")
        git(workspace, "commit", "-m", "checkpoint fixture")
        val commit = git(workspace, "rev-parse", "HEAD").trim()
        val resolver = IndexPathResolver(workspace, storeRootOverride = tempDir.resolve("store"))
        fun runner(failure: IllegalArgumentException? = null) =
            IndexBuildRunner(
                project = workspace,
                topologyRequest =
                    TopologyRequest(buildSystem = BuildSystem.GRADLE, gradleModule = ":"),
                applications = emptyList(),
                bazelQueryExecutor = null,
                bazelProcessRunner = null,
                progress = {},
                machineProgress =
                    JsonlIndexBuildProgressReporter { event ->
                        if (failure != null && "phase_completed" in event && "file-hash" in event)
                            throw failure
                    },
                storeRootOverride = resolver.storeRoot(),
            )
        assertEquals(CliExitCodes.SUCCESS, runner().run())
        val previousManifest = resolver.resolveManifest(commit).readText()
        val previousRecords =
            XodusCodeIndexStore.open(resolver.resolveBaseStore(commit)).use {
                it.prefixScan("").toMap()
            }
        source.writeText("class After")
        val failure = IllegalArgumentException("after producer writes")

        assertSame(failure, assertFailsWith<IllegalArgumentException> { runner(failure).run() })
        assertEquals(previousManifest, resolver.resolveManifest(commit).readText())
        XodusCodeIndexStore.open(resolver.resolveBaseStore(commit)).use {
            assertEquals(previousRecords, it.prefixScan("").toMap())
        }
        assertEquals(CliExitCodes.SUCCESS, runner().run())
        XodusCodeIndexStore.open(resolver.resolveBaseStore(commit)).use {
            assertEquals(
                setOf("After"),
                it.prefixScan("sym:").map { row -> (row.second as SymbolRecord).name }.toSet(),
            )
        }
        // A retained checkpoint must block even the fresh-index fast path.
        XodusCodeIndexStore.open(resolver.resolveBaseStore(commit)).use {
            Files.createDirectory(
                BuildStoreCheckpoint(it, resolver.resolveManifest(commit)).directory
            )
        }
        val pending = assertFailsWith<IllegalStateException> { runner().run() }
        assertContains(pending.message.orEmpty(), "checkpoint")
    }

    @Test
    fun `indexes external included build sources in composite manifest`() {
        val root = tempDir.resolve("included")
        val workspace = root.resolve("app")
        val includedBuild = root.resolve("build-logic")
        Files.createDirectories(workspace.resolve("src/main/kotlin"))
        Files.createDirectories(includedBuild.resolve("src/main/kotlin"))
        Files.writeString(
            workspace.resolve("settings.gradle.kts"),
            "includeBuild(\"../build-logic\")",
        )
        Files.writeString(workspace.resolve("src/main/kotlin/App.kt"), "class App")
        Files.writeString(
            includedBuild.resolve("settings.gradle.kts"),
            "rootProject.name = \"logic\"",
        )
        Files.writeString(
            includedBuild.resolve("src/main/kotlin/Convention.kt"),
            "class Convention",
        )
        Files.createDirectories(includedBuild.resolve("src/main/res/raw"))
        Files.writeString(
            includedBuild.resolve("src/main/res/raw/Preview.java"),
            "class NotCompilationCode {}",
        )
        git(workspace, "init")
        git(workspace, "config", "user.email", "test@example.invalid")
        git(workspace, "config", "user.name", "Indexino Test")
        git(workspace, "add", ".")
        git(workspace, "commit", "-m", "workspace")

        val machineProgress = mutableListOf<String>()
        val diagnosticProgress = mutableListOf<String>()
        val execution =
            IndexBuildRunner(
                    project = workspace,
                    topologyRequest =
                        TopologyRequest(buildSystem = BuildSystem.GRADLE, gradleModule = ":"),
                    applications = emptyList(),
                    bazelQueryExecutor = null,
                    bazelProcessRunner = null,
                    progress = diagnosticProgress::add,
                    machineProgress = JsonlIndexBuildProgressReporter(machineProgress::add),
                    storeRootOverride = tempDir.resolve("store"),
                )
                .runDetailed()

        assertEquals(CliExitCodes.SUCCESS, execution.exitCode)
        for (phase in
            listOf(
                "topology",
                "source-resolution",
                "source-capture",
                "source-preview",
                "origin-resolution",
                "store-build",
                "checkpoint",
                "checkpoint-release",
                "checkpoint-backup-close",
                "checkpoint-delete",
                "store-close",
                "change-detection",
                "producer:java-source",
            )) {
            val started = diagnosticProgress.indexOf("index phase=$phase state=started")
            val completed = diagnosticProgress.indexOfFirst {
                it.matches(Regex("index phase=$phase state=completed durationMillis=\\d+"))
            }
            assertTrue(started >= 0 && completed > started, "$phase: $diagnosticProgress")
        }
        assertContains(diagnosticProgress, "index store=writer")
        assertContains(diagnosticProgress, "index changes changed=3 deleted=0 full=true")
        assertContains(
            machineProgress.first { it.contains("discovery_completed") },
            "\"phaseTotal\":3",
        )
        assertContains(
            machineProgress.first {
                it.contains("\"event\":\"progress\"") &&
                    it.contains("\"phase\":\"source-hash-preview\"")
            },
            "\"phaseTotal\":3",
        )
        assertEquals(3, execution.manifest?.sourceFileCount, "manifest=${execution.manifest}")
        assertEquals(2, execution.manifest?.origins?.size, "manifest=${execution.manifest}")
        assertEquals(false, execution.sources.single { it.path.endsWith("Preview.java") }.isCode)
        assertEquals(2, execution.sources.count { it.isCode })
    }

    @Test
    fun `indexes repo manifest project with manifest identity and revision`() {
        val workspace = tempDir.resolve("repo-workspace")
        Files.createDirectories(workspace.resolve(".repo"))
        Files.createDirectories(workspace.resolve("local/tools/base/src/main/kotlin"))
        Files.writeString(
            workspace.resolve(".repo/manifest.xml"),
            """
            <manifest>
              <project name="platform/tools/base" path="local/tools/base" revision="deadbeef"/>
            </manifest>
            """
                .trimIndent(),
        )
        Files.writeString(
            workspace.resolve("local/tools/base/src/main/kotlin/Base.kt"),
            "class Base",
        )
        git(workspace, "init")
        git(workspace, "config", "user.email", "test@example.invalid")
        git(workspace, "config", "user.name", "Indexino Test")
        git(workspace, "add", ".")
        git(workspace, "commit", "-m", "repo workspace")

        val execution =
            IndexBuildRunner(
                    project = workspace,
                    topologyRequest =
                        TopologyRequest(
                            buildSystem = BuildSystem.REPO,
                            repoManifest = workspace.resolve(".repo/manifest.xml"),
                        ),
                    applications = emptyList(),
                    bazelQueryExecutor = null,
                    bazelProcessRunner = null,
                    progress = {},
                    machineProgress = null,
                    storeRootOverride = tempDir.resolve("store"),
                )
                .runDetailed()

        assertEquals(CliExitCodes.SUCCESS, execution.exitCode)
        assertEquals("repo:platform/tools/base", execution.manifest?.origins?.single()?.originId)
        assertEquals("deadbeef", execution.manifest?.origins?.single()?.expectedRevision)
    }

    @Test
    fun `rejects unavailable repo manifest projects`() {
        val workspace = tempDir.resolve("partial-repo-workspace")
        Files.createDirectories(workspace.resolve(".repo"))
        Files.createDirectories(workspace.resolve("checked-out/src/main/kotlin"))
        Files.writeString(
            workspace.resolve(".repo/manifest.xml"),
            """
            <manifest>
              <project name="checked" path="checked-out" revision="one"/>
              <project name="missing" path="not-synced" revision="two"/>
            </manifest>
            """
                .trimIndent(),
        )
        Files.writeString(
            workspace.resolve("checked-out/src/main/kotlin/Checked.kt"),
            "class Checked",
        )
        git(workspace, "init")
        git(workspace, "config", "user.email", "test@example.invalid")
        git(workspace, "config", "user.name", "Indexino Test")
        git(workspace, "add", ".")
        git(workspace, "commit", "-m", "partial repo workspace")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                IndexBuildRunner(
                        project = workspace,
                        topologyRequest =
                            TopologyRequest(
                                buildSystem = BuildSystem.REPO,
                                repoManifest = workspace.resolve(".repo/manifest.xml"),
                            ),
                        applications = emptyList(),
                        bazelQueryExecutor = null,
                        bazelProcessRunner = null,
                        progress = {},
                        machineProgress = null,
                        storeRootOverride = tempDir.resolve("store"),
                    )
                    .runDetailed()
            }

        assertContains(failure.message.orEmpty(), "repo project mount is unavailable: missing")
    }

    @Test
    fun `manifest records expected revision for Git submodule origin`() {
        val child = tempDir.resolve("child")
        Files.createDirectories(child.resolve("src/main/kotlin"))
        Files.writeString(child.resolve("settings.gradle.kts"), "rootProject.name = \\\"child\\\"")
        Files.writeString(child.resolve("src/main/kotlin/Child.kt"), "class Child")
        git(child, "init")
        git(child, "config", "user.email", "test@example.invalid")
        git(child, "config", "user.name", "Indexino Test")
        git(child, "add", ".")
        git(child, "commit", "-m", "child")
        val childRevision = git(child, "rev-parse", "HEAD").trim()

        val workspace = tempDir.resolve("workspace")
        copyFixture(Path("src/test/resources/gradle-fixtures/multi-module"), workspace)
        workspace.resolve("ui").toFile().deleteRecursively()
        git(workspace, "init")
        git(workspace, "config", "user.email", "test@example.invalid")
        git(workspace, "config", "user.name", "Indexino Test")
        git(workspace, "add", ".")
        git(workspace, "commit", "-m", "root")
        git(
            workspace,
            "-c",
            "protocol.file.allow=always",
            "submodule",
            "add",
            child.toString(),
            "ui",
        )
        git(workspace, "commit", "-m", "add submodule")
        Files.writeString(workspace.resolve("ui/src/main/kotlin/Child.kt"), "class DirtyChild")

        val execution =
            IndexBuildRunner(
                    project = workspace,
                    topologyRequest =
                        TopologyRequest(buildSystem = BuildSystem.GRADLE, gradleModule = ":ui"),
                    applications = emptyList(),
                    bazelQueryExecutor = null,
                    bazelProcessRunner = null,
                    progress = {},
                    machineProgress = null,
                    storeRootOverride = tempDir.resolve("store"),
                )
                .runDetailed()

        assertEquals(CliExitCodes.SUCCESS, execution.exitCode)
        val submoduleOrigin = execution.manifest?.origins?.single { it.originId == "git:ui" }
        assertEquals(childRevision, submoduleOrigin?.expectedRevision)
        assertEquals(true, submoduleOrigin?.dirty)
    }

    @Test
    fun `manifest resolves nested submodule revision from its parent`() {
        val child = tempDir.resolve("child")
        Files.createDirectories(child.resolve("src/main/kotlin"))
        Files.writeString(child.resolve("src/main/kotlin/Child.kt"), "class Child")
        git(child, "init")
        git(child, "config", "user.email", "test@example.invalid")
        git(child, "config", "user.name", "Indexino Test")
        git(child, "add", ".")
        git(child, "commit", "-m", "child")
        val childRevision = git(child, "rev-parse", "HEAD").trim()

        val parent = tempDir.resolve("parent")
        Files.createDirectories(parent)
        git(parent, "init")
        git(parent, "config", "user.email", "test@example.invalid")
        git(parent, "config", "user.name", "Indexino Test")
        git(
            parent,
            "-c",
            "protocol.file.allow=always",
            "submodule",
            "add",
            child.toString(),
            "nested",
        )
        git(parent, "commit", "-m", "add child")

        val workspace = tempDir.resolve("workspace")
        Files.createDirectories(workspace)
        git(workspace, "init")
        git(workspace, "config", "user.email", "test@example.invalid")
        git(workspace, "config", "user.name", "Indexino Test")
        git(
            workspace,
            "-c",
            "protocol.file.allow=always",
            "submodule",
            "add",
            parent.toString(),
            "parent",
        )
        git(workspace, "commit", "-m", "add parent")
        git(
            workspace,
            "-c",
            "protocol.file.allow=always",
            "submodule",
            "update",
            "--init",
            "--recursive",
        )
        val nestedRoot = workspace.resolve("parent/nested")

        val origins =
            ManifestOriginResolver.resolve(
                workspace,
                listOf(
                    IndexedSource(
                        SourceOriginResolver.externalOriginId(nestedRoot),
                        nestedRoot,
                        "src/main/kotlin/Child.kt",
                    )
                ),
                emptyMap(),
            )

        assertEquals(childRevision, origins.single().expectedRevision)
    }

    @Test
    fun `incremental build retains nested origin identity`() {
        val workspace = tempDir.resolve("workspace")
        copyFixture(Path("src/test/resources/gradle-fixtures/multi-module"), workspace)
        git(workspace, "init")
        git(workspace, "config", "user.email", "test@example.invalid")
        git(workspace, "config", "user.name", "Indexino Test")
        git(workspace, "add", ".")
        git(workspace, "commit", "-m", "initial fixture")
        val nested = workspace.resolve("ui")
        git(nested, "init")
        git(nested, "config", "user.email", "test@example.invalid")
        git(nested, "config", "user.name", "Indexino Test")
        git(nested, "add", ".")
        git(nested, "commit", "-m", "nested fixture")
        val runner = {
            IndexBuildRunner(
                project = workspace,
                topologyRequest =
                    TopologyRequest(buildSystem = BuildSystem.GRADLE, gradleModule = ":ui"),
                applications = emptyList(),
                bazelQueryExecutor = null,
                bazelProcessRunner = null,
                progress = {},
                machineProgress = null,
                storeRootOverride = tempDir.resolve("store"),
            )
        }
        val initial = runner().runDetailed()
        assertEquals(CliExitCodes.SUCCESS, initial.exitCode)
        assertEquals(
            setOf("git:ui", "workspace"),
            initial.manifest?.origins?.map { it.originId }?.toSet(),
        )
        val source = nested.resolve("src/main/kotlin/Panel.kt")
        source.writeText(source.readText() + "\nfun nestedChange() = Unit\n")

        val execution = runner().runDetailed()

        assertEquals(CliExitCodes.SUCCESS, execution.exitCode)
        assertEquals(
            setOf("git:ui"),
            execution.changes?.changedSources?.map { it.originId }?.toSet(),
        )
    }

    @Test
    fun `gradle namespace changes refresh resource package identities`() {
        val workspace = tempDir.resolve("resource-namespace")
        Files.createDirectories(workspace.resolve("app/src/main/res/values"))
        Files.createDirectories(workspace.resolve("app/src/main/res/drawable"))
        Files.write(
            workspace.resolve("app/src/main/res/drawable/icon.png"),
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x80.toByte()),
        )
        Files.writeString(
            workspace.resolve("settings.gradle.kts"),
            "rootProject.name = \"resources\"\ninclude(\":app\")\n",
        )
        Files.writeString(
            workspace.resolve("app/build.gradle.kts"),
            "android { namespace = \"com.example.old\" }\n",
        )
        Files.writeString(
            workspace.resolve("app/src/main/res/values/strings.xml"),
            "<resources><string name=\"title\">Hello</string></resources>",
        )
        git(workspace, "init")
        git(workspace, "config", "user.email", "test@example.invalid")
        git(workspace, "config", "user.name", "Indexino Test")
        git(workspace, "add", ".")
        git(workspace, "commit", "-m", "resources")
        val commit = git(workspace, "rev-parse", "HEAD").trim()
        val storeRoot = tempDir.resolve("store")

        fun runIndex(): Int =
            IndexBuildRunner(
                    project = workspace,
                    topologyRequest =
                        TopologyRequest(buildSystem = BuildSystem.GRADLE, gradleModule = ":app"),
                    applications = emptyList(),
                    bazelQueryExecutor = null,
                    bazelProcessRunner = null,
                    progress = {},
                    machineProgress = null,
                    storeRootOverride = storeRoot,
                )
                .runDetailed()
                .exitCode

        assertEquals(CliExitCodes.SUCCESS, runIndex())
        Files.writeString(
            workspace.resolve("app/build.gradle.kts"),
            "android { namespace = \"com.example.new\" }\n",
        )
        assertEquals(CliExitCodes.SUCCESS, runIndex())

        val store =
            XodusCodeIndexStore.open(
                storeRoot.resolve("index").resolve(commit).resolve("base.xodus")
            )
        try {
            val packages =
                store
                    .prefixScan("resdef:")
                    .map { it.second }
                    .filterIsInstance<ResourceDefinitionRecord>()
                    .map { it.packageName }
                    .toSet()
            assertEquals(setOf("com.example.new"), packages)
        } finally {
            store.close()
        }
    }

    @Test
    fun `gradle namespace changes refresh code only R usages`() {
        val workspace = tempDir.resolve("resource-code-only")
        Files.createDirectories(workspace.resolve("app/src/main/kotlin/com/example/app"))
        Files.writeString(
            workspace.resolve("settings.gradle.kts"),
            "rootProject.name = \"resources\"\ninclude(\":app\")\n",
        )
        Files.writeString(
            workspace.resolve("app/build.gradle.kts"),
            "android { namespace = \"com.example.old\" }\n",
        )
        Files.writeString(
            workspace.resolve("app/src/main/kotlin/com/example/app/Screen.kt"),
            "package com.example.app\nclass Screen { val title = R.string.title }\n",
        )
        git(workspace, "init")
        git(workspace, "config", "user.email", "test@example.invalid")
        git(workspace, "config", "user.name", "Indexino Test")
        git(workspace, "add", ".")
        git(workspace, "commit", "-m", "resources")
        val commit = git(workspace, "rev-parse", "HEAD").trim()
        val storeRoot = tempDir.resolve("store")

        fun runIndex(): Int =
            IndexBuildRunner(
                    project = workspace,
                    topologyRequest =
                        TopologyRequest(buildSystem = BuildSystem.GRADLE, gradleModule = ":app"),
                    applications = emptyList(),
                    bazelQueryExecutor = null,
                    bazelProcessRunner = null,
                    progress = {},
                    machineProgress = null,
                    storeRootOverride = storeRoot,
                )
                .runDetailed()
                .exitCode

        assertEquals(CliExitCodes.SUCCESS, runIndex())
        Files.writeString(
            workspace.resolve("app/build.gradle.kts"),
            "android { namespace = \"com.example.new\" }\n",
        )
        assertEquals(CliExitCodes.SUCCESS, runIndex())

        val store =
            XodusCodeIndexStore.open(
                storeRoot.resolve("index").resolve(commit).resolve("base.xodus")
            )
        try {
            val packages =
                store
                    .prefixScan("resuse:")
                    .map { it.second }
                    .filterIsInstance<ResourceUsageRecord>()
                    .map { it.packageName }
                    .toSet()
            assertEquals(setOf("com.example.new"), packages)
        } finally {
            store.close()
        }
    }

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
                    progress = { message ->
                        if (!headAdvanced && message == "index phase=store-build state=started") {
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
