package dev.sebastiano.indexino.core.cache

import dev.sebastiano.indexino.core.BASIC_FACT_SCHEMA_VERSION
import dev.sebastiano.indexino.core.Version
import dev.sebastiano.indexino.core.manifest.IndexManifest
import dev.sebastiano.indexino.core.manifest.ManifestFreshness
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorktreeForkCompatibilityTest {
    private val tempDirs = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
        tempDirs.clear()
    }

    @Test
    fun `findCompatibleBase skips overlay bases at max chain depth`() {
        val cacheRoot = createTempDirectory("indexino-fork-depth-cache-").also(tempDirs::add)
        val (mainWorkspace, forkWorkspace) = createLinkedWorktrees()
        val gitCommonDir =
            checkNotNull(GitWorktreeLayout.commonDir(mainWorkspace)) {
                "fixture worktrees must share a git common dir"
            }
        val mainId = dev.sebastiano.indexino.api.InProcessCacheLayout.workspaceId(mainWorkspace)
        val compatibility =
            IndexManifest(
                commit = "abc123",
                indexerVersion = Version.NAME,
                basicFactSchemaVersion = BASIC_FACT_SCHEMA_VERSION,
                scope = ":ui",
                topology = "gradle",
                includeDeps = true,
                sourceFileCount = 4,
                sourcesContentHash = "hash",
                builtAt = "2026-01-01T00:00:00Z",
            )
        WorkspaceRegistryStore(cacheRoot).upsert(mainId, mainWorkspace, gitCommonDir)
        WorkspaceGenerationManifestStore(cacheRoot, mainId)
            .publish(
                WorkspaceGenerationManifest(
                    basicFactSchemaVersion = BASIC_FACT_SCHEMA_VERSION,
                    generation = "gen-max-depth",
                    workspaceRevisionFingerprint = "revision",
                    originId = "workspace",
                    revision = "abc123",
                    stateFingerprint = "hash",
                    packKeys = emptyList(),
                    compatibilityManifest = compatibility,
                    representation = WorktreeOverlayPolicy.REPRESENTATION_OVERLAY,
                    overlayChainDepth = WorktreeOverlayPolicy.MAX_CHAIN_DEPTH,
                )
            )

        val criteria =
            ManifestFreshness.criteriaFrom(
                commit = compatibility.commit,
                scope = compatibility.scope,
                includeDeps = compatibility.includeDeps,
                sourcesContentHash = compatibility.sourcesContentHash,
                applications = compatibility.applications,
            )

        assertNull(
            WorktreeForkCompatibility.findCompatibleBase(
                project = forkWorkspace,
                cacheRoot = cacheRoot,
                criteria = criteria,
            )
        )
    }

    @Test
    fun `findCompatibleBase accepts materialized base below max chain depth`() {
        val cacheRoot = createTempDirectory("indexino-fork-base-cache-").also(tempDirs::add)
        val (mainWorkspace, forkWorkspace) = createLinkedWorktrees()
        val gitCommonDir = checkNotNull(GitWorktreeLayout.commonDir(mainWorkspace))
        val mainId = dev.sebastiano.indexino.api.InProcessCacheLayout.workspaceId(mainWorkspace)
        val compatibility =
            IndexManifest(
                commit = "abc123",
                indexerVersion = Version.NAME,
                basicFactSchemaVersion = BASIC_FACT_SCHEMA_VERSION,
                scope = ":ui",
                topology = "gradle",
                includeDeps = true,
                sourceFileCount = 4,
                sourcesContentHash = "hash",
                builtAt = "2026-01-01T00:00:00Z",
            )
        WorkspaceRegistryStore(cacheRoot).upsert(mainId, mainWorkspace, gitCommonDir)
        WorkspaceGenerationManifestStore(cacheRoot, mainId)
            .publish(
                WorkspaceGenerationManifest(
                    basicFactSchemaVersion = BASIC_FACT_SCHEMA_VERSION,
                    generation = "gen-materialized",
                    workspaceRevisionFingerprint = "revision",
                    originId = "workspace",
                    revision = "abc123",
                    stateFingerprint = "hash",
                    packKeys = listOf("ab".repeat(32)),
                    compatibilityManifest = compatibility,
                    representation = WorktreeOverlayPolicy.REPRESENTATION_MATERIALIZED,
                )
            )

        val criteria =
            ManifestFreshness.criteriaFrom(
                commit = compatibility.commit,
                scope = compatibility.scope,
                includeDeps = compatibility.includeDeps,
                sourcesContentHash = compatibility.sourcesContentHash,
                applications = compatibility.applications,
            )

        val forkBase =
            WorktreeForkCompatibility.findCompatibleBase(
                project = forkWorkspace,
                cacheRoot = cacheRoot,
                criteria = criteria,
            )
        assertTrue(forkBase != null)
        assertEquals(mainId, forkBase.baseWorkspaceId)
        assertEquals(1, forkBase.overlayChainDepth)
    }

    private fun createLinkedWorktrees(): Pair<java.nio.file.Path, java.nio.file.Path> {
        val mainWorkspace = createGitWorkspace()
        val forkWorkspace = createTempDirectory("indexino-worktree-fork-").also(tempDirs::add)
        runGit(
            mainWorkspace,
            "worktree",
            "add",
            "-b",
            "overlay-fork-${System.nanoTime()}",
            forkWorkspace.toString(),
        )
        return mainWorkspace to forkWorkspace
    }

    private fun createGitWorkspace(): java.nio.file.Path {
        val workspace = createTempDirectory("indexino-overlay-main-").also(tempDirs::add)
        val fixtureRoot = java.nio.file.Path.of("src/test/resources/gradle-fixtures/multi-module")
        Files.walk(fixtureRoot).use { paths ->
            paths.forEach { path ->
                val destination = workspace.resolve(fixtureRoot.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(destination)
                else {
                    Files.createDirectories(destination.parent)
                    Files.copy(path, destination)
                }
            }
        }
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
