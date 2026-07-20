package dev.sebastiano.indexino.topology.bazel

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BazelQueryFallbackTest {
    @Test
    fun `falls back to labels srcs when deps query fails`() {
        val warnings = mutableListOf<String>()
        val lines =
            BazelTopology.queryWithFallback(
                target = "//plugins/foo/ui:ui",
                workspace = Path("."),
                runner =
                    BazelProcessRunner { query, _ ->
                        when {
                            query.contains("deps(") ->
                                BazelQueryOutcome(1, listOf("ERROR: partial checkout"))
                            query.contains("labels(srcs,") ->
                                BazelQueryOutcome(
                                    0,
                                    listOf(
                                        "//plugins/foo/ui:src/main/kotlin/Panel.kt",
                                        "//plugins/foo/ui:src/main/kotlin/Other.kt",
                                    ),
                                )
                            else -> error("unexpected query: $query")
                        }
                    },
                onStderr = warnings::add,
            )

        assertEquals(
            listOf(
                "plugins/foo/ui/src/main/kotlin/Panel.kt",
                "plugins/foo/ui/src/main/kotlin/Other.kt",
            ),
            BazelQueryResultParser.parseKotlinSourcePaths(lines.lines),
        )
        assertTrue(warnings.any { it.contains("labels(srcs") })
        assertEquals(false, lines.includeDeps)
    }

    @Test
    fun `uses primary deps query when it succeeds`() {
        val warnings = mutableListOf<String>()
        val lines =
            BazelTopology.queryWithFallback(
                target = "//plugins/foo/ui:ui",
                workspace = Path("."),
                runner =
                    BazelProcessRunner { query, _ ->
                        check(query.contains("deps("))
                        BazelQueryOutcome(0, listOf("//plugins/foo/ui:src/main/kotlin/Panel.kt"))
                    },
                onStderr = warnings::add,
            )

        assertEquals(
            listOf("plugins/foo/ui/src/main/kotlin/Panel.kt"),
            BazelQueryResultParser.parseKotlinSourcePaths(lines.lines),
        )
        assertTrue(warnings.isEmpty())
        assertEquals(true, lines.includeDeps)
    }
}
