package dev.sebastiano.indexino.producer.kotlinpsi

import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.CodeIndexRecordCodec
import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.ResourceUsageRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.producer.IndexBuildContext
import dev.sebastiano.indexino.producer.IndexedSource
import dev.sebastiano.indexino.producer.ProducerRegistry
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinPsiSymbolProducerTest {
    @Test
    fun `preserves one based declaration columns for Kotlin symbols`() {
        val source =
            """
            class FirstColumn

                fun multiline(
                    value: String,
                ) = value
            """
                .trimIndent()
        val context =
            IndexBuildContext.forInlineSources(
                store = store,
                commitHash = "columns",
                sourceFiles = mapOf("Columns.kt" to source),
            )

        checkNotNull(ProducerRegistry.get("kotlin-psi-symbols")).produce(context, store)

        val encodedSymbols =
            store
                .prefixScan("sym:")
                .map { CodeIndexRecordCodec.encode(it.second).decodeToString() }
                .toList()
        assertTrue(encodedSymbols.any { "\"name\":\"FirstColumn\"" in it && "\"column\":1" in it })
        assertTrue(encodedSymbols.any { "\"name\":\"multiline\"" in it && "\"column\":5" in it })
    }

    @Test
    fun `keeps equal Kotlin paths from separate origins distinct`() {
        val firstRoot = createTempDirectory("indexino-kotlin-origin-first-")
        val secondRoot = createTempDirectory("indexino-kotlin-origin-second-")
        val relativePath = "src/main/kotlin/sample/Panel.kt"
        firstRoot
            .resolve(relativePath)
            .also { it.parent.createDirectories() }
            .writeText("package sample\nfun first() = Unit")
        secondRoot
            .resolve(relativePath)
            .also { it.parent.createDirectories() }
            .writeText("package sample\nfun second() = Unit")
        val producer = checkNotNull(ProducerRegistry.get("kotlin-psi-symbols"))
        producer.produce(
            IndexBuildContext(
                store = store,
                commitHash = "abc",
                sourceFiles = listOf(relativePath),
                sources =
                    listOf(
                        IndexedSource("git:first", firstRoot, relativePath),
                        IndexedSource("git:second", secondRoot, relativePath),
                    ),
            ),
            store,
        )

        val symbols = store.prefixScan("sym:").map { it.second }.filterIsInstance<SymbolRecord>()
        assertEquals(
            setOf("git:first", "git:second"),
            symbols
                .filter { it.fqn in setOf("sample.first", "sample.second") }
                .map { it.originId }
                .toSet(),
        )
    }

    @Test
    fun `incremental callers resolve unchanged same-package declarations`() {
        val sources =
            mapOf(
                "src/main/kotlin/sample/Helpers.kt" to
                    "package sample\nfun helper(content: () -> Unit) = content()",
                "src/main/kotlin/sample/Caller.kt" to "package sample\nfun call() { helper({}) }",
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
        val call =
            store
                .prefixScan("call:")
                .map { it.second }
                .filterIsInstance<CallSiteRecord>()
                .single { it.relativeFile.endsWith("Caller.kt") }
        assertEquals("content", call.arguments.single().resolvedName)
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
        assertEquals(')', source[container.endOffset])
    }

    @Test
    fun `indexes Kotlin R resource usages with package identity`() {
        val source =
            """
            package com.example.app

            import com.example.feature.R as FeatureR
            import com.example.feature.R.string.title

            class Screen {
                val title = R.string.title
                val styleable = R.styleable.CustomView
                val accent = R.attr.accent
                val composeTitle = Res.string.compose_title
                val titleLength = R.string.title.length()
                val subtitle = FeatureR.string.subtitle
                val icon = com.example.assets.R.drawable.icon
                val notResource = foo.R.state.idle
            }
            """
                .trimIndent()
        val producer = checkNotNull(ProducerRegistry.get("kotlin-psi-symbols"))
        producer.produce(
            IndexBuildContext.forInlineSources(
                store = store,
                commitHash = "resources",
                sourceFiles =
                    mapOf(
                        "app/build.gradle.kts" to
                            "android { namespace = \"com.example.namespace\" }",
                        "app/src/main/kotlin/com/example/app/Screen.kt" to source,
                    ),
            ),
            store,
        )

        val usages =
            store
                .prefixScan("resuse:")
                .map { it.second }
                .filterIsInstance<ResourceUsageRecord>()
                .toList()
        assertEquals(7, usages.size)
        assertEquals(
            setOf(
                "com.example.namespace:string:title",
                "com.example.namespace:styleable:CustomView",
                "com.example.namespace:string:compose_title",
                "com.example.namespace:attr:accent",
                "com.example.feature:string:subtitle",
                "com.example.assets:drawable:icon",
            ),
            usages
                .map { listOf(it.packageName.orEmpty(), it.type, it.name).joinToString(":") }
                .toSet(),
        )
        assertTrue(usages.all { it.language == "kotlin" })
        assertTrue(usages.all { it.offset > 0 })
        assertTrue(usages.all { it.relativeFile.endsWith("Screen.kt") })
    }

    @Test
    fun `namespace metadata changes reindex Kotlin R usages`() {
        val source = "package com.example.app\nclass Screen { val title = R.string.title }"
        val initial =
            mapOf(
                "app/build.gradle.kts" to "android { namespace = \"com.example.old\" }",
                "app/src/main/kotlin/com/example/app/Screen.kt" to source,
            )
        val producer = checkNotNull(ProducerRegistry.get("kotlin-psi-symbols"))
        producer.produce(IndexBuildContext.forInlineSources(store, "initial", initial), store)
        val updated =
            initial + ("app/build.gradle.kts" to "android { namespace = \"com.example.new\" }")
        producer.produce(
            IndexBuildContext(
                store = store,
                commitHash = "updated",
                sourceFiles = updated.keys.toList(),
                sourceContentOverrides = updated,
                changedSourceFiles = setOf("app/build.gradle.kts"),
            ),
            store,
        )

        val packages =
            store
                .prefixScan("resuse:")
                .map { it.second }
                .filterIsInstance<ResourceUsageRecord>()
                .map { it.packageName }
                .toSet()
        assertEquals(setOf("com.example.new"), packages)
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
