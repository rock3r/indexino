package dev.sebastiano.indexino.api

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class FacadeMetalavaContractTest {
    @Test
    fun `reviewed facade signature exposes AutoCloseable and model query types`() {
        // Nested GradleRunner against this project races with :test result aggregation.
        // Task wiring + --jdk-home are gated by :metalavaCheckSignature in check.
        val reviewedSignature =
            File(System.getProperty("user.dir")).resolve("api/indexino/current.txt")
        assertTrue(reviewedSignature.isFile, "Missing ${reviewedSignature.absolutePath}")
        val text = reviewedSignature.readText()
        assertTrue(text.contains("// Signature format: 5.0"))
        assertTrue(text.contains("class Indexino"))
        assertTrue(text.contains("java.lang.AutoCloseable"))
        assertTrue(text.contains("dev.sebastiano.indexino.model.SymbolQuery"))
        assertTrue(text.contains("dev.sebastiano.indexino.model.ReferenceQuery"))
        assertTrue(text.contains("dev.sebastiano.indexino.model.QueryOptions"))
    }
}
