package dev.sebastiano.indexino.producer.kotlinpsi

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.record.CodeIndexRecordCodec
import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.ResourceUsageRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.producer.IndexBuildContext
import dev.sebastiano.indexino.producer.IndexedSource
import dev.sebastiano.indexino.producer.ProducerRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KotlinPsiSymbolProducerContractTest {
    @Test
    fun `emits the same records as the two pass baseline on multi file fixtures`() {
        withStore { store ->
            kotlinProducer()
                .produce(IndexBuildContext.forInlineSources(store, "contract", sources()), store)

            val actual =
                store
                    .prefixScan("")
                    .filter { (_, record) -> record.languageNamespace() }
                    .map { (key, record) -> encodedLine(key, record) }
                    .toList()
                    .sorted()
            val golden = Path.of(GOLDEN_PATH)
            if (!Files.exists(golden)) {
                writeActual(actual)
                assertTrue(Files.exists(golden), "Missing baseline golden: $golden")
            }
            val expected = Files.readAllLines(golden).filter(String::isNotBlank)
            if (expected != actual) {
                writeActual(actual)
                assertEquals(
                    expected.size,
                    actual.size,
                    "Record count changed; actual: $ACTUAL_PATH",
                )
            }
            assertEquals(expected, actual)
        }
    }

    private fun writeActual(actual: List<String>) {
        val actualPath = Path.of(ACTUAL_PATH)
        Files.createDirectories(actualPath.parent)
        Files.write(actualPath, actual)
    }

    @Test
    fun `resolves cross file parameter names without symbol store scans`() {
        val symbolScans = AtomicInteger()
        withStore { delegate ->
            val store =
                object : CodeIndexStore by delegate {
                    override fun prefixScan(
                        prefix: String
                    ): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> {
                        if (prefix.startsWith("sym:")) symbolScans.incrementAndGet()
                        return delegate.prefixScan(prefix)
                    }

                    override fun forEachPrefix(
                        prefix: String,
                        action: (CodeIndexKey, CodeIndexRecord) -> Boolean,
                    ) {
                        if (prefix.startsWith("sym:")) symbolScans.incrementAndGet()
                        delegate.forEachPrefix(prefix, action)
                    }
                }
            kotlinProducer()
                .produce(
                    IndexBuildContext.forInlineSources(
                        store,
                        "store-reads",
                        linkedMapOf(
                            "Caller.kt" to
                                "package sample\nimport fixture.Target\n" +
                                    "fun caller(supplied: Int) { Target.take(supplied) }",
                            "Target.kt" to
                                "package fixture\nobject Target { " +
                                    "fun take(expected: Int) = expected }",
                        ),
                    ),
                    store,
                )
            assertEquals(0, symbolScans.get(), "Candidates still query symbol store reads")
        }
    }

    @Test
    fun `prefers the caller origin when another origin redeclares the same function`() {
        val originA = createTempDirectory("kotlin-contract-origin-a-")
        val originB = createTempDirectory("kotlin-contract-origin-b-")
        try {
            originA.resolve("ATarget.kt").apply {
                parent.createDirectories()
                writeText("package sample\nclass Target { fun take(alpha: Int) = alpha }")
            }
            originB.resolve("BTarget.kt").apply {
                parent.createDirectories()
                writeText("package sample\nclass Target { fun take(beta: Int) = beta }")
            }
            originB.resolve("Caller.kt").apply {
                parent.createDirectories()
                writeText("package sample\nfun call() { Target().take(1) }")
            }
            withStore { store ->
                val producer = kotlinProducer()
                val sources =
                    listOf(
                        IndexedSource("git:a", originA, "ATarget.kt"),
                        IndexedSource("git:b", originB, "BTarget.kt"),
                        IndexedSource("git:b", originB, "Caller.kt"),
                    )
                val sourceFiles = sources.map { source -> source.path }
                producer.produce(
                    IndexBuildContext(
                        store = store,
                        commitHash = "initial",
                        sourceFiles = sourceFiles,
                        sources = sources,
                    ),
                    store,
                )

                originA
                    .resolve("ATarget.kt")
                    .writeText("package sample\nclass Target { fun take(gamma: Int) = gamma }")
                originB
                    .resolve("Caller.kt")
                    .writeText("package sample\nfun call() { Target().take(2) }")
                producer.produce(
                    IndexBuildContext(
                        store = store,
                        commitHash = "changed",
                        sourceFiles = sourceFiles,
                        sources = sources,
                        changedSourceFiles = setOf("ATarget.kt", "Caller.kt"),
                    ),
                    store,
                )

                val call =
                    store
                        .prefixScan("call:")
                        .map { it.second }
                        .filterIsInstance<CallSiteRecord>()
                        .single { it.calleeName == "take" }
                assertEquals("beta", call.arguments.single().resolvedName)
            }
        } finally {
            originA.toFile().deleteRecursively()
            originB.toFile().deleteRecursively()
        }
    }

    @Test
    fun `keeps stored foreign language parameter names for Kotlin callers`() {
        val workspace = createTempDirectory("kotlin-contract-mixed-")
        try {
            withStore { store ->
                store.put(
                    CodeIndexKey.symbolDefinition(
                        fqn = "fixture.java.JavaTarget#render",
                        originId = "workspace",
                        relativeFile = "Target.java",
                        line = 3,
                        column = 5,
                    ),
                    SymbolRecord(
                        fqn = "fixture.java.JavaTarget#render",
                        relativeFile = "Target.java",
                        originId = "workspace",
                        line = 3,
                        column = 5,
                        kind = "method",
                        name = "render",
                        language = "java",
                        ownerFqn = "fixture.java.JavaTarget",
                        arity = 1,
                        parameterNames = listOf("javaValue"),
                    ),
                )
                kotlinProducer()
                    .produce(
                        IndexBuildContext(
                            store = store,
                            commitHash = "mixed",
                            sourceFiles = listOf("Caller.kt", "Target.java"),
                            sources =
                                listOf(
                                    IndexedSource("workspace", workspace, "Caller.kt"),
                                    IndexedSource("workspace", workspace, "Target.java"),
                                ),
                            sourceContentOverrides =
                                mapOf(
                                    "Caller.kt" to
                                        "package sample\nimport fixture.java.JavaTarget\n" +
                                            "fun call() { JavaTarget.render(1) }"
                                ),
                            changedSourceFiles = setOf("Caller.kt"),
                        ),
                        store,
                    )

                val call =
                    store
                        .prefixScan("call:")
                        .map { it.second }
                        .filterIsInstance<CallSiteRecord>()
                        .single()
                assertEquals(listOf("fixture.java.JavaTarget#render"), call.candidateSymbolFqns)
                assertEquals("javaValue", call.arguments.single().resolvedName)
            }
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private fun kotlinProducer() = assertNotNull(ProducerRegistry.get("kotlin-psi-symbols"))

    private fun CodeIndexRecord.languageNamespace(): Boolean =
        this is SymbolRecord ||
            this is ReferenceRecord ||
            this is CallSiteRecord ||
            this is ResourceUsageRecord

    private fun encodedLine(key: CodeIndexKey, record: CodeIndexRecord): String =
        key.value + "\t" + CodeIndexRecordCodec.encode(record).decodeToString()

    companion object {
        const val GOLDEN_PATH =
            "src/test/resources/fixtures/kotlin-producer-contract/expected-records.txt"
        const val ACTUAL_PATH = "build/kotlin-producer-contract/actual-records.txt"

        fun sources(): LinkedHashMap<String, String> =
            linkedMapOf<String, String>().apply {
                put("app/build.gradle.kts", "android { namespace = \"com.example.namespace\" }")
                putAll(librarySources())
                putAll(declarationSources())
                putAll(useSources())
            }

        private fun librarySources(): Map<String, String> =
            linkedMapOf(
                "app/src/main/kotlin/fixture/library/Ledger.kt" to
                    """
                    package fixture.library

                    interface Ledger {
                        fun mark()
                        val title: String
                    }

                    class BookLedger(override val title: String) : Ledger {
                        override fun mark() {}
                        fun write(entry: String) {}
                        fun copy(count: Int = 1) {}
                    }
                    """
                        .trimIndent(),
                "app/src/main/kotlin/fixture/other/LedgerImpl.kt" to
                    """
                    package fixture.other

                    import fixture.library.Ledger

                    class OtherLedger : Ledger {
                        override fun mark() {}
                        override val title: String get() = "other"
                    }
                    """
                        .trimIndent(),
                "app/src/main/kotlin/fixture/library/Renderer.kt" to
                    """
                    package fixture.library

                    interface Renderer {
                        fun draw(count: Int, visible: Boolean)
                    }
                    """
                        .trimIndent(),
            )

        private fun declarationSources(): Map<String, String> =
            linkedMapOf(
                "app/src/main/kotlin/fixture/declarations/Shapes.kt" to
                    """
                    package fixture.declarations

                    import fixture.library.Renderer

                    interface Shape {
                        fun area(scale: Int): Int
                        fun label(): String
                    }

                    open class Base(val baseValue: Int) {
                        open fun inherited(width: Int) = width
                        protected fun helper(text: String) = text
                    }

                    class Square(side: Int, override val label: String) : Base(side), Shape {
                        var isVisible: Boolean = true
                        val areaValue: Int get() = side * side

                        override fun area(scale: Int): Int = side * scale

                        override fun label(): String = label

                        fun render(target: Renderer) {
                            target.draw(area(2), isVisible)
                            this.helper("x")
                            super.inherited(3)
                            println(this.areaValue)
                            println(areaValue)
                            nested()
                        }

                        fun nested() {}

                        class Nested {
                            fun deep(): Int = 1
                        }
                    }

                    object Registry {
                        fun register(name: String) {}
                        val default: String = "default"
                    }
                    """
                        .trimIndent(),
                "app/src/main/kotlin/fixture/overloads/Overloads.kt" to
                    """
                    package fixture.overloads

                    fun format(text: String) {}

                    fun format(number: Int) {}

                    fun format(number: Int, flag: Boolean) {}

                    fun varargs(vararg values: String) {}

                    fun callers() {
                        format("text")
                        format(42)
                        format(1, true)
                        varargs("a", "b")
                    }
                    """
                        .trimIndent(),
                "app/src/main/kotlin/fixture/facade/Facade.kt" to
                    """
                    @file:JvmName("Facades")

                    package fixture.facade

                    val visible: String = "v"
                    var counter: Int = 0
                    val isReady: Boolean = true

                    fun topLevelAction(argument: String) = argument

                    object Holder {
                        @JvmName("renamedAction")
                        fun action(value: Int) {}
                    }
                    """
                        .trimIndent(),
            )

        private fun useSources(): Map<String, String> =
            linkedMapOf<String, String>().apply {
                putAll(useFileSources())
                putAll(receiverSources())
                putAll(resourceSources())
                putAll(consumerSources())
                putAll(callSources())
            }

        private fun useFileSources(): Map<String, String> =
            linkedMapOf(
                "app/src/main/kotlin/fixture/use/Use.kt" to
                    """
                    package fixture.use

                    import fixture.library.BookLedger
                    import fixture.library.Ledger as Book
                    import fixture.other.OtherLedger

                    fun use(book: Book) {
                        book.mark()
                        if (true) {
                            val book = OtherLedger()
                            book.mark()
                            println(book.title)
                        }
                        book.mark()
                        val explicit: OtherLedger = OtherLedger()
                        explicit.mark()
                        val inferred = BookLedger("t")
                        inferred.write("x")
                        inferred.mark()
                        println(inferred.title)
                        localHelper()
                    }

                    fun localHelper() = Unit

                    fun lambdaUser() {
                        val block = { value: Int -> value + 1 }
                        listOf(1).forEach { item -> println(item) }
                        block(2)
                    }
                    """
                        .trimIndent()
            )

        private fun receiverSources(): Map<String, String> =
            linkedMapOf(
                "app/src/main/kotlin/fixture/receivers/Receivers.kt" to
                    """
                    package fixture.receivers

                    import fixture.declarations.Square
                    import fixture.library.BookLedger
                    import fixture.library.Renderer

                    fun receiver(book: BookLedger) {
                        book.write("one")
                        val local = Square(1, "square")
                        local.render(object : Renderer {
                            override fun draw(count: Int, visible: Boolean) {}
                        })
                        val square = factory()
                        square.render(object : Renderer {
                            override fun draw(count: Int, visible: Boolean) {}
                        })
                        val typed: Square = factory()
                        typed.nested()
                    }

                    fun factory(): Square = Square(1, "f")

                    object StaticHolder {
                        fun mark() {}
                    }

                    fun shadow(book: BookLedger) {
                        val book = unknown()
                        book.write("shadowed")
                        val StaticHolder = unknown()
                        StaticHolder.mark()
                    }

                    fun unknown(): BookLedger = BookLedger("u")
                    """
                        .trimIndent()
            )

        private fun resourceSources(): Map<String, String> =
            linkedMapOf(
                "app/src/main/kotlin/fixture/resources/ResourceScreen.kt" to
                    """
                    package fixture.resources

                    import com.example.feature.R
                    import com.example.feature.R.string.title
                    import com.example.shared.R.string.shared_title as appSharedTitle

                    class ResourceScreen {
                        val title = R.string.title
                        val styleable = R.styleable.CustomView
                        val shadowedTitle = title
                        val accent = R.attr.accent
                        val composeTitle = Res.string.compose_title
                        val assetComposeIcon = com.example.assets.Res.drawable.asset_icon
                        val length = R.string.title.length()
                        val icon = com.example.assets.R.drawable.icon
                        val notResource = foo.R.state.idle
                    }

                    fun useStaticTitle() = title

                    fun staticTitleLength() = title.length()

                    fun useAliasedTitle() = appSharedTitle
                    """
                        .trimIndent()
            )

        private fun consumerSources(): Map<String, String> =
            linkedMapOf(
                "app/src/main/kotlin/fixture/consumer/Consumer.kt" to
                    """
                    package fixture.consumer

                    import fixture.declarations.Registry
                    import fixture.overloads.format as renamed

                    fun consume() {
                        renamed("alias")
                        renamed(7)
                        Registry.register("name")
                        println(Registry.default)
                    }
                    """
                        .trimIndent()
            )

        private fun callSources(): Map<String, String> =
            linkedMapOf(
                "app/src/main/kotlin/fixture/calls/Composition.kt" to
                    """
                    package fixture.calls

                    fun Container(content: () -> Unit) = content()

                    fun Child() {}

                    fun Panel() {
                        Container({ Child() })
                        Container { Child() }
                    }

                    class Chain {
                        override fun toString(): String = "chain"
                    }

                    fun chain() {
                        Panel().toString()
                        Chain().toString()
                    }
                    """
                        .trimIndent()
            )

        private fun withStore(block: (XodusCodeIndexStore) -> Unit) {
            val store =
                XodusCodeIndexStore.open(createTempDirectory("kotlin-contract-").resolve("index"))
            try {
                block(store)
            } finally {
                store.close()
            }
        }
    }
}
