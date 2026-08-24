package dev.sebastiano.indexino.core.store

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    private fun symbol(
        name: String,
        relativeFile: String,
        line: Int = 11,
        kind: String = "function",
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
        )
}
