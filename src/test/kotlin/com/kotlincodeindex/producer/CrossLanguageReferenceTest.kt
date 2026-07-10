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
            assertTrue(
                refs.any {
                    it.relativeFile.endsWith("KotlinGreeter.kt") &&
                        it.symbolFqn == "sample.JavaGreeter#name" &&
                        "sample.JavaGreeter#getName" in it.candidateSymbolFqns
                }
            )
            val booleanPropertyReference = refs.first {
                it.relativeFile.endsWith("KotlinGreeter.kt") &&
                    it.symbolFqn == "sample.KotlinGreeter#isEnabled"
            }
            assertTrue(
                "sample.KotlinGreeter#setEnabled" in booleanPropertyReference.candidateSymbolFqns
            )
            assertTrue(
                "sample.KotlinGreeter#setIsEnabled" !in booleanPropertyReference.candidateSymbolFqns
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
                        "sample.GreeterApi#topLevelGreeting" in it.aliases
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

    @Test
    fun `Kotlin imported top level calls use the imported symbol id`() {
        val source =
            """
            package sample
            import sample.Util.render as draw
            import api.Renderer as UiRenderer
            class LocalRenderer { fun render() {} }
            fun callImported(renderer: UiRenderer) {
                draw()
                renderer.render()
            }
            """
                .trimIndent()
        val store = XodusCodeIndexStore.open(createTempDirectory("imported-call-").resolve("index"))
        try {
            val context =
                IndexBuildContext.forInlineSources(
                    store,
                    "abc",
                    mapOf("src/main/kotlin/sample/Caller.kt" to source),
                )
            ProducerRegistry.forApplications(emptyList()).forEach { it.produce(context, store) }

            val refs =
                store
                    .prefixScan("ref:")
                    .map { it.second }
                    .filterIsInstance<ReferenceRecord>()
                    .toList()
            assertTrue(
                refs.any {
                    it.symbolFqn == "sample.Util.render" &&
                        "sample.Util#render" in it.candidateSymbolFqns &&
                        it.context == "call"
                }
            )
            assertTrue(refs.any { it.symbolFqn == "api.Renderer#render" })
        } finally {
            store.close()
        }
    }

    @Test
    fun `Kotlin self qualified properties resolve through their declared type`() {
        val source =
            """
            package sample
            class Renderer { fun render() {} }
            class OtherRenderer { fun render() {} }
            class Holder(val renderer: Renderer) {
                fun call(renderer: OtherRenderer) { this.renderer.render() }
            }
            """
                .trimIndent()
        val store = XodusCodeIndexStore.open(createTempDirectory("self-property-").resolve("index"))
        try {
            val context =
                IndexBuildContext.forInlineSources(
                    store,
                    "abc",
                    mapOf("src/main/kotlin/sample/Holder.kt" to source),
                )
            ProducerRegistry.forApplications(emptyList()).forEach { it.produce(context, store) }

            val refs =
                store
                    .prefixScan("ref:")
                    .map { it.second }
                    .filterIsInstance<ReferenceRecord>()
                    .toList()
            assertTrue(refs.any { it.symbolFqn == "sample.Renderer#render" })
            assertTrue(refs.none { it.symbolFqn == "sample.OtherRenderer#render" })
            assertTrue(refs.none { it.symbolFqn == "this.renderer#render" })

            val symbols =
                store.prefixScan("sym:").map { it.second }.filterIsInstance<SymbolRecord>().toList()
            assertTrue(
                symbols.any {
                    it.fqn == "sample.Holder#renderer" &&
                        it.kind == "property" &&
                        "sample.Holder#getRenderer" in it.aliases
                }
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun `unresolved Kotlin qualified calls do not fall back to unqualified members`() {
        val source =
            """
            package sample
            class Holder {
                val renderer = createRenderer()
                fun render() {}
                fun call() { this.renderer.render() }
            }
            """
                .trimIndent()
        val store =
            XodusCodeIndexStore.open(createTempDirectory("unresolved-qualified-").resolve("index"))
        try {
            val context =
                IndexBuildContext.forInlineSources(
                    store,
                    "abc",
                    mapOf("src/main/kotlin/sample/Holder.kt" to source),
                )
            ProducerRegistry.forApplications(emptyList()).forEach { it.produce(context, store) }

            val refs =
                store
                    .prefixScan("ref:")
                    .map { it.second }
                    .filterIsInstance<ReferenceRecord>()
                    .toList()
            assertTrue(refs.none { it.symbolFqn == "sample.Holder#render" })
        } finally {
            store.close()
        }
    }

    @Test
    fun `Kotlin object properties resolve before enclosing class properties`() {
        val source =
            """
            package sample
            class Renderer { fun render() {} }
            class OtherRenderer { fun render() {} }
            class Holder(val renderer: OtherRenderer) {
                companion object {
                    val renderer: Renderer = Renderer()
                    fun call() { this.renderer.render() }
                }
            }
            """
                .trimIndent()
        val store =
            XodusCodeIndexStore.open(createTempDirectory("object-property-").resolve("index"))
        try {
            val context =
                IndexBuildContext.forInlineSources(
                    store,
                    "abc",
                    mapOf("src/main/kotlin/sample/Holder.kt" to source),
                )
            ProducerRegistry.forApplications(emptyList()).forEach { it.produce(context, store) }

            val refs =
                store
                    .prefixScan("ref:")
                    .map { it.second }
                    .filterIsInstance<ReferenceRecord>()
                    .toList()
            assertTrue(refs.any { it.symbolFqn == "sample.Renderer#render" })
            assertTrue(refs.none { it.symbolFqn == "sample.OtherRenderer#render" })
        } finally {
            store.close()
        }
    }

    @Test
    fun `Kotlin local properties are not persisted as member symbols`() {
        val source =
            """
            package sample
            class Renderer
            class Holder {
                fun call() {
                    fun helper() {}
                    helper()
                    val model: Renderer = Renderer()
                }
            }
            """
                .trimIndent()
        val store =
            XodusCodeIndexStore.open(createTempDirectory("local-property-").resolve("index"))
        try {
            val context =
                IndexBuildContext.forInlineSources(
                    store,
                    "abc",
                    mapOf("src/main/kotlin/sample/Holder.kt" to source),
                )
            ProducerRegistry.forApplications(emptyList()).forEach { it.produce(context, store) }

            val symbols =
                store.prefixScan("sym:").map { it.second }.filterIsInstance<SymbolRecord>().toList()
            assertTrue(symbols.none { it.fqn == "sample.Holder#model" })
            assertTrue(symbols.none { it.fqn == "sample.Holder#helper" })
            val refs =
                store
                    .prefixScan("ref:")
                    .map { it.second }
                    .filterIsInstance<ReferenceRecord>()
                    .toList()
            assertTrue(refs.none { it.symbolFqn == "sample.Holder#helper" })
        } finally {
            store.close()
        }
    }

    @Test
    fun `Kotlin member properties shadow non-property constructor parameters`() {
        val source =
            """
            package sample
            class FirstRenderer { fun render() {} }
            class SecondRenderer { fun render() {} }
            class Holder(renderer: FirstRenderer) {
                val renderer: SecondRenderer = SecondRenderer()
                fun call() { renderer.render() }
            }
            """
                .trimIndent()
        val store =
            XodusCodeIndexStore.open(createTempDirectory("constructor-shadow-").resolve("index"))
        try {
            val context =
                IndexBuildContext.forInlineSources(
                    store,
                    "abc",
                    mapOf("src/main/kotlin/sample/Holder.kt" to source),
                )
            ProducerRegistry.forApplications(emptyList()).forEach { it.produce(context, store) }

            val refs =
                store
                    .prefixScan("ref:")
                    .map { it.second }
                    .filterIsInstance<ReferenceRecord>()
                    .toList()
            assertTrue(refs.any { it.symbolFqn == "sample.SecondRenderer#render" })
            assertTrue(refs.none { it.symbolFqn == "sample.FirstRenderer#render" })
        } finally {
            store.close()
        }
    }

    @Test
    fun `Kotlin constructor parameters resolve in initializers and init blocks`() {
        val source =
            """
            package sample
            class Renderer { fun render() {} }
            class Holder(renderer: Renderer) {
                val rendered = renderer.render()
                init { renderer.render() }
            }
            """
                .trimIndent()
        val store =
            XodusCodeIndexStore.open(createTempDirectory("constructor-init-").resolve("index"))
        try {
            val context =
                IndexBuildContext.forInlineSources(
                    store,
                    "abc",
                    mapOf("src/main/kotlin/sample/Holder.kt" to source),
                )
            ProducerRegistry.forApplications(emptyList()).forEach { it.produce(context, store) }

            val refs =
                store
                    .prefixScan("ref:")
                    .map { it.second }
                    .filterIsInstance<ReferenceRecord>()
                    .toList()
            assertTrue(refs.count { it.symbolFqn == "sample.Renderer#render" } == 2)
        } finally {
            store.close()
        }
    }

    @Test
    fun `receiver owners resolve through call returns and enclosing nested types`() {
        val sources =
            mapOf(
                "src/main/kotlin/sample/Nested.kt" to
                    """
                    package sample
                    class Renderer { fun render() {} }
                    fun createRenderer(): Renderer = Renderer()
                    fun inferredFactory(): Renderer = Renderer()
                    fun callFactory() {
                        createRenderer().render()
                        Renderer().render()
                        fun inferredFactory() = Renderer()
                        inferredFactory().render()
                    }
                    class NullableHolder(val renderer: Renderer?) {
                        fun render() {}
                        fun callSafe() { renderer?.render() }
                    }
                    object Util { fun render() {} }
                    fun callObject() {
                        Util.render()
                        JavaUtil.render()
                    }
                    class Holder {
                        class Renderer { fun render() {} }
                        val renderer: Renderer = Renderer()
                        fun callNested() { renderer.render() }
                    }
                    """
                        .trimIndent(),
                "src/main/java/sample/Outer.java" to
                    """
                    package sample;
                    class Outer {
                        static class Inner { void render() {} }
                        Inner model;
                        void callNested() { model.render(); }
                    }
                    class JavaUtil { static void render() {} }
                    """
                        .trimIndent(),
            )
        val store =
            XodusCodeIndexStore.open(createTempDirectory("nested-receivers-").resolve("index"))
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
                    it.symbolFqn == "sample.Renderer#render" && it.qualifier == "createRenderer()"
                }
            )
            assertTrue(
                refs.any {
                    it.symbolFqn == "sample.Renderer#render" && it.qualifier == "Renderer()"
                }
            )
            assertTrue(
                refs.any { it.symbolFqn == "sample.Renderer#render" && it.qualifier == "renderer" }
            )
            assertTrue(refs.none { it.qualifier == "inferredFactory()" })
            assertTrue(
                refs.none { it.symbolFqn == "sample.NullableHolder#render" && it.qualifier == null }
            )
            assertTrue(refs.any { it.symbolFqn == "sample.Util#render" && it.qualifier == "Util" })
            assertTrue(
                refs.any { it.symbolFqn == "sample.JavaUtil#render" && it.qualifier == "JavaUtil" }
            )
            assertTrue(
                refs.any {
                    it.symbolFqn == "sample.Holder.Renderer#render" && it.qualifier == "renderer"
                }
            )
            assertTrue(
                refs.any { it.symbolFqn == "sample.Outer.Inner#render" && it.qualifier == "model" }
            )
            assertTrue(refs.none { it.symbolFqn == "sample.createRenderer#render" })
            assertTrue(refs.none { it.symbolFqn == "sample.Inner#render" })
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
                    public String getName() { return "hello"; }
                    public void greet() {}
                    public void callKotlin(KotlinGreeter greeter) {
                        greeter.greet();
                        greeter.getTitle();
                        GreeterApi.topLevelGreeting();
                    }
                }
                """
                    .trimIndent(),
            "src/main/kotlin/sample/KotlinGreeter.kt" to
                """
                @file:JvmName("GreeterApi")
                package sample
                class KotlinGreeter {
                    val title: String = "hello"
                    var isEnabled: Boolean = true
                    fun greet() {}
                    fun callJava(greeter: JavaGreeter) {
                        this.greet()
                        greeter.greet()
                        println(greeter.title)
                        println(greeter.name)
                    }
                    fun callKotlin(other: KotlinGreeter) { println(other.isEnabled) }
                }
                fun topLevelGreeting() {}
                """
                    .trimIndent(),
        )
}
