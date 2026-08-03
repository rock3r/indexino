package dev.sebastiano.indexino.topology.gradle

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsParserTest {
    @Test
    fun `parses literal included builds from settings`() {
        val content =
            """
            includeBuild("../build-logic")
            includeBuild("tools/conventions")
            """
                .trimIndent()

        assertEquals(
            listOf("../build-logic", "tools/conventions"),
            SettingsParser.parseIncludedBuilds(content),
        )
    }

    @Test
    fun `parses Groovy included build declarations`() {
        val content =
            """
            includeBuild '../build-logic'
            includeBuild('tools/conventions')
            """
                .trimIndent()

        assertEquals(
            listOf("../build-logic", "tools/conventions"),
            SettingsParser.parseIncludedBuilds(content),
        )
    }

    @Test
    fun `ignores included builds inside comments`() {
        val content =
            """
            // includeBuild("../line-comment")
            includeBuild("../active") // includeBuild("../trailing-comment")
            /* includeBuild("../block-comment") */
            """
                .trimIndent()

        assertEquals(listOf("../active"), SettingsParser.parseIncludedBuilds(content))
    }

    @Test
    fun `parses include list from settings kts`() {
        val content =
            """
            rootProject.name = "demo"
            include(":core", ":ui")
            """
                .trimIndent()
        assertEquals(listOf(":core", ":ui"), SettingsParser.parseIncludes(content))
    }
}
