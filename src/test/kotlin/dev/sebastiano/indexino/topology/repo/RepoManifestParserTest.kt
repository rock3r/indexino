package dev.sebastiano.indexino.topology.repo

import kotlin.test.Test
import kotlin.test.assertEquals

class RepoManifestParserTest {
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
