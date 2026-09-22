package dev.sebastiano.indexino.producer.kotlinpsi

import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.producer.IndexBuildContext
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class KotlinReceiverBindingTest {
    @Test
    fun `local constructor bindings shadow parameters only within their lexical block`() {
        val source =
            """
            package sample
            import library.Ledger as Book
            import javafixture.JavaLedger as Other
            fun use(book: Book) {
                book.mark()
                if (true) {
                    val book = Other()
                    book.mark()
                    println(book.title)
                }
                book.mark()
                val book: Other = Other()
                book.mark()
                println(book.title)
            }
            """
                .trimIndent()
        withIndexed(source) { store ->
            assertEquals(
                listOf(
                    5 to "library.Ledger#mark",
                    8 to "javafixture.JavaLedger#mark",
                    9 to "javafixture.JavaLedger#title",
                    11 to "library.Ledger#mark",
                    13 to "javafixture.JavaLedger#mark",
                    14 to "javafixture.JavaLedger#title",
                ),
                receiverReferences(store),
            )
            assertEquals(
                listOf(
                    5 to listOf("library.Ledger#mark"),
                    8 to listOf("javafixture.JavaLedger#mark"),
                    11 to listOf("library.Ledger#mark"),
                    13 to listOf("javafixture.JavaLedger#mark"),
                ),
                markCalls(store),
            )
        }
    }

    @Test
    fun `unknown bindings block outer parameters imports and object fallbacks`() {
        val source =
            """
            package sample
            import library.Ledger as Book
            object StaticBook { fun mark() {} }
            fun unknown(book: Book) {
                val book = unknownFactory()
                book.mark()
                println(book.title)
                val Book = unknownFactory()
                Book.mark()
                println(Book.title)
                val StaticBook = unknownFactory()
                StaticBook.mark()
                println(StaticBook.title)
            }
            fun lambda(book: Book) {
                unknownConsumer { book -> book.mark(); println(book.title) }
            }
            """
                .trimIndent()
        withIndexed(source) { store ->
            assertEquals(emptyList(), receiverReferences(store))
            assertEquals(listOf(6, 9, 12, 16).map { it to emptyList<String>() }, markCalls(store))
        }
    }

    @Test
    fun `initializer lookup uses declaration scope and functions before constructor names`() {
        val source =
            """
            package sample
            import library.Ledger as Book
            import javafixture.JavaLedger as Other
            fun declared(book: Book) {
                val book = Other()
                if (true) {
                    class Other { fun mark() {} }
                    book.mark()
                }
            }
            fun unknownFactory(book: Book) {
                fun Other() = unknownValue()
                val book = Other()
                book.mark()
            }
            fun typedFactory(book: Book) {
                fun Other(): Book = unknownValue()
                val book = Other()
                book.mark()
            }
            """
                .trimIndent()
        withIndexed(source) { store ->
            assertEquals(
                listOf(8 to "javafixture.JavaLedger#mark", 19 to "library.Ledger#mark"),
                receiverReferences(store),
            )
            assertEquals(
                listOf(
                    8 to listOf("javafixture.JavaLedger#mark"),
                    14 to emptyList(),
                    19 to listOf("library.Ledger#mark"),
                ),
                markCalls(store),
            )
        }
    }

    private fun receiverReferences(store: XodusCodeIndexStore): List<Pair<Int, String>> =
        store
            .prefixScan("ref:")
            .map { it.second }
            .filterIsInstance<ReferenceRecord>()
            .filter { it.referencedName in setOf("mark", "title") }
            .map { it.line to it.symbolFqn }
            .sortedBy { it.first }
            .toList()

    private fun markCalls(store: XodusCodeIndexStore): List<Pair<Int, List<String>>> =
        store
            .prefixScan("call:")
            .map { it.second }
            .filterIsInstance<CallSiteRecord>()
            .filter { it.calleeName == "mark" }
            .map { it.startLine to it.candidateSymbolFqns }
            .sortedBy { it.first }
            .toList()

    private fun withIndexed(source: String, block: (XodusCodeIndexStore) -> Unit) {
        val directory = createTempDirectory("receiver-binding-")
        try {
            val store = XodusCodeIndexStore.open(directory.resolve("index"))
            try {
                KotlinPsiSymbolProducer()
                    .produce(
                        IndexBuildContext.forInlineSources(
                            store,
                            "bindings",
                            mapOf("Use.kt" to source),
                        ),
                        store,
                    )
                block(store)
            } finally {
                store.close()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
