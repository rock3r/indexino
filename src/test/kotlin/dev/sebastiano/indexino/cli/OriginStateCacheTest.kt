package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.engine.ManifestOriginResolver
import dev.sebastiano.indexino.engine.OriginResolution
import dev.sebastiano.indexino.engine.OriginStateCache
import dev.sebastiano.indexino.producer.IndexedSource
import dev.sebastiano.indexino.producer.SourceContentSnapshot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `a configured global excludes file invalidates origin reuse`() {
        val workspace = committedWorkspace()
        val excludes = tempDir.resolve("global-excludes")
        excludes.writeText("notes.txt\n")
        val config = workspace.resolve(".git/config")
        config.writeText(config.readText() + "\n[core]\n\texcludesFile = $excludes\n")
        Files.writeString(workspace.resolve("notes.txt"), "untracked\n")
        ageWorkspace(workspace)
        val sources = sources(workspace)

        val clean = resolve(workspace, sources, newCache())
        assertFalse(clean.origins.single().dirty, "${clean.origins}")
        assertEquals(1, resolve(workspace, sources, newCache()).cached)

        excludes.writeText("")
        val dirty = resolve(workspace, sources, newCache())

        assertEquals(0, dirty.cached)
        assertTrue(dirty.origins.single().dirty, "${dirty.origins}")
    }

    @Test
    fun `default global ignore and attributes files participate in the stamp`() {
        val workspace = committedWorkspace()
        val home = Files.createDirectories(tempDir.resolve("global-home"))
        home.resolve("git").createDirectories()
        val ignore = home.resolve("git/ignore")
        val attributes = home.resolve("git/attributes")
        ignore.writeText("*.tmp\n")
        attributes.writeText("*.kt diff=kotlin\n")
        val cache =
            OriginStateCache.open(
                tempDir.resolve("stamp-cache"),
                tempDir,
                homeOverride = home,
                xdgConfigHomeOverride = home,
            )

        val first = cache.stamp(workspace.toRealPath(), null)
        assertNotNull(first)
        ignore.writeText("*.tmp\n*.log\n")
        val second = cache.stamp(workspace.toRealPath(), null)
        assertNotNull(second)
        assertNotEquals(first.value, second.value)

        attributes.writeText("*.kt diff=java\n")
        val third = cache.stamp(workspace.toRealPath(), null)
        assertNotNull(third)
        assertNotEquals(second.value, third.value)
    }

    @Test
    fun `tilde gitconfig overrides xdg for excludes and attributes`() {
        val workspace = committedWorkspace()
        val home = Files.createDirectories(tempDir.resolve("precedence-home"))
        val xdg = home.resolve(".config")
        xdg.resolve("git").createDirectories()
        val homeExcludes = home.resolve("home-excludes")
        val xdgExcludes = xdg.resolve("xdg-excludes")
        homeExcludes.writeText("home.txt\n")
        xdgExcludes.writeText("xdg.txt\n")
        home.resolve(".gitconfig").writeText("[core]\n\texcludesFile = $homeExcludes\n")
        xdg.resolve("git/config").writeText("[core]\n\texcludesFile = $xdgExcludes\n")
        val cache =
            OriginStateCache.open(
                tempDir.resolve("precedence-cache"),
                tempDir,
                homeOverride = home,
                xdgConfigHomeOverride = xdg,
            )

        val first = cache.stamp(workspace.toRealPath(), null)
        assertNotNull(first)
        xdgExcludes.writeText("xdg.txt\nxdg-extra.txt\n")
        val afterXdg = cache.stamp(workspace.toRealPath(), null)
        assertNotNull(afterXdg)
        assertEquals(
            first.value,
            afterXdg.value,
            "The non-effective XDG excludes file changed the stamp",
        )
        homeExcludes.writeText("home.txt\nhome-extra.txt\n")
        val afterHome = cache.stamp(workspace.toRealPath(), null)
        assertNotNull(afterHome)
        assertNotEquals(first.value, afterHome.value)
    }

    @Test
    fun `a replacement ref invalidates origin reuse`() {
        val workspace = committedWorkspace()
        val base = git(workspace, "rev-parse", "HEAD").trim()
        Files.writeString(workspace.resolve("README.md"), "replacement\n")
        git(workspace, "add", "README.md")
        git(workspace, "commit", "-m", "replacement")
        val replacement = git(workspace, "rev-parse", "HEAD").trim()
        git(workspace, "reset", "--hard", base)
        ageWorkspace(workspace)
        val sources = sources(workspace)

        resolve(workspace, sources, newCache())
        assertEquals(1, resolve(workspace, sources, newCache()).cached)
        git(workspace, "replace", "HEAD", replacement)
        val replaced = resolve(workspace, sources, newCache())

        assertEquals(0, replaced.cached)
        assertTrue(replaced.origins.single().dirty, "${replaced.origins}")
        assertEquals(replaced.origins, resolve(workspace, sources, cache = null).origins)
    }

    @Test
    fun `effective git inputs are stamped or decline reuse`() {
        data class Case(val name: String, val setup: (Path) -> Unit, val declines: Boolean)

        fun create(path: Path, content: String) {
            Files.createDirectories(path.parent)
            path.writeText(content)
        }
        val replacementSha = "0123456789012345678901234567890123456789"
        val cases =
            listOf(
                Case(
                    "info/grafts",
                    { create(it.resolve(".git/info/grafts"), "$replacementSha\n") },
                    false,
                ),
                Case("shallow", { create(it.resolve(".git/shallow"), "$replacementSha\n") }, false),
                Case(
                    "info/sparse-checkout",
                    { create(it.resolve(".git/info/sparse-checkout"), "README.md\n") },
                    false,
                ),
                Case(
                    "config.worktree",
                    {
                        create(
                            it.resolve(".git/config.worktree"),
                            "[core]\n\tsparseCheckout = true\n",
                        )
                    },
                    false,
                ),
                Case(
                    "objects/info/alternates",
                    { create(it.resolve(".git/objects/info/alternates"), "/tmp/other-objects\n") },
                    true,
                ),
                Case(
                    "refs/replace",
                    {
                        create(
                            it.resolve(".git/refs/replace/$replacementSha"),
                            replacementSha + "\n",
                        )
                    },
                    false,
                ),
                Case(
                    "packed replacement ref",
                    {
                        create(
                            it.resolve(".git/packed-refs"),
                            "# pack-refs with: peeled fully-peeled sorted \n" +
                                "$replacementSha refs/replace/$replacementSha\n",
                        )
                    },
                    false,
                ),
            )

        cases.forEachIndexed { index, case ->
            val workspace = committedWorkspace("sweep-$index")
            val cache = newCache()
            val before = cache.stamp(workspace.toRealPath(), null)
            assertNotNull(before, case.name)
            case.setup(workspace)
            val after = cache.stamp(workspace.toRealPath(), null)
            if (case.declines) {
                assertNull(after, case.name)
            } else {
                assertNotNull(after, case.name)
                assertNotEquals(before.value, after.value, case.name)
            }
        }
    }

    @Test
    fun `a section-form git config include disables origin reuse`() {
        val workspace = committedWorkspace()
        val shared = tempDir.resolve("shared.gitconfig")
        shared.writeText("[core]\n\tquotepath = false\n")
        val config = workspace.resolve(".git/config")
        config.writeText(config.readText() + "\n[include]\n\tpath = $shared\n")
        val sources = sources(workspace)

        val first = resolve(workspace, sources, newCache())
        val second = resolve(workspace, sources, newCache())

        assertEquals(0, second.cached)
        assertEquals(first.origins, second.origins)
        assertEquals(first.origins, resolve(workspace, sources, cache = null).origins)
    }

    @Test
    fun `a submodule head advance invalidates the parent state`() {
        val child = committedWorkspace("submodule-child")
        val parent = Files.createDirectories(tempDir.resolve("submodule-parent")).toRealPath()
        Files.writeString(parent.resolve("README.md"), "parent\n")
        git(parent, "init")
        git(parent, "config", "user.email", "test@example.invalid")
        git(parent, "config", "user.name", "Indexino Test")
        git(parent, "add", ".")
        git(parent, "commit", "-m", "base")
        git(
            parent,
            "-c",
            "protocol.file.allow=always",
            "submodule",
            "add",
            child.toString(),
            "module",
        )
        git(parent, "commit", "-m", "add submodule")
        ageWorkspace(parent)
        val sources = listOf(IndexedSource("workspace", parent, "README.md"))

        val first = resolve(parent, sources, newCache())
        val unchanged = resolve(parent, sources, newCache())
        assertEquals(0, first.cached)
        assertEquals(1, unchanged.cached)

        val submodule = parent.resolve("module")
        git(submodule, "config", "user.email", "test@example.invalid")
        git(submodule, "config", "user.name", "Indexino Test")
        git(submodule, "commit", "--allow-empty", "-m", "advance")
        val advanced = resolve(parent, sources, newCache())

        assertEquals(0, advanced.cached)
        assertTrue(advanced.origins.single().dirty, "${advanced.origins}")
        assertEquals(advanced.origins, resolve(parent, sources, cache = null).origins)
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

    private fun newCache(): OriginStateCache {
        val home = Files.createDirectories(tempDir.resolve("test-home"))
        return OriginStateCache.open(
            tempDir.resolve("origin-cache"),
            tempDir,
            homeOverride = home,
            xdgConfigHomeOverride = home.resolve(".config"),
        )
    }

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
