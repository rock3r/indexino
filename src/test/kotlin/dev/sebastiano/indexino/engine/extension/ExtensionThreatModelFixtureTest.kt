package dev.sebastiano.indexino.engine.extension

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

/** Contract test: threat model document exists and states required boundaries. */
class ExtensionThreatModelFixtureTest {
    @Test
    fun `threat model documents sandbox limits and out of process boundary`() {
        val doc = projectFile("docs/NATIVE-DYNAMIC-EXTENSIONS.md").readText()
        assertTrue(doc.contains("Not provided"), "Must document isolation limits")
        assertTrue(doc.contains("security sandbox"), "Must deny sandbox claims")
        assertTrue(doc.contains("Out-of-process"), "Must describe OOP boundary")
        assertTrue(doc.contains("Capability matrix"), "Must distinguish distribution modes")
        assertTrue(doc.contains("sessionToken"), "Must version handshake fields")
        assertTrue(doc.contains("query budget"), "Must document resource budgets")
    }

    private fun projectFile(relative: String): Path =
        sequenceOf(
                Path.of(System.getProperty("indexino.projectDir", ".")),
                Path.of("").toAbsolutePath(),
            )
            .map { it.resolve(relative).normalize() }
            .first { it.toFile().isFile }
}
