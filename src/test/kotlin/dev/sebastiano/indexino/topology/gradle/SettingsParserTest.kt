package dev.sebastiano.indexino.topology.gradle

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsParserTest {
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
