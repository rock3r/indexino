package dev.sebastiano.indexino.acceptance

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.producer.IndexBuildContext
import dev.sebastiano.indexino.producer.java.JavaSourceProducer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.system.measureNanoTime

/** Opt-in, test-classpath-only driver for profiling Java indexing against the real Xodus store. */
internal object JavaIndexingPerformanceDriver {
    private const val MAX_FILES = 2_000
    private const val MAX_METHODS = 100

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4) {
            "usage: JavaIndexingPerformanceDriver <fresh-root> <report.json> <files> <methods-per-file>"
        }
        val root = Path.of(args[0]).toAbsolutePath().normalize()
        val report = Path.of(args[1]).toAbsolutePath().normalize()
        val fileCount = args[2].toInt()
        val methodCount = args[3].toInt()
        require(fileCount in 2..MAX_FILES) { "files must be in 2..$MAX_FILES" }
        require(methodCount in 1..MAX_METHODS) { "methods-per-file must be in 1..$MAX_METHODS" }
        require(Files.notExists(root)) { "owned root must not exist: $root" }
        require(!report.startsWith(root)) { "report must be outside owned root: $report" }
        require(report.parent != null && Files.isDirectory(report.parent)) {
            "report parent must already exist: ${report.parent}"
        }

        val sourcePaths = mutableListOf<String>()
        var openedStore: CountingCodeIndexStore? = null
        Files.createDirectory(root)
        try {
            val sourceDirectory = root.resolve("sources/bench").also { it.createDirectories() }
            repeat(fileCount) { fileIndex ->
                val relativePath = "sources/bench/InventedNode$fileIndex.java"
                sourcePaths += relativePath
                sourceDirectory
                    .resolve("InventedNode$fileIndex.java")
                    .writeText(javaSource(fileIndex, fileCount, methodCount))
            }

            lateinit var countingStore: CountingCodeIndexStore
            val wallNanos = measureNanoTime {
                countingStore =
                    CountingCodeIndexStore(XodusCodeIndexStore.open(root.resolve("index")))
                openedStore = countingStore
                JavaSourceProducer()
                    .produce(
                        IndexBuildContext(
                            store = countingStore,
                            commitHash = "java-performance-driver",
                            sourceFiles = sourcePaths,
                            workspaceRoot = root,
                        ),
                        countingStore,
                    )
            }

            try {
                val store = countingStore
                val records = store.recordsForVerification()
                verifyDeclarations(records, fileCount, methodCount)
                verifyForwardParameterMapping(records)
                val json =
                    """
                    {
                      "coldWallMillis": ${nanosToMillis(wallNanos)},
                      "sourceCount": $fileCount,
                      "factCount": ${records.size},
                      "writeCount": ${store.putCount.get()},
                      "getCount": ${store.getCount.get()},
                      "scanCount": ${store.scanCount.get()},
                      "delegatedPutMillis": ${nanosToMillis(store.putNanos.get())},
                      "delegatedGetMillis": ${nanosToMillis(store.getNanos.get())},
                      "delegatedScanMillis": ${nanosToMillis(store.scanNanos.get())},
                      "files": $fileCount,
                      "methodsPerFile": $methodCount
                    }
                    """
                        .trimIndent() + "\n"
                report.writeText(json)
                print(json)
            } finally {
                openedStore = null
                countingStore.close()
            }
        } finally {
            try {
                openedStore?.close()
            } finally {
                deleteOwnedRoot(root)
            }
        }
    }

    private fun javaSource(index: Int, fileCount: Int, methodCount: Int): String = buildString {
        appendLine("package bench;")
        appendLine("final class InventedNode$index {")
        appendLine("  InventedNode$index() {}")
        val next = (index + 1) % fileCount
        repeat(methodCount) { method ->
            appendLine(
                "  static int method$method(int input${index}_$method) { " +
                    "return InventedNode$next.method$method(input${index}_$method); }"
            )
        }
        appendLine("}")
    }

    private fun verifyDeclarations(
        records: List<CodeIndexRecord>,
        fileCount: Int,
        methodCount: Int,
    ) {
        val actual = records.filterIsInstance<SymbolRecord>().map { it.fqn to it.kind }.toSet()
        val expected = buildSet {
            repeat(fileCount) { file ->
                add("bench.InventedNode$file" to "class")
                add("bench.InventedNode$file#<init>" to "constructor")
                repeat(methodCount) { method ->
                    add("bench.InventedNode$file#method$method" to "method")
                }
            }
        }
        check(actual == expected) {
            "declaration mismatch: missing=${expected - actual}, unexpected=${actual - expected}"
        }
    }

    private fun verifyForwardParameterMapping(records: List<CodeIndexRecord>) {
        val call =
            records.filterIsInstance<CallSiteRecord>().single {
                it.enclosingSymbolFqn == "bench.InventedNode0#method0" &&
                    "bench.InventedNode1#method0" in it.candidateSymbolFqns
            }
        check(call.arguments.single().resolvedName == "input1_0") {
            "forward call parameter was ${call.arguments.single().resolvedName}, expected input1_0"
        }
    }

    private fun nanosToMillis(nanos: Long): String =
        "%.3f".format(java.util.Locale.ROOT, nanos / 1_000_000.0)

    private fun deleteOwnedRoot(root: Path) {
        if (Files.notExists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private class CountingCodeIndexStore(private val delegate: CodeIndexStore) : CodeIndexStore {
        val putCount = AtomicLong()
        val getCount = AtomicLong()
        val scanCount = AtomicLong()
        val putNanos = AtomicLong()
        val getNanos = AtomicLong()
        val scanNanos = AtomicLong()

        fun recordsForVerification(): List<CodeIndexRecord> =
            delegate.prefixScan("").map { it.second }.toList()

        override fun get(key: CodeIndexKey): CodeIndexRecord? {
            getCount.incrementAndGet()
            var result: CodeIndexRecord? = null
            getNanos.addAndGet(measureNanoTime { result = delegate.get(key) })
            return result
        }

        override fun put(key: CodeIndexKey, record: CodeIndexRecord) {
            putCount.incrementAndGet()
            putNanos.addAndGet(measureNanoTime { delegate.put(key, record) })
        }

        override fun delete(key: CodeIndexKey) = delegate.delete(key)

        override fun prefixScan(prefix: String): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> {
            scanCount.incrementAndGet()
            var result: Sequence<Pair<CodeIndexKey, CodeIndexRecord>> = emptySequence()
            scanNanos.addAndGet(measureNanoTime { result = delegate.prefixScan(prefix) })
            return result
        }

        override fun forEachPrefix(
            prefix: String,
            action: (CodeIndexKey, CodeIndexRecord) -> Boolean,
        ) {
            scanCount.incrementAndGet()
            scanNanos.addAndGet(measureNanoTime { delegate.forEachPrefix(prefix, action) })
        }

        override fun <T> transaction(block: () -> T): T = delegate.transaction(block)

        override fun close() = delegate.close()
    }
}
