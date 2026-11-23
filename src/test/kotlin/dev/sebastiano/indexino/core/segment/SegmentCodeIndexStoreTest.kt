package dev.sebastiano.indexino.core.segment

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.MetaIndexerVersionRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SegmentCodeIndexStoreTest {
    private lateinit var tempDir: Path
    private val files = mutableListOf<MappedSegmentFile>()

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("segment-store-test-")
    }

    @AfterTest
    fun tearDown() {
        files.forEach { runCatching { it.close() } }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `matches a Xodus store record for record after conversion`() {
        openXodus("source").use { source ->
            val symbolA =
                CodeIndexKey.symbolDefinition("com.example.Panel", "one", "ui/Panel.kt", 12, 1)
            val symbolB =
                CodeIndexKey.symbolDefinition("com.example.Button", "two", "ui/Button.kt", 4, 1)
            val aliased = CodeIndexKey.sym("com.example.Legacy")
            val call = CodeIndexKey.call("call-identity-1")
            val file = CodeIndexKey.file("ui/Panel.kt", "hash-a")
            source.put(symbolA, symbol("Panel", "com.example.Panel", "one", "ui/Panel.kt"))
            source.put(symbolB, symbol("Button", "com.example.Button", "two", "ui/Button.kt"))
            source.put(
                aliased,
                symbol("Legacy", "com.example.Legacy", "workspace", "Legacy.kt")
                    .copy(aliases = listOf("AliasOne", "AliasTwo")),
            )
            source.put(call, callRecord("call-identity-1", "one", "ui/Panel.kt"))
            source.put(file, MetaIndexerVersionRecord("meta"))

            convert(source).use { store ->
                assertEquals(source.get(symbolA), store.get(symbolA))
                assertEquals(source.get(aliased), store.get(aliased))
                assertEquals(source.get(call), store.get(call))
                assertEquals(source.get(file), store.get(file))
                assertNull(store.get(CodeIndexKey.sym("absent")))

                assertEquals(
                    source.prefixScan("").map { it.first.value }.toList(),
                    store.prefixScan("").map { it.first.value }.toList(),
                )
                assertEquals(
                    source.prefixScan("").map { it.second }.toList(),
                    store.prefixScan("").map { it.second }.toList(),
                )
                assertEquals(
                    source.prefixScan("sym:").map { it.second }.toList(),
                    store.prefixScan("sym:").map { it.second }.toList(),
                )
            }
        }
    }

    @Test
    fun `derived name and file lookups match the Xodus store`() {
        openXodus("source").use { source ->
            val panel =
                CodeIndexKey.symbolDefinition("com.example.Panel", "one", "ui/Panel.kt", 12, 1)
            val button =
                CodeIndexKey.symbolDefinition("com.example.Button", "two", "ui/Button.kt", 4, 1)
            val call = CodeIndexKey.call("call-identity-1")
            source.put(panel, symbol("Panel", "com.example.Panel", "one", "ui/Panel.kt"))
            source.put(button, symbol("Button", "com.example.Button", "two", "ui/Button.kt"))
            source.put(call, callRecord("call-identity-1", "one", "ui/Panel.kt"))

            convert(source).use { store ->
                assertEquals(
                    keys(source, "Panel", exact = true),
                    keys(store, "Panel", exact = true),
                )
                assertEquals(keys(source, "Pan", exact = false), keys(store, "Pan", exact = false))
                assertEquals(
                    keys(source, "com.example.Panel", exact = true),
                    keys(store, "com.example.Panel", exact = true),
                )
                assertEquals(emptyList(), keys(store, "Missing", exact = true))

                assertEquals(
                    symbolsInFile(source, "one", "ui/Panel.kt"),
                    symbolsInFile(store, "one", "ui/Panel.kt"),
                )
                assertEquals(
                    symbolsInFile(source, "one", "ui/Other.kt"),
                    symbolsInFile(store, "one", "ui/Other.kt"),
                )
                assertEquals(
                    callsInFile(source, "one", "ui/Panel.kt"),
                    callsInFile(store, "one", "ui/Panel.kt"),
                )
                assertEquals(
                    callsInFile(source, "two", "ui/Panel.kt"),
                    callsInFile(store, "two", "ui/Panel.kt"),
                )
            }
        }
    }

    @Test
    fun `alias terms emit each symbol once through the canonical term`() {
        openXodus("source").use { source ->
            val shared = CodeIndexKey.symbolDefinition("a.First", "one", "First.kt", 1, 1)
            val other = CodeIndexKey.symbolDefinition("b.Second", "two", "Second.kt", 1, 1)
            source.put(
                shared,
                symbol("First", "a.First", "one", "First.kt")
                    .copy(aliases = listOf("Shared", "Shared")),
            )
            source.put(
                other,
                symbol("Second", "b.Second", "two", "Second.kt").copy(aliases = listOf("Shared")),
            )

            convert(source).use { store ->
                assertEquals(
                    keys(source, "Shared", exact = true),
                    keys(store, "Shared", exact = true),
                )
                assertEquals(
                    listOf(shared, other).map { it.value },
                    keys(store, "Shared", exact = true),
                )
            }
        }
    }

    @Test
    fun `newer segment overrides older keys and tombstones shadow them`() {
        val kept = CodeIndexKey.sym("com.example.Kept")
        val replaced = CodeIndexKey.sym("com.example.Replaced")
        val removed = CodeIndexKey.sym("com.example.Removed")
        openXodus("base").use { base ->
            base.put(kept, MetaIndexerVersionRecord("kept"))
            base.put(replaced, MetaIndexerVersionRecord("old"))
            base.put(removed, MetaIndexerVersionRecord("doomed"))
        }
        val baseSegment = openXodus("base").use { reopened -> writeSegmentFile("base", reopened) }
        openXodus("delta").use { delta ->
            delta.put(replaced, MetaIndexerVersionRecord("new"))
            delta.delete(removed)
        }
        val deltaSegment =
            openXodus("delta").use { reopened ->
                writeSegmentFile("delta", reopened, listOf(removed))
            }

        openStore(baseSegment, deltaSegment).use { store ->
            assertEquals(MetaIndexerVersionRecord("kept"), store.get(kept))
            assertEquals(MetaIndexerVersionRecord("new"), store.get(replaced))
            assertNull(store.get(removed))
            assertEquals(
                listOf(kept.value, replaced.value),
                store.prefixScan("sym:").map { it.first.value }.toList(),
            )
            assertEquals(
                listOf("kept", "new"),
                store
                    .prefixScan("sym:")
                    .map { it.second }
                    .map { record -> (record as MetaIndexerVersionRecord).version }
                    .toList(),
            )
        }
    }

    @Test
    fun `read-only store rejects mutation and transaction only runs the block`() {
        openXodus("source").use { source ->
            val key = CodeIndexKey.sym("com.example.Immutable")
            source.put(key, MetaIndexerVersionRecord("v"))
            convert(source).use { store ->
                assertFailsWith<IllegalStateException> {
                    store.put(key, MetaIndexerVersionRecord("x"))
                }
                assertFailsWith<IllegalStateException> { store.delete(key) }
                val result = store.transaction { "ran" }
                assertEquals("ran", result)
            }
        }
    }

    @Test
    fun `forEachPrefix stops early without decoding the remaining tail`() {
        openXodus("source").use { source ->
            val first = CodeIndexKey.sym("com.example.A")
            val second = CodeIndexKey.sym("com.example.B")
            source.put(first, MetaIndexerVersionRecord("a"))
            source.put(second, MetaIndexerVersionRecord("b"))
            convert(source).use { store ->
                val visited = mutableListOf<CodeIndexKey>()
                store.forEachPrefix("sym:") { key, _ ->
                    visited += key
                    false
                }
                assertEquals(listOf(first), visited)
            }
        }
    }

    @Test
    fun `symbol file lookups honor newest-segment precedence`() {
        val original = CodeIndexKey.symbolDefinition("com.example.Moved", "one", "Old.kt", 1, 1)
        openXodus("precedence-base").use { base ->
            base.put(original, symbol("Moved", "com.example.Moved", "one", "Old.kt"))
        }
        val baseSegment =
            openXodus("precedence-base").use { reopened ->
                writeSegmentFile("precedence-base", reopened)
            }
        val moved = CodeIndexKey.symbolDefinition("com.example.Moved", "one", "New.kt", 2, 1)
        openXodus("precedence-delta").use { delta ->
            delta.put(moved, symbol("Moved", "com.example.Moved", "one", "New.kt"))
        }
        val deltaSegment =
            openXodus("precedence-delta").use { reopened ->
                writeSegmentFile("precedence-delta", reopened, listOf(original))
            }

        openStore(baseSegment, deltaSegment).use { store ->
            assertEquals(listOf(moved), symbolsInFile(store, "one", "New.kt"))
            assertEquals(emptyList(), symbolsInFile(store, "one", "Old.kt"))
        }
    }

    private fun openXodus(name: String): XodusCodeIndexStore =
        XodusCodeIndexStore.open(tempDir.resolve("$name.xodus"))

    private fun convert(source: CodeIndexStore): SegmentCodeIndexStore {
        val path = tempDir.resolve("${source.hashCode()}-${files.size}.segment")
        val file = SegmentCodeIndexStore.writeFrom(source, path)
        files += file
        return SegmentCodeIndexStore.open(listOf(file))
    }

    private fun writeSegmentFile(
        name: String,
        source: CodeIndexStore,
        tombstoneKeys: List<CodeIndexKey> = emptyList(),
    ): MappedSegmentFile {
        val path = tempDir.resolve("$name.segment")
        return SegmentCodeIndexStore.writeFrom(source, path, tombstoneKeys)
    }

    private fun openStore(vararg segmentFiles: MappedSegmentFile): SegmentCodeIndexStore {
        files += segmentFiles
        return SegmentCodeIndexStore.open(segmentFiles.toList())
    }

    private fun keys(store: CodeIndexStore, name: String, exact: Boolean): List<String> =
        buildList {
            store.forEachSymbolMatching(name, !exact) { key, _ ->
                add(key.value)
                true
            }
        }

    private fun symbolsInFile(
        store: CodeIndexStore,
        origin: String,
        file: String,
    ): List<CodeIndexKey> = buildList {
        store.forEachSymbolInFile(origin, file) { key, _ ->
            add(key)
            true
        }
    }

    private fun callsInFile(
        store: CodeIndexStore,
        origin: String,
        file: String,
    ): List<CodeIndexKey> = buildList {
        store.forEachCallInFile(origin, file) { key, _ ->
            add(key)
            true
        }
    }

    private fun symbol(
        name: String,
        fqn: String,
        originId: String,
        relativeFile: String,
    ): SymbolRecord =
        SymbolRecord(
            fqn = fqn,
            relativeFile = relativeFile,
            line = 1,
            kind = "class",
            name = name,
            originId = originId,
        )

    private fun callRecord(
        identity: String,
        originId: String,
        relativeFile: String,
    ): CallSiteRecord =
        CallSiteRecord(
            identity = identity,
            calleeName = "target",
            candidateSymbolFqns = emptyList(),
            relativeFile = relativeFile,
            startLine = 1,
            startColumn = 1,
            startOffset = 0,
            endLine = 1,
            endColumn = 8,
            endOffset = 7,
            confidence = "UNRESOLVED",
            originId = originId,
        )
}
