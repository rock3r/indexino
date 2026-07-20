package dev.sebastiano.indexino.topology.bazel

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class BazelQueryResultParserTest {
    @Test
    fun `parses mock query output to source paths`() {
        val lines =
            Path("src/test/resources/fixtures/bazel/mock-query-output.txt").toFile().readLines()
        val paths = BazelQueryResultParser.parseKotlinSourcePaths(lines)
        assertEquals(
            listOf(
                "plugins/foo/ui/src/main/kotlin/Panel.kt",
                "plugins/foo/ui/src/main/kotlin/Other.kt",
                "plugins/foo/ui/src/main/java/Legacy.java",
            ),
            paths,
        )
    }

    @Test
    fun `parses Kotlin Java and XML source paths`() {
        val paths =
            BazelQueryResultParser.parseKotlinSourcePaths(
                listOf(
                    "//app:src/main/kotlin/App.kt",
                    "//app:src/main/java/Panel.java",
                    "//app:src/main/res/layout/main.xml",
                    "//:src/main/java/RootApp.java",
                    "//:src/main/res/layout/root.xml",
                    "@maven//:external.jar",
                )
            )

        assertEquals(
            listOf(
                "app/src/main/kotlin/App.kt",
                "app/src/main/java/Panel.java",
                "app/src/main/res/layout/main.xml",
                "src/main/java/RootApp.java",
                "src/main/res/layout/root.xml",
            ),
            paths,
        )
    }
}
