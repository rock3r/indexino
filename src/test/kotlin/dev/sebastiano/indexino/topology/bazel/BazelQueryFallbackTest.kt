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
                            query == "kind('source file', deps(//plugins/foo/ui:ui))" ->
                                BazelQueryOutcome(1, listOf("ERROR: partial checkout"))
                            query.contains("filegroup rule") -> BazelQueryOutcome(0, emptyList())
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

    @Test
    fun `target-only query failure falls back to BUILD parsing`() {
        val warnings = mutableListOf<String>()
        val lines =
            BazelTopology.queryWithFallback(
                target = "//plugins/foo/ui:ui",
                workspace = Path("src/test/resources/fixtures/bazel"),
                includeDeps = false,
                runner = BazelProcessRunner { _, _ -> BazelQueryOutcome(1, listOf("ERROR")) },
                onStderr = warnings::add,
            )

        assertEquals(false, lines.includeDeps)
        assertEquals("build-parse", lines.topology)
        assertTrue(warnings.any { it.contains("build-parse") })
        assertEquals(
            listOf(
                "plugins/foo/ui/src/main/kotlin/Other.kt",
                "plugins/foo/ui/src/main/kotlin/Panel.kt",
            ),
            BazelQueryResultParser.parseKotlinSourcePaths(lines.lines).sorted(),
        )
    }

    @Test
    fun `dependency and target query failures fall back to BUILD parsing`() {
        val queries = mutableListOf<String>()
        val lines =
            BazelTopology.queryWithFallback(
                target = "//plugins/foo/ui:ui",
                workspace = Path("src/test/resources/fixtures/bazel"),
                includeDeps = true,
                runner =
                    BazelProcessRunner { query, _ ->
                        queries += query
                        BazelQueryOutcome(1, listOf("ERROR"))
                    },
                onStderr = {},
            )

        assertEquals(
            listOf(
                "kind('source file', deps(//plugins/foo/ui:ui))",
                "kind('source file', labels(srcs, //plugins/foo/ui:ui)) union " +
                    "kind('source file', labels(resource_files, //plugins/foo/ui:ui))",
            ),
            queries,
        )
        assertEquals(false, lines.includeDeps)
        assertEquals(
            listOf(
                "plugins/foo/ui/src/main/kotlin/Other.kt",
                "plugins/foo/ui/src/main/kotlin/Panel.kt",
            ),
            BazelQueryResultParser.parseKotlinSourcePaths(lines.lines).sorted(),
        )
    }
}
