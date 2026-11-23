package dev.sebastiano.indexino.producer.kotlinpsi

import dev.sebastiano.indexino.parse.KotlinPsiParser
import dev.sebastiano.indexino.parse.KotlinPsiParserPool
import dev.sebastiano.indexino.producer.IndexedSource
import java.nio.file.Path
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinSourceParserTest {
    @Test
    fun `parses every Kotlin file once across workers`() {
        val fileCount = 12
        val workers = 4
        val reads = AtomicIntegerArray(fileCount)
        val barrier = CyclicBarrier(workers)
        val inputs =
            (0 until fileCount).map { index ->
                KotlinParseInput(
                    source = IndexedSource.workspace(Path.of("/virtual"), "Batch$index.kt"),
                    defaultResourcePackage = null,
                ) {
                    reads.incrementAndGet(index)
                    barrier.await(5, TimeUnit.SECONDS)
                    "package batch\nfun value$index(argument$index: Int) = argument$index\n"
                }
            }

        val parsers = KotlinPsiParserPool(workers)
        val files =
            try {
                KotlinSourceParser().parse(inputs, parsers)
            } finally {
                parsers.close()
            }

        assertEquals(inputs.map { it.source }, files.map { it.source })
        assertTrue(
            (0 until fileCount).all { index -> reads.get(index) == 1 },
            "Each Kotlin source must be read and parsed exactly once",
        )
        assertEquals(
            workers,
            files.map { it.workerThreadName }.toSet().size,
            "Files must execute on the requested number of worker threads",
        )
    }

    @Test
    fun `a single parser still starts after the shared pool closes`() {
        KotlinPsiParserPool(1).use { pool ->
            KotlinSourceParser()
                .parse(
                    listOf(
                        KotlinParseInput(
                            source = IndexedSource.workspace(Path.of("/virtual"), "First.kt"),
                            defaultResourcePackage = null,
                        ) {
                            "package lifecycle\nfun first() = Unit"
                        }
                    ),
                    pool,
                )
        }
        KotlinPsiParser().use { parser ->
            val file = parser.parseFile("After.kt", "package lifecycle\nfun after() = Unit")
            assertTrue(file.declarations.isNotEmpty())
        }
    }

    @Test
    fun `parsed facts retain no compiler syntax tree`() {
        val parsers = KotlinPsiParserPool(1)
        try {
            val files =
                KotlinSourceParser()
                    .parse(
                        listOf(
                            KotlinParseInput(
                                source =
                                    IndexedSource.workspace(Path.of("/virtual"), "Retained.kt"),
                                defaultResourcePackage = null,
                            ) {
                                "package retained\nfun call(argument: Int) = println(argument)"
                            }
                        ),
                        parsers,
                    )
            val factTypes =
                listOf(
                    KotlinParsedFile::class.java,
                    KotlinFileFacts::class.java,
                    KotlinCallDraft::class.java,
                    KotlinCallArgumentDraft::class.java,
                    KotlinNamedCallResolution::class.java,
                )
            val retainedFields = factTypes.flatMap { type ->
                type.declaredFields.map { field ->
                    "${type.simpleName}.${field.name}" to field.type
                }
            }
            val syntaxFields = retainedFields.filter { (_, type) ->
                type.name.startsWith("org.jetbrains.kotlin.psi") ||
                    type.name.startsWith("org.jetbrains.kotlin.com.intellij")
            }
            assertTrue(syntaxFields.isEmpty(), "Parsed facts retain PSI: $syntaxFields")
            assertEquals(1, files.size)
            assertTrue(files.single().facts.symbols.any { symbol -> symbol.name == "call" })
        } finally {
            parsers.close()
        }
    }
}
