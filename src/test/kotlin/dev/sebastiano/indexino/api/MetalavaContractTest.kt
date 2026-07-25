package dev.sebastiano.indexino.api

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class MetalavaContractTest {
    @Test
    fun `reviewed model signature includes host factories and format pin`() {
        // Nested GradleRunner against this project races with :test result aggregation.
        // Task wiring + --jdk-home are gated by :indexino-model:metalavaCheckSignature in check.
        val reviewedSignature =
            File(System.getProperty("user.dir")).resolve("api/indexino-model/current.txt")
        assertTrue(reviewedSignature.isFile, "Missing ${reviewedSignature.absolutePath}")
        val text = reviewedSignature.readText()
        assertTrue(text.contains("// Signature format: 5.0"))
        assertTrue(text.contains("package dev.sebastiano.indexino.model"))
        assertTrue(text.contains("class SymbolId"))
        assertTrue(text.contains("class PluginId"))
        assertTrue(text.contains("method public static"))
        assertTrue(text.contains("IndexFailure"))
        assertTrue(
            text.contains("method @dev.sebastiano.indexino.model.IndexinoInternalApi public static")
        )
    }
}
