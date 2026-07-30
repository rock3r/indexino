package dev.sebastiano.indexino.topology

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildSystemDetectorTest {
    @Test
    fun `detects resolved repo workspaces`() {
        val workspace = createTempDirectory("indexino-repo-detect-")
        try {
            workspace
                .resolve(".repo/manifest.xml")
                .also { it.parent.createDirectories() }
                .writeText("<manifest/>")

            assertEquals(BuildSystem.REPO, BuildSystemDetector.detect(workspace))
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }
}
