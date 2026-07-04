package com.kotlincodeindex.producer.kotlinpsi

import com.kotlincodeindex.core.record.ReferenceRecord
import com.kotlincodeindex.core.record.SymbolRecord
import com.kotlincodeindex.core.xodus.XodusCodeIndexStore
import com.kotlincodeindex.producer.IndexBuildContext
import com.kotlincodeindex.producer.ProducerRegistry
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinPsiSymbolProducerTest {
    private lateinit var store: XodusCodeIndexStore
    private lateinit var tempDir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("sym-producer-")
        store = XodusCodeIndexStore.open(tempDir.resolve("base.xodus"))
    }

    @AfterTest
    fun tearDown() {
        store.close()
    }

    @Test
    fun `indexes symbols and references from fixture sources`() {
        val panel = """
            @Composable
            fun Panel() {
                ActionButton()
            }
            @Composable
            fun ActionButton() {}
        """.trimIndent()
        val context = IndexBuildContext.forInlineSources(
            store = store,
            commitHash = "abc",
            sourceFiles = mapOf("Panel.kt" to panel),
        )
        ProducerRegistry.get("kotlin-psi-symbols")!!.produce(context, store)

        val symbols = store.prefixScan("sym:").map { it.second }.filterIsInstance<SymbolRecord>().toList()
        assertTrue(symbols.any { it.name == "ActionButton" && it.kind == "function" })

        val refs = store.prefixScan("ref:").map { it.second }.filterIsInstance<ReferenceRecord>().toList()
        assertEquals(1, refs.size)
        assertTrue(refs[0].symbolFqn.contains("ActionButton"))
    }
}
