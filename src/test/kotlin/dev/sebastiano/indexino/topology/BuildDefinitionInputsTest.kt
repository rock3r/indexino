package dev.sebastiano.indexino.topology

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.io.TempDir

class BuildDefinitionInputsTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `bazel definition digest covers repository and module inputs`() {
        val cases =
            listOf(".bazelignore", "REPO.bazel", "WORKSPACE.bzlmod", "segments/extra.MODULE.bazel")

        cases.forEachIndexed { index, relative ->
            val root = Files.createDirectories(tempDir.resolve("bazel-$index"))
            val file = root.resolve(relative)
            file.parent.createDirectories()
            file.writeText("initial\n")
            val before = BuildDefinitionInputs.capture(root, BuildSystem.BAZEL, null)
            assertNotNull(before, relative)
            file.writeText("changed\n")
            val after = BuildDefinitionInputs.capture(root, BuildSystem.BAZEL, null)
            assertNotNull(after, relative)
            assertNotEquals(before.definitionDigest, after.definitionDigest, relative)
        }
    }

    @Test
    fun `gradle definition digest covers applied scripts and every version catalog`() {
        val cases =
            listOf(
                "gradle/applied.gradle",
                "gradle/applied.gradle.kts",
                "gradle/custom.versions.toml",
            )

        cases.forEachIndexed { index, relative ->
            val root = Files.createDirectories(tempDir.resolve("gradle-$index"))
            val file = root.resolve(relative)
            file.parent.createDirectories()
            file.writeText("initial\n")
            val before = BuildDefinitionInputs.capture(root, BuildSystem.GRADLE, null)
            assertNotNull(before, relative)
            file.writeText("changed\n")
            val after = BuildDefinitionInputs.capture(root, BuildSystem.GRADLE, null)
            assertNotNull(after, relative)
            assertNotEquals(before.definitionDigest, after.definitionDigest, relative)
        }
    }
}
