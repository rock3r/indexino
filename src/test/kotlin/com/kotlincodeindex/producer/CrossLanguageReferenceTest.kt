package com.kotlincodeindex.producer

import com.kotlincodeindex.core.record.ReferenceRecord
import com.kotlincodeindex.core.record.SymbolRecord
import com.kotlincodeindex.core.xodus.XodusCodeIndexStore
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class CrossLanguageReferenceTest {
    @Test
    fun `Java and Kotlin member references share language-neutral target ids`() {
        val sources = crossLanguageSources()

        val store =
            XodusCodeIndexStore.open(createTempDirectory("cross-language-").resolve("index"))
        try {
            val context = IndexBuildContext.forInlineSources(store, "abc", sources)
            ProducerRegistry.forApplications(emptyList()).forEach { it.produce(context, store) }

            val refs =
                store
                    .prefixScan("ref:")
                    .map { it.second }
                    .filterIsInstance<ReferenceRecord>()
                    .toList()
            assertTrue(
                refs.any {
                    it.relativeFile.endsWith("JavaGreeter.java") &&
                        it.symbolFqn == "sample.KotlinGreeter#greet"
                }
            )
            assertTrue(
                refs.any {
                    it.relativeFile.endsWith("KotlinGreeter.kt") &&
                        it.symbolFqn == "sample.KotlinGreeter#greet" &&
                        it.qualifier == "this"
                }
            )
            assertTrue(
                refs.any {
                    it.relativeFile.endsWith("KotlinGreeter.kt") &&
                        it.symbolFqn == "sample.JavaGreeter#greet"
                }
            )
            assertTrue(
                refs.any {
                    it.relativeFile.endsWith("JavaGreeter.java") &&
                        "sample.KotlinGreeter#title" in it.candidateSymbolFqns
                }
            )
            assertTrue(
                refs.any {
                    it.relativeFile.endsWith("KotlinGreeter.kt") &&
                        it.symbolFqn == "sample.JavaGreeter#title" &&
                        it.context == "member"
                }
            )

            val symbols =
                store.prefixScan("sym:").map { it.second }.filterIsInstance<SymbolRecord>().toList()
            assertTrue(
                symbols.any {
                    it.fqn == "sample.KotlinGreeter#title" &&
                        "sample.KotlinGreeter#getTitle" in it.aliases
                }
            )
            assertTrue(
                symbols.any {
                    it.fqn == "sample.KotlinGreeter#isEnabled" &&
                        "sample.KotlinGreeter#isEnabled" in it.aliases
                }
            )
            assertTrue(
                symbols.any {
                    it.fqn == "sample.topLevelGreeting" &&
                        "sample.KotlinGreeterKt#topLevelGreeting" in it.aliases
                }
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun `Kotlin receiver types are resolved in their declaration scope`() {
        val source =
            """
            package sample
            class FirstRenderer { fun render() {} }
            class SecondRenderer { fun render() {} }
            fun renderFirst(model: FirstRenderer) { model.render() }
            fun renderSecond(model: SecondRenderer) { model.render() }
            """
                .trimIndent()
        val store =
            XodusCodeIndexStore.open(createTempDirectory("receiver-scope-").resolve("index"))
        try {
            val context =
                IndexBuildContext.forInlineSources(
                    store,
                    "abc",
                    mapOf("src/main/kotlin/sample/Renderers.kt" to source),
                )
            ProducerRegistry.forApplications(emptyList()).forEach { it.produce(context, store) }

            val refs =
                store
                    .prefixScan("ref:")
                    .map { it.second }
                    .filterIsInstance<ReferenceRecord>()
                    .toList()
            assertTrue(refs.any { it.symbolFqn == "sample.FirstRenderer#render" })
            assertTrue(refs.any { it.symbolFqn == "sample.SecondRenderer#render" })
        } finally {
            store.close()
        }
    }

    @Test
    fun `Kotlin lambda and catch receiver types use their lexical bindings`() {
        val source =
            """
            package sample
            class Item { fun render() {} }
            class RenderFailure : Exception() { fun render() {} }
            fun render(items: List<Item>) {
                items.forEach { item: Item -> item.render() }
                try { error("failed") } catch (failure: RenderFailure) { failure.render() }
            }
            """
                .trimIndent()
        val store =
            XodusCodeIndexStore.open(createTempDirectory("lexical-bindings-").resolve("index"))
        try {
            val context =
                IndexBuildContext.forInlineSources(
                    store,
                    "abc",
                    mapOf("src/main/kotlin/sample/Bindings.kt" to source),
                )
            ProducerRegistry.forApplications(emptyList()).forEach { it.produce(context, store) }

            val refs =
                store
                    .prefixScan("ref:")
                    .map { it.second }
                    .filterIsInstance<ReferenceRecord>()
                    .toList()
            assertTrue(refs.any { it.symbolFqn == "sample.Item#render" })
            assertTrue(refs.any { it.symbolFqn == "sample.RenderFailure#render" })
        } finally {
            store.close()
        }
    }

    private fun crossLanguageSources(): Map<String, String> =
        mapOf(
            "src/main/java/sample/JavaGreeter.java" to
                """
                package sample;
                public class JavaGreeter {
                    public String title;
                    public void greet() {}
                    public void callKotlin(KotlinGreeter greeter) {
                        greeter.greet();
                        greeter.getTitle();
                        KotlinGreeterKt.topLevelGreeting();
                    }
                }
                """
                    .trimIndent(),
            "src/main/kotlin/sample/KotlinGreeter.kt" to
                """
                package sample
                class KotlinGreeter {
                    val title: String = "hello"
                    val isEnabled = true
                    fun greet() {}
                    fun callJava(greeter: JavaGreeter) {
                        this.greet()
                        greeter.greet()
                        println(greeter.title)
                    }
                }
                fun topLevelGreeting() {}
                """
                    .trimIndent(),
        )
}
