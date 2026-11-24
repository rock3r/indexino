package dev.sebastiano.indexino.producer.java

import dev.sebastiano.indexino.producer.IndexedSource
import java.nio.file.Path
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaSourceParserTest {
    @Test
    fun `parses bounded multi file batches once per file across workers`() {
        val fileCount = 12
        val filesPerBatch = 3
        val workers = 4
        val reads = AtomicIntegerArray(fileCount)
        val barrier = CyclicBarrier(workers)
        val inputs =
            (0 until fileCount).map { index ->
                JavaParseInput(
                    source = IndexedSource.workspace(Path.of("/virtual"), "Batch$index.java"),
                    defaultResourcePackage = null,
                ) {
                    reads.incrementAndGet(index)
                    barrier.await(5, TimeUnit.SECONDS)
                    "class Batch$index { int value$index; }"
                }
            }

        val batches = JavaSourceParser().parse(inputs, filesPerBatch, workers)

        assertEquals(
            List(fileCount / filesPerBatch) { filesPerBatch },
            batches.map { it.files.size },
        )
        assertEquals(
            inputs.map { it.source },
            batches.flatMap { batch -> batch.files.map { it.source } },
        )
        assertTrue(
            (0 until fileCount).all { index -> reads.get(index) == 1 },
            "Each Java source must be read and parsed exactly once",
        )
        assertEquals(
            workers,
            batches.map { it.workerThreadName }.toSet().size,
            "Batches must execute on the requested number of worker threads",
        )
    }
}
