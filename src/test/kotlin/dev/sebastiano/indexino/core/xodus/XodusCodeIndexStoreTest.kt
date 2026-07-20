package dev.sebastiano.indexino.core.xodus

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.ComposeSelectionSiteRecord
import dev.sebastiano.indexino.core.record.MetaIndexerVersionRecord
import dev.sebastiano.indexino.core.record.SelectionContainerRef
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class XodusCodeIndexStoreTest {
    private lateinit var store: XodusCodeIndexStore
    private lateinit var tempDir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("xodus-test-")
        store = XodusCodeIndexStore.open(tempDir.resolve("base.xodus"))
    }

    @AfterTest
    fun tearDown() {
        store.close()
    }

    @Test
    fun `put get round-trips typed records`() {
        val key = CodeIndexKey.metaIndexerVersion()
        val record = MetaIndexerVersionRecord(version = "0.1.0")
        store.put(key, record)
        assertEquals(record, store.get(key))
    }

    @Test
    fun `delete removes key`() {
        val key = CodeIndexKey.sym("com.example.Foo")
        store.put(key, MetaIndexerVersionRecord("unused"))
        store.delete(key)
        assertNull(store.get(key))
    }

    @Test
    fun `prefix scan returns keys in lexicographic order`() {
        val prefix = "compose:selection-site:ui/Panel.kt:"
        val key1 = CodeIndexKey.composeSelectionSite("ui/Panel.kt", 10, 1)
        val key2 = CodeIndexKey.composeSelectionSite("ui/Panel.kt", 20, 1)
        val keyOther = CodeIndexKey.composeSelectionSite("ui/Other.kt", 10, 1)

        store.put(key2, composeRecord("Later"))
        store.put(key1, composeRecord("Earlier"))
        store.put(keyOther, composeRecord("Other"))

        val scanned = store.prefixScan(prefix).toList()
        assertEquals(2, scanned.size)
        assertEquals(listOf(key1, key2), scanned.map { it.first })
        assertEquals("Earlier", (scanned[0].second as ComposeSelectionSiteRecord).callee)
        assertEquals("Later", (scanned[1].second as ComposeSelectionSiteRecord).callee)
    }

    private fun composeRecord(callee: String): ComposeSelectionSiteRecord =
        ComposeSelectionSiteRecord(
            callee = callee,
            inSelectionContainer = true,
            selectionContainerCount = 1,
            excludedByDisableSelection = false,
            selectionContainers =
                listOf(SelectionContainerRef(file = "ui/Panel.kt", line = 5, function = "Panel")),
        )
}
