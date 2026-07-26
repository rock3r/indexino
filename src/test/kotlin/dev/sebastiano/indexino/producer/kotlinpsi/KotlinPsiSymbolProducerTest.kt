package dev.sebastiano.indexino.producer.kotlinpsi

import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.producer.IndexBuildContext
import dev.sebastiano.indexino.producer.ProducerRegistry
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinPsiSymbolProducerTest {
    @Test
    fun `incremental callers resolve unchanged same-package declarations`() {
        val sources =
            mapOf(
                "src/main/kotlin/sample/Helpers.kt" to "package sample\nfun helper() {}",
                "src/main/kotlin/sample/Caller.kt" to "package sample\nfun call() { helper() }",
            )
        val producer = checkNotNull(ProducerRegistry.get("kotlin-psi-symbols"))
        producer.produce(IndexBuildContext.forInlineSources(store, "first", sources), store)
        producer.produce(
            IndexBuildContext(
                store = store,
                commitHash = "second",
                sourceFiles = sources.keys.toList(),
                sourceContentOverrides = sources,
                changedSourceFiles = setOf("src/main/kotlin/sample/Caller.kt"),
            ),
            store,
        )

        val references =
            store
                .prefixScan("ref:sample.helper:")
                .map { it.second }
                .filterIsInstance<ReferenceRecord>()
                .toList()
        assertTrue(references.any { it.relativeFile.endsWith("Caller.kt") })
    }

    @Test
    fun `indexes lexical call containment inside trailing lambdas`() {
        val source =
            """
            package sample
            fun Container(content: () -> Unit) = content()
            fun Child() {}
            fun Panel() { Container({ Child() }) }
            """
                .trimIndent()
        val context =
            IndexBuildContext.forInlineSources(
                store = store,
                commitHash = "calls",
                sourceFiles = mapOf("Panel.kt" to source),
            )

        checkNotNull(ProducerRegistry.get("kotlin-psi-symbols")).produce(context, store)

        val calls =
            store.prefixScan("call:").map { it.second }.filterIsInstance<CallSiteRecord>().toList()
        val container = calls.first { it.calleeName == "Container" && it.arguments.isNotEmpty() }
        val child = calls.first {
            it.calleeName == "Child" && it.parentCallIdentity == container.identity
        }
        assertEquals(listOf(child.identity), container.arguments.single().nestedCallIdentities)
        assertEquals(container.identity, child.parentCallIdentity)
        assertEquals("content", container.arguments.single().resolvedName)
        assertEquals("LAMBDA", container.arguments.single().kind)
    }

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
        val panel =
            """
            @Composable
            fun Panel() {
                ActionButton()
            }
            @Composable
            fun ActionButton() {}
            """
                .trimIndent()
        val context =
            IndexBuildContext.forInlineSources(
                store = store,
                commitHash = "abc",
                sourceFiles = mapOf("Panel.kt" to panel),
            )
        ProducerRegistry.get("kotlin-psi-symbols")!!.produce(context, store)

        val symbols =
            store.prefixScan("sym:").map { it.second }.filterIsInstance<SymbolRecord>().toList()
        assertTrue(symbols.any { it.name == "ActionButton" && it.kind == "function" })

        val refs =
            store.prefixScan("ref:").map { it.second }.filterIsInstance<ReferenceRecord>().toList()
        assertEquals(1, refs.size)
        assertTrue(refs[0].symbolFqn.contains("ActionButton"))
    }
}
