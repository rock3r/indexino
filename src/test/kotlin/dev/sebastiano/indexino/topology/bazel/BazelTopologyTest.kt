package dev.sebastiano.indexino.topology.bazel

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class BazelTopologyTest {
    @Test
    fun `mock query executor resolves kotlin source paths`() {
        val workspace = Path("src/test/resources/fixtures/bazel")
        val result =
            BazelTopology.resolveSources(
                target = "//plugins/foo/ui:ui",
                workspace = workspace,
                executor =
                    MockBazelQueryExecutor(
                        listOf(
                            "//plugins/foo/ui:src/main/kotlin/Panel.kt",
                            "//plugins/foo/ui:src/main/kotlin/Other.kt",
                        )
                    ),
            )
        assertEquals("bazel-query", result.topology)
        assertEquals(true, result.includeDeps)
        assertEquals(
            listOf(
                "plugins/foo/ui/src/main/kotlin/Panel.kt",
                "plugins/foo/ui/src/main/kotlin/Other.kt",
            ),
            result.sourceFiles,
        )
    }

    @Test
    fun `build parse fallback resolves sources without bazel`() {
        val workspace = Path("src/test/resources/fixtures/bazel")
        val labels = BazelTopology.degradedSourceLabels("//plugins/foo/ui:ui", workspace)
        val paths = BazelQueryResultParser.parseKotlinSourcePaths(labels)
        assertEquals(
            listOf(
                    "plugins/foo/ui/src/main/kotlin/Panel.kt",
                    "plugins/foo/ui/src/main/kotlin/Other.kt",
                )
                .sorted(),
            paths.sorted(),
        )
    }
}
