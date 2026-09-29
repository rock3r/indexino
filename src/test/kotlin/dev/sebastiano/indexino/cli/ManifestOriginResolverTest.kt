package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.core.manifest.IndexManifestOrigin
import dev.sebastiano.indexino.producer.IndexedSource
import dev.sebastiano.indexino.producer.SourceContentSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Pins origin provenance so the Git work can be reorganized without changing manifests. */
class ManifestOriginResolverTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `dirty and fingerprints follow tracked index untracked and transitional state`() {
        val workspace = Files.createDirectories(tempDir.resolve("workspace"))
        val source = workspace.resolve("src/A.java")
        Files.createDirectories(source.parent)
        Files.writeString(source, "class A {}\n")
        Files.writeString(workspace.resolve("README.md"), "readme\n")
        git(workspace, "init")
        git(workspace, "config", "user.email", "test@example.invalid")
        git(workspace, "config", "user.name", "Indexino Test")
        git(workspace, "add", ".")
        git(workspace, "commit", "-m", "base")
        val clean = resolve(workspace)
        assertFalse(clean.dirty, "$clean")

        Files.createDirectories(workspace.resolve(".indexino"))
        Files.writeString(workspace.resolve(".indexino/state"), "transitional\n")
        val transitional = resolve(workspace)
        assertFalse(transitional.dirty, "Transitional index state is not a working-tree edit")
        assertEquals(clean.stateFingerprint, transitional.stateFingerprint)

        Files.writeString(workspace.resolve("notes.txt"), "untracked\n")
        val untracked = resolve(workspace)
        assertTrue(untracked.dirty, "$untracked")
        assertNotEquals(clean.stateFingerprint, untracked.stateFingerprint)
        Files.delete(workspace.resolve("notes.txt"))

        // Index differs from HEAD while the working tree matches HEAD again.
        Files.writeString(workspace.resolve("README.md"), "staged\n")
        git(workspace, "add", "README.md")
        Files.writeString(workspace.resolve("README.md"), "readme\n")
        val stagedOnly = resolve(workspace)
        assertTrue(stagedOnly.dirty, "A staged index change is dirty even with a clean diff")
        assertEquals(clean.stateFingerprint, stagedOnly.stateFingerprint)
        git(workspace, "reset", "-q", "README.md")

        Files.writeString(workspace.resolve("README.md"), "edited\n")
        val modified = resolve(workspace)
        assertTrue(modified.dirty, "$modified")
        assertNotEquals(clean.stateFingerprint, modified.stateFingerprint)
        assertEquals(clean.revision, modified.revision)
    }

    @Test
    fun `nested repositories resolve as independent origins`() {
        val workspace = Files.createDirectories(tempDir.resolve("outer"))
        val nested = Files.createDirectories(workspace.resolve("nested"))
        Files.writeString(workspace.resolve("A.java"), "class A {}\n")
        Files.writeString(nested.resolve("B.java"), "class B {}\n")
        Files.writeString(workspace.resolve(".gitignore"), "/nested/\n")
        for (repository in listOf(workspace, nested)) {
            git(repository, "init")
            git(repository, "config", "user.email", "test@example.invalid")
            git(repository, "config", "user.name", "Indexino Test")
            git(repository, "add", ".")
            git(repository, "commit", "-m", "base")
        }
        Files.writeString(nested.resolve("untracked.txt"), "new\n")
        val sources =
            listOf(
                IndexedSource("workspace", workspace.toRealPath(), "A.java"),
                IndexedSource("git:nested", nested.toRealPath(), "B.java"),
            )
        val origins = ManifestOriginResolver.resolve(workspace, sources, emptyMap())
        val capturedOrigins =
            ManifestOriginResolver.resolveWithState(
                    workspace,
                    sources,
                    emptyMap(),
                    sourceSnapshot = SourceContentSnapshot.capture(sources),
                )
                .origins
        assertEquals(origins, capturedOrigins)
        assertEquals(listOf("git:nested", "workspace"), origins.map { it.originId })
        assertTrue(origins.single { it.originId == "git:nested" }.dirty, "$origins")
        assertFalse(origins.single { it.originId == "workspace" }.dirty, "$origins")
        assertTrue(origins.all { it.revision?.length == 40 }, "$origins")
    }

    @Test
    fun `covered known-source edits update origin state incrementally and exactly`() {
        val workspace = committedWorkspace()
        // Staged-only index state, a dirty tracked source and an untracked known source.
        Files.writeString(workspace.resolve("README.md"), "staged\n")
        git(workspace, "add", "README.md")
        Files.writeString(workspace.resolve("README.md"), "readme\n")
        Files.writeString(workspace.resolve("src/A.java"), "class A { int dirty; }\n")
        Files.writeString(workspace.resolve("src/New.java"), "class New {}\n")
        Files.writeString(workspace.resolve("notes.txt"), "untracked non-source\n")
        val sources = sources(workspace, "src/A.java", "src/B.kt", "src/New.java")
        var states = resolveWithState(workspace, sources).also { assertEquals(0, it.incremental) }

        for ((path, content) in
            listOf(
                "src/B.kt" to "class B { val edited = 1 }\n",
                "src/New.java" to "class New { int edited; }\n",
                "src/A.java" to "class A {}\n",
                "src/B.kt" to "class B\n",
            )) {
            Files.writeString(workspace.resolve(path), content)
            val hinted = sources.filter { it.path == path }
            val incremental =
                ManifestOriginResolver.resolveWithState(
                    workspace,
                    sources,
                    emptyMap(),
                    sourceSnapshot = SourceContentSnapshot.capture(sources),
                    hint = OriginIncrementalHint(states.states, hinted, workspace.toRealPath()),
                )
            val full = resolveWithState(workspace, sources)
            assertEquals(full.origins, incremental.origins, "after editing $path")
            assertEquals(1, incremental.incremental, "after editing $path")
            states = incremental
        }
        assertTrue(states.origins.single().dirty)
    }

    @Test
    fun `origin diagnostics separate source hashing from incremental git reads`() {
        val workspace = committedWorkspace("timed-origin")
        val sources = sources(workspace, "src/A.java", "src/B.kt")
        val captured = resolveWithState(workspace, sources)
        Files.writeString(workspace.resolve("src/B.kt"), "class B { val edited = 1 }\n")
        val lines = mutableListOf<String>()
        val updated =
            ManifestOriginResolver.resolveWithState(
                workspace,
                sources,
                emptyMap(),
                hint =
                    OriginIncrementalHint(
                        captured.states,
                        listOf(sources.last()),
                        workspace.toRealPath(),
                    ),
                progress = lines::add,
            )
        assertEquals(1, updated.incremental)
        assertEquals(resolveWithState(workspace, sources).origins, updated.origins)
        assertEquals(
            listOf("source-fingerprint", "git-state", "manifest-fingerprint"),
            lines.map { line ->
                val match =
                    Regex("index origin=workspace phase=([a-z-]+) durationMillis=\\d+")
                        .matchEntire(line)
                assertTrue(match != null, line)
                match.groupValues[1]
            },
        )
    }

    @Test
    fun `git state completes while the same origin source fingerprint is blocked`() {
        val workspace = committedWorkspace("overlapping-origin")
        val sources = sources(workspace, "src/A.java", "src/B.kt")
        val snapshot = SourceContentSnapshot.capture(sources)
        val expected =
            ManifestOriginResolver.resolveWithState(
                    workspace,
                    sources,
                    emptyMap(),
                    sourceSnapshot = snapshot,
                )
                .origins
        val fingerprintStarted = CountDownLatch(1)
        val releaseFingerprint = CountDownLatch(1)
        val gitFinished = CountDownLatch(1)
        val result = CompletableFuture.supplyAsync {
            ManifestOriginResolver.resolveWithState(
                workspace,
                sources,
                emptyMap(),
                sourceSnapshot = snapshot,
                onPhaseForTests = { origin, phase, completed ->
                    if (origin == "workspace" && phase == "source-fingerprint" && !completed) {
                        fingerprintStarted.countDown()
                        check(releaseFingerprint.await(15, TimeUnit.SECONDS))
                    }
                    if (origin == "workspace" && phase == "git-state" && completed) {
                        gitFinished.countDown()
                    }
                },
            )
        }
        val overlapped =
            try {
                assertTrue(fingerprintStarted.await(5, TimeUnit.SECONDS))
                gitFinished.await(3, TimeUnit.SECONDS)
            } finally {
                releaseFingerprint.countDown()
                result.get(15, TimeUnit.SECONDS)
            }
        assertTrue(overlapped, "Git state must finish before the fingerprint is unblocked")
        assertEquals(expected, result.get(15, TimeUnit.SECONDS).origins)
    }

    @Test
    fun `failed fingerprint waits for its in-flight git state`() {
        val workspace = committedWorkspace("failed-fingerprint")
        val sources = sources(workspace, "src/A.java")
        val releaseGit = CountDownLatch(1)
        val fingerprintFailed = CountDownLatch(1)
        val failure = IllegalStateException("fingerprint failed")
        val result = CompletableFuture.supplyAsync {
            ManifestOriginResolver.resolveWithState(
                workspace,
                sources,
                emptyMap(),
                sourceSnapshot = SourceContentSnapshot.capture(sources),
                onPhaseForTests = { origin, phase, completed ->
                    if (origin == "workspace" && phase == "git-state" && !completed) {
                        check(releaseGit.await(15, TimeUnit.SECONDS))
                    }
                    if (origin == "workspace" && phase == "source-fingerprint" && !completed) {
                        fingerprintFailed.countDown()
                        throw failure
                    }
                },
            )
        }
        try {
            assertTrue(fingerprintFailed.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { result.get(100, TimeUnit.MILLISECONDS) }
        } finally {
            releaseGit.countDown()
        }
        assertSame(
            failure,
            assertFailsWith<ExecutionException> { result.get(15, TimeUnit.SECONDS) }.cause,
        )
    }

    @Test
    fun `git state the watcher cannot see forces a full origin recomputation`() {
        val scenarios: List<Pair<String, (Path) -> Unit>> =
            listOf(
                "index" to { workspace -> git(workspace, "add", "src/A.java") },
                "head" to
                    { workspace ->
                        git(workspace, "commit", "--allow-empty", "-m", "empty")
                        Unit
                    },
                "exclude" to
                    { workspace ->
                        Files.writeString(workspace.resolve(".git/info/exclude"), "notes.txt\n")
                    },
                "config" to { workspace -> git(workspace, "config", "diff.noprefix", "true") },
            )
        for ((name, change) in scenarios) {
            val workspace = committedWorkspace(name)
            Files.writeString(workspace.resolve("src/A.java"), "class A { int dirty; }\n")
            Files.writeString(workspace.resolve("notes.txt"), "untracked\n")
            val sources = sources(workspace, "src/A.java", "src/B.kt")
            val captured = resolveWithState(workspace, sources)
            change(workspace)
            Files.writeString(workspace.resolve("src/B.kt"), "class B { val edited = 1 }\n")
            val hint =
                OriginIncrementalHint(
                    captured.states,
                    sources.filter { it.path == "src/B.kt" },
                    workspace.toRealPath(),
                )
            val incremental = resolveWithState(workspace, sources, hint)
            assertEquals(0, incremental.incremental, name)
            assertEquals(resolveWithState(workspace, sources).origins, incremental.origins, name)
        }
    }

    @Test
    fun `incremental origins need recursive coverage and untracked nested roots`() {
        val workspace = committedWorkspace("coverage")
        val sources = sources(workspace, "src/A.java", "src/B.kt")
        val captured = resolveWithState(workspace, sources)
        Files.writeString(workspace.resolve("src/B.kt"), "class B { val edited = 1 }\n")
        val elsewhere = Files.createDirectories(tempDir.resolve("elsewhere")).toRealPath()
        val uncovered =
            resolveWithState(
                workspace,
                sources,
                OriginIncrementalHint(
                    captured.states,
                    sources.filter { it.path == "src/B.kt" },
                    elsewhere,
                ),
            )
        assertEquals(0, uncovered.incremental)

        // A nested repository whose files the outer repository also tracks.
        val nested = Files.createDirectories(workspace.resolve("vendored"))
        Files.writeString(nested.resolve("C.java"), "class C {}\n")
        git(workspace, "add", "vendored/C.java")
        git(workspace, "commit", "-m", "vendored")
        initRepository(nested)
        val nestedSource = IndexedSource("git:vendored", nested.toRealPath(), "C.java")
        val all = sources + nestedSource
        val before = resolveWithState(workspace, all)
        Files.writeString(nested.resolve("C.java"), "class C { int edited; }\n")
        val incremental =
            resolveWithState(
                workspace,
                all,
                OriginIncrementalHint(before.states, listOf(nestedSource), workspace.toRealPath()),
            )
        assertEquals(1, incremental.incremental, "Only the nested origin is provably local")
        assertEquals(resolveWithState(workspace, all).origins, incremental.origins)
    }

    private fun committedWorkspace(name: String = "incremental"): Path {
        val workspace = Files.createDirectories(tempDir.resolve(name))
        Files.createDirectories(workspace.resolve("src"))
        Files.writeString(workspace.resolve("src/A.java"), "class A {}\n")
        Files.writeString(workspace.resolve("src/B.kt"), "class B\n")
        Files.writeString(workspace.resolve("README.md"), "readme\n")
        initRepository(workspace)
        return workspace
    }

    private fun initRepository(repository: Path) {
        git(repository, "init")
        git(repository, "config", "user.email", "test@example.invalid")
        git(repository, "config", "user.name", "Indexino Test")
        git(repository, "add", ".")
        git(repository, "commit", "-m", "base")
    }

    private fun sources(workspace: Path, vararg paths: String): List<IndexedSource> = paths.map {
        IndexedSource("workspace", workspace.toRealPath(), it)
    }

    private fun resolveWithState(
        workspace: Path,
        sources: List<IndexedSource>,
        hint: OriginIncrementalHint? = null,
    ): OriginResolution =
        ManifestOriginResolver.resolveWithState(workspace, sources, emptyMap(), hint = hint)

    private fun resolve(workspace: Path): IndexManifestOrigin =
        ManifestOriginResolver.resolve(
                workspace,
                listOf(IndexedSource("workspace", workspace.toRealPath(), "src/A.java")),
                emptyMap(),
            )
            .single()

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
