package dev.sebastiano.indexino.core.store

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorktreeOverlayIndexStoreTest {
    private lateinit var base: XodusCodeIndexStore
    private lateinit var delta: XodusCodeIndexStore
    private lateinit var tempDir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("worktree-overlay-store-")
        base = XodusCodeIndexStore.open(tempDir.resolve("base.xodus"), readOnly = false)
        delta = XodusCodeIndexStore.open(tempDir.resolve("delta.xodus"), readOnly = false)
    }

    @AfterTest
    fun tearDown() {
        base.close()
        delta.close()
    }

    @Test
    fun `close releases the base even when delta close fails`() {
        val closed = mutableListOf<String>()
        val deltaFailure = IllegalStateException("delta close")
        val baseFailure = IllegalArgumentException("base close")
        val throwingBase =
            object : CodeIndexStore by base {
                override fun close() {
                    closed += "base"
                    throw baseFailure
                }
            }
        val throwingDelta =
            object : CodeIndexStore by delta {
                override fun close() {
                    closed += "delta"
                    throw deltaFailure
                }
            }
        val overlay = WorktreeOverlayIndexStore(throwingBase, throwingDelta, emptyList())
        val failure = assertFailsWith<IllegalStateException> { overlay.close() }
        assertSame(deltaFailure, failure)
        assertEquals(listOf("delta", "base"), closed)
        assertSame(baseFailure, failure.suppressed.single())
    }

    @Test
    fun `tombstone prefixes hide base keys but not delta keys for the same file`() {
        val relativeFile = "ui/src/main/kotlin/Panel.kt"
        val tombstone = WorktreeOverlayIndexStore.tombstonePrefixForRelativeFile(relativeFile)
        val baseKey =
            CodeIndexKey.symbolDefinition("ActionButton", "workspace", relativeFile, 11, 0)
        val deltaKey =
            CodeIndexKey.symbolDefinition("ForkActionButton", "workspace", relativeFile, 11, 0)
        base.put(baseKey, symbol("ActionButton", relativeFile))
        delta.put(deltaKey, symbol("ForkActionButton", relativeFile))

        val overlay = WorktreeOverlayIndexStore(base, delta, listOf(tombstone))
        try {
            assertNull(overlay.get(baseKey))
            assertEquals(symbol("ForkActionButton", relativeFile), overlay.get(deltaKey))
            val names =
                overlay
                    .prefixScan("sym:")
                    .map { (_, record) -> (record as SymbolRecord).name }
                    .toSet()
            assertEquals(setOf("ForkActionButton"), names)
        } finally {
            overlay.close()
        }
    }

    @Test
    fun `delta overrides base when no tombstone applies`() {
        val relativeFile = "ui/src/main/kotlin/Other.kt"
        val key = CodeIndexKey.symbolDefinition("Panel", "workspace", relativeFile, 1, 0)
        base.put(key, symbol("Panel", relativeFile, line = 1))
        delta.put(key, symbol("Panel", relativeFile, line = 1, kind = "class"))

        val overlay = WorktreeOverlayIndexStore(base, delta, emptyList())
        try {
            assertEquals("class", (overlay.get(key) as SymbolRecord).kind)
        } finally {
            overlay.close()
        }
    }

    @Test
    fun `symbol candidates mask tombstones and base aliases removed by delta replacement`() {
        val removedFile = "ui/Removed.kt"
        val replacedKey =
            CodeIndexKey.symbolDefinition("pkg.Old", "workspace", "ui/Replaced.kt", 1, 0)
        val removedKey =
            CodeIndexKey.symbolDefinition("pkg.Removed", "workspace", removedFile, 1, 0)
        base.put(replacedKey, symbol("Old", "ui/Replaced.kt", aliases = listOf("Legacy")))
        base.put(removedKey, symbol("Removed", removedFile, aliases = listOf("Legacy")))
        delta.put(replacedKey, symbol("New", "ui/Replaced.kt"))
        val overlay =
            WorktreeOverlayIndexStore(
                base,
                delta,
                listOf(WorktreeOverlayIndexStore.tombstonePrefixForRelativeFile(removedFile)),
            )
        try {
            val found = mutableListOf<CodeIndexKey>()
            overlay.forEachSymbolMatching("Legacy", prefix = false) { key, _ ->
                found += key
                true
            }
            assertEquals(emptyList(), found)
        } finally {
            overlay.close()
        }
    }

    @Test
    fun `file call lookup masks moved delta rows and tombstones without scanning`() {
        val call =
            CallSiteRecord(
                identity = "original",
                calleeName = "target",
                candidateSymbolFqns = emptyList(),
                relativeFile = "Old.kt",
                originId = "workspace",
                startLine = 1,
                startColumn = 1,
                startOffset = 0,
                endLine = 1,
                endColumn = 8,
                endOffset = 7,
                confidence = "UNRESOLVED",
            )
        val moved = CodeIndexKey.call("moved")
        val removed = CodeIndexKey.call("workspace:Old.kt:removed")
        val kept = CodeIndexKey.call("kept")
        base.put(moved, call)
        base.put(removed, call)
        base.put(kept, call)
        delta.put(moved, call.copy(originId = "git:nested", relativeFile = "New.kt"))
        fun indexedOnly(store: CodeIndexStore) =
            object : CodeIndexStore by store {
                override fun prefixScan(
                    prefix: String
                ): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> = error("Unexpected full scan")
            }
        val overlay =
            WorktreeOverlayIndexStore(indexedOnly(base), indexedOnly(delta), listOf(":Old.kt:"))
        val old = mutableListOf<CodeIndexKey>()
        val result = runCatching {
            overlay.forEachCallInFile("workspace", "Old.kt") { key, _ ->
                old += key
                true
            }
        }
        assertTrue(result.isSuccess, "File lookup scanned the base: ${result.exceptionOrNull()}")
        assertEquals(listOf(kept), old)
        val new = mutableListOf<CodeIndexKey>()
        overlay.forEachCallInFile("git:nested", "New.kt") { key, _ ->
            new += key
            false
        }
        assertEquals(listOf(moved), new)
    }

    private fun symbol(
        name: String,
        relativeFile: String,
        line: Int = 11,
        kind: String = "function",
        aliases: List<String> = emptyList(),
    ): SymbolRecord =
        SymbolRecord(
            fqn = name,
            relativeFile = relativeFile,
            originId = "workspace",
            line = line,
            column = 0,
            kind = kind,
            name = name,
            language = "kotlin",
            aliases = aliases,
        )
}
