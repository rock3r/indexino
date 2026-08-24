package dev.sebastiano.indexino.distribution

import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class NativeDynamicExtensionsDistributionTest {
    @Test
    fun `distribution docs describe native dynamic extension capability`() {
        val distributions = projectFile("docs/DISTRIBUTIONS.md").readText()
        val extensions = projectFile("docs/NATIVE-DYNAMIC-EXTENSIONS.md").readText()
        assertTrue(distributions.contains("Out-of-process worker"))
        assertTrue(distributions.contains("indexino-extension-worker.jar"))
        assertTrue(extensions.contains("Capability matrix"))
        assertTrue(extensions.contains("Not provided"))
    }

    private fun projectFile(relative: String) =
        sequenceOf(
                java.nio.file.Path.of(System.getProperty("indexino.projectDir", ".")),
                java.nio.file.Path.of("").toAbsolutePath(),
            )
            .map { it.resolve(relative).normalize() }
            .first { it.toFile().isFile }
}
