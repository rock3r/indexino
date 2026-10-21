package dev.sebastiano.indexino.producer.kotlinpsi

import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.producer.IndexBuildContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class KotlinPsiHeapTest {
    @Test
    fun `indexes project larger than heap without retaining every syntax tree`(
        @TempDir root: Path
    ) {
        val output = root.resolve("heap-probe.log")
        val process =
            ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx256m",
                    "-cp",
                    System.getProperty("java.class.path"),
                    KotlinPsiHeapProbe::class.java.name,
                    root.toString(),
                )
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start()
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "Kotlin heap probe timed out")
            assertEquals(0, process.exitValue(), output.readText().takeLast(4000))
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
    }
}

internal object KotlinPsiHeapProbe {
    private const val FILE_COUNT = 384
    private const val LOCALS_PER_FILE = 2048

    @JvmStatic
    fun main(args: Array<String>) {
        val body = buildString {
            repeat(LOCALS_PER_FILE) { append("val local$it = $it\n") }
            append("target(7)\n")
        }
        val sources =
            (0 until FILE_COUNT).associate { index ->
                "Caller$index.kt" to "package sample\nfun caller$index() {\n$body}\n"
            } + ("Target.kt" to "package sample\nfun target(value: Int) {}\n")
        XodusCodeIndexStore.open(Path.of(args.single()).resolve("store")).use { store ->
            KotlinPsiSymbolProducer()
                .produce(IndexBuildContext.forInlineSources(store, "heap-probe", sources), store)
            val symbols =
                store.prefixScan("sym:").map { it.second }.filterIsInstance<SymbolRecord>().toList()
            check(
                symbols.map { it.fqn }.toSet() ==
                    (0 until FILE_COUNT).map { "sample.caller$it" }.toSet() + "sample.target"
            )
            val calls =
                store
                    .prefixScan("call:")
                    .map { it.second }
                    .filterIsInstance<CallSiteRecord>()
                    .toList()
            check(calls.size == FILE_COUNT)
            calls.forEach { call ->
                check(call.candidateSymbolFqns == listOf("sample.target"))
                check(call.arguments.single().resolvedName == "value")
                check(call.startLine == LOCALS_PER_FILE + 3)
            }
            val references =
                store
                    .prefixScan("ref:")
                    .map { it.second }
                    .filterIsInstance<ReferenceRecord>()
                    .toList()
            check(references.size == FILE_COUNT)
            check(references.all { it.symbolFqn == "sample.target" })
        }
    }
}
