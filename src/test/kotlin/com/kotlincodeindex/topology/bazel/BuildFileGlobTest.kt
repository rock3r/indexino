package com.kotlincodeindex.topology.bazel

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildFileGlobTest {
    @Test
    fun `expands glob srcs to kotlin files under package`() {
        val workspace = Path("src/test/resources/fixtures/bazel")
        val buildFile = workspace.resolve("plugins/bar/glob/BUILD.bazel")
        val result = BuildFileParser.parseKotlinSources(buildFile, workspace)

        assertEquals(
            listOf(
                "plugins/bar/glob/src/main/kotlin/Foo.kt",
                "plugins/bar/glob/src/main/kotlin/Bar.kt",
            ).sorted(),
            result.paths.sorted(),
        )
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `does not treat glob call as literal path`() {
        val workspace = Path("src/test/resources/fixtures/bazel")
        val buildFile = workspace.resolve("plugins/bar/glob/BUILD.bazel")
        val result = BuildFileParser.parseKotlinSources(buildFile, workspace)

        assertTrue(result.paths.none { it.contains("glob(") || it.contains("**") })
    }

    @Test
    fun `warns when glob pattern matches no kotlin files`() {
        val workspace = Path("src/test/resources/fixtures/bazel")
        val buildFile = workspace.resolve("plugins/bar/empty-glob/BUILD.bazel")
        val result = BuildFileParser.parseKotlinSources(buildFile, workspace)

        assertEquals(emptyList(), result.paths)
        assertTrue(result.warnings.any { it.contains("glob") })
    }
}
