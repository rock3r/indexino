package dev.sebastiano.indexino.topology.repo

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class RepoManifestParserTest {
    @Test
    fun `includes local manifest projects`() {
        val workspace = createTempDirectory("indexino-repo-local-")
        try {
            val manifest = workspace.resolve(".repo/manifest.xml")
            manifest.parent.createDirectories()
            manifest.writeText("<manifest><project name=\"base\"/></manifest>")
            workspace
                .resolve(".repo/local_manifests/extra.xml")
                .also { it.parent.createDirectories() }
                .writeText("<manifest><project name=\"local\" path=\"local-path\"/></manifest>")

            assertEquals(
                listOf(RepoProject("base", "base", null), RepoProject("local", "local-path", null)),
                RepoManifestParser.parse(manifest).projects,
            )
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `keeps repository identity separate from project mount path`() {
        val manifest =
            """
            <manifest>
              <default revision="refs/heads/main"/>
              <project name="platform/tools/base" path="tools/base-local" revision="deadbeef"/>
              <project name="platform/frameworks/support" path="frameworks/support"/>
            </manifest>
            """
                .trimIndent()

        val projects = RepoManifestParser.parse(manifest).projects

        assertEquals(
            listOf(
                RepoProject("platform/frameworks/support", "frameworks/support", "refs/heads/main"),
                RepoProject("platform/tools/base", "tools/base-local", "deadbeef"),
            ),
            projects,
        )
    }
}
