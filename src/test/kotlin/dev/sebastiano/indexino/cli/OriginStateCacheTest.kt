package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.engine.ManifestOriginResolver
import dev.sebastiano.indexino.engine.OriginResolution
import dev.sebastiano.indexino.engine.OriginStateCache
import dev.sebastiano.indexino.producer.IndexedSource
import dev.sebastiano.indexino.producer.SourceContentSnapshot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class OriginStateCacheTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `unchanged origin reuses persisted state without a full resolution`() {
        val workspace = committedWorkspace()
        val sources = sources(workspace)
        val first = resolve(workspace, sources, newCache())

        val second = resolve(workspace, sources, newCache())

        assertEquals(0, first.cached)
        assertEquals(1, second.cached)
        assertEquals(first.origins, second.origins)
        assertEquals(first.origins, resolve(workspace, sources, cache = null).origins)
        assertTrue(second.states.isNotEmpty())
        assertTrue(second.origins.all { it.revision?.length == 40 }, "${second.origins}")
    }

    @Test
    fun `a tracked edit invalidates the cached origin state`() {
        val workspace = committedWorkspace()
        val sources = sources(workspace)
        val first = resolve(workspace, sources, newCache())
        assertEquals(1, resolve(workspace, sources, newCache()).cached)

        Files.writeString(workspace.resolve("src/A.java"), "class A { int edited; }\n")
        val edited = resolve(workspace, sources, newCache())

        assertEquals(0, edited.cached)
        assertTrue(edited.origins.single().dirty, "${edited.origins}")
        assertNotEquals(first.origins, edited.origins)
    }

    @Test
    fun `an untracked file added outside the source closure invalidates the cache`() {
        val workspace = committedWorkspace()
        val sources = sources(workspace)
        val clean = resolve(workspace, sources, newCache())

        Files.writeString(workspace.resolve("notes.txt"), "new\n")
        val dirty = resolve(workspace, sources, newCache())

        assertEquals(0, dirty.cached, "Untracked files must be observed without Git")
        assertTrue(dirty.origins.single().dirty, "${dirty.origins}")
        assertNotEquals(
            clean.origins.single().stateFingerprint,
            dirty.origins.single().stateFingerprint,
        )
    }

    @Test
    fun `a non-source tracked edit invalidates the cache and stays clean`() {
        val workspace = committedWorkspace()
        val sources = sources(workspace)
        resolve(workspace, sources, newCache())
        assertEquals(1, resolve(workspace, sources, newCache()).cached)

        Files.writeString(workspace.resolve("README.md"), "edited readme\n")
        val edited = resolve(workspace, sources, newCache())

        assertEquals(0, edited.cached)
        assertTrue(edited.origins.single().dirty, "${edited.origins}")
    }

    @Test
    fun `a new commit invalidates the cache and updates the revision`() {
        val workspace = committedWorkspace()
        val sources = sources(workspace)
        val initial = resolve(workspace, sources, newCache())
        assertEquals(1, resolve(workspace, sources, newCache()).cached)

        git(workspace, "commit", "--allow-empty", "-m", "advance")
        val advanced = resolve(workspace, sources, newCache())

        assertEquals(0, advanced.cached)
        assertNotEquals(initial.origins.single().revision, advanced.origins.single().revision)
        assertEquals(advanced.origins, resolve(workspace, sources, cache = null).origins)
    }

    @Test
    fun `packing refs invalidates once and then serves the cache`() {
        val workspace = committedWorkspace()
        val sources = sources(workspace)
        val initial = resolve(workspace, sources, newCache())
        assertEquals(1, resolve(workspace, sources, newCache()).cached)

        git(workspace, "pack-refs", "--all")
        val packed = resolve(workspace, sources, newCache())

        assertEquals(0, packed.cached)
        assertEquals(initial.origins, packed.origins)
        assertEquals(1, resolve(workspace, sources, newCache()).cached)
    }

    @Test
    fun `a staged index change invalidates the cache`() {
        val workspace = committedWorkspace()
        val sources = sources(workspace)
        resolve(workspace, sources, newCache())
        assertEquals(1, resolve(workspace, sources, newCache()).cached)

        Files.writeString(workspace.resolve("README.md"), "staged\n")
        git(workspace, "add", "README.md")
        Files.writeString(workspace.resolve("README.md"), "readme\n")
        val staged = resolve(workspace, sources, newCache())

        assertEquals(0, staged.cached)
        assertTrue(staged.origins.single().dirty, "${staged.origins}")
    }

    @Test
    fun `a git config change invalidates the cache`() {
        val workspace = committedWorkspace()
        val sources = sources(workspace)
        resolve(workspace, sources, newCache())
        assertEquals(1, resolve(workspace, sources, newCache()).cached)

        git(workspace, "config", "core.quotepath", "false")
        val reconfigured = resolve(workspace, sources, newCache())

        assertEquals(0, reconfigured.cached)
        assertEquals(resolve(workspace, sources, cache = null).origins, reconfigured.origins)
    }

    @Test
    fun `an expected revision mismatch invalidates the cache`() {
        val workspace = committedWorkspace()
        val sources = sources(workspace)
        resolve(workspace, sources, newCache(), externalExpectedRevision = null)
        assertEquals(
            1,
            resolve(workspace, sources, newCache(), externalExpectedRevision = null).cached,
        )

        val mismatched =
            resolve(
                workspace,
                sources,
                newCache(),
                externalExpectedRevision = "0123456789012345678901234567890123456789",
            )
        assertEquals(0, mismatched.cached)
    }

    @Test
    fun `a corrupt entry falls back to a full resolution`() {
        val workspace = committedWorkspace()
        val sources = sources(workspace)
        resolve(workspace, sources, newCache())
        Files.walk(tempDir.resolve("origin-cache")).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { it.writeText("{garbage") }
        }

        val recovered = resolve(workspace, sources, newCache())
        assertEquals(0, recovered.cached)
        assertEquals(recovered.origins, resolve(workspace, sources, cache = null).origins)
    }

    @Test
    fun `a linked worktree reuses its cached state`() {
        val primary = committedWorkspace()
        val linked = tempDir.resolve("linked")
        git(primary, "worktree", "add", linked.toString(), "-b", "linked-branch")
        val canonicalLinked = linked.toRealPath()
        ageWorkspace(canonicalLinked)
        val sources = listOf(IndexedSource("workspace", canonicalLinked, "src/A.java"))

        val first = resolve(canonicalLinked, sources, newCache())
        val second = resolve(canonicalLinked, sources, newCache())

        assertEquals(0, first.cached)
        assertEquals(1, second.cached)
        assertEquals(first.origins, second.origins)
    }

    private fun resolve(
        workspace: Path,
        sources: List<IndexedSource>,
        cache: OriginStateCache?,
        externalExpectedRevision: String? = null,
    ): OriginResolution =
        ManifestOriginResolver.resolveWithState(
            workspace = workspace,
            sources = sources,
            externalOriginMetadata =
                if (externalExpectedRevision == null) emptyMap()
                else
                    mapOf(
                        workspace.toRealPath() to
                            (sources.first().originId to externalExpectedRevision)
                    ),
            sourceSnapshot = SourceContentSnapshot.capture(sources),
            stateCache = cache,
        )

    private fun newCache(): OriginStateCache =
        OriginStateCache.open(tempDir.resolve("origin-cache"), tempDir)

    private fun sources(workspace: Path): List<IndexedSource> =
        listOf(IndexedSource("workspace", workspace.toRealPath(), "src/A.java"))

    private fun committedWorkspace(name: String = "workspace"): Path {
        val workspace = Files.createDirectories(tempDir.resolve(name)).toRealPath()
        Files.createDirectories(workspace.resolve("src"))
        Files.writeString(workspace.resolve("src/A.java"), "class A {}\n")
        Files.writeString(workspace.resolve("README.md"), "readme\n")
        git(workspace, "init")
        git(workspace, "config", "user.email", "test@example.invalid")
        git(workspace, "config", "user.name", "Indexino Test")
        git(workspace, "add", ".")
        git(workspace, "commit", "-m", "base")
        ageWorkspace(workspace)
        return workspace
    }

    /** Keep fixture timestamps outside the production racy window. */
    private fun ageWorkspace(workspace: Path) {
        val past = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000)
        Files.walk(workspace).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { Files.setLastModifiedTime(it, past) }
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
