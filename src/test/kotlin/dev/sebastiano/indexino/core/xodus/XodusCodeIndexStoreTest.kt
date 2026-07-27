package dev.sebastiano.indexino.core.xodus

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.MetaIndexerVersionRecord
import dev.sebastiano.indexino.core.record.PluginFactRecord
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
        val pluginId = "dev.example.plugin"
        val prefix = CodeIndexKey.pluginFactFilePrefix(pluginId, "ui/Panel.kt")
        val key1 = CodeIndexKey.pluginFact(pluginId, "ui/Panel.kt", "site:10:1")
        val key2 = CodeIndexKey.pluginFact(pluginId, "ui/Panel.kt", "site:20:1")
        val keyOther = CodeIndexKey.pluginFact(pluginId, "ui/Other.kt", "site:10:1")

        store.put(key2, pluginFact("Later"))
        store.put(key1, pluginFact("Earlier"))
        store.put(keyOther, pluginFact("Other"))

        val scanned = store.prefixScan(prefix).toList()
        assertEquals(listOf(key1, key2), scanned.map { it.first })
        assertEquals("Earlier", (scanned[0].second as PluginFactRecord).encodedValue)
        assertEquals("Later", (scanned[1].second as PluginFactRecord).encodedValue)
    }

    @Test
    fun `forEachPrefix stops without materializing remaining cursor rows`() {
        val pluginId = "dev.example.plugin"
        val prefix = CodeIndexKey.pluginFactFilePrefix(pluginId, "ui/Panel.kt")
        val first = CodeIndexKey.pluginFact(pluginId, "ui/Panel.kt", "site:10:1")
        store.put(first, pluginFact("First"))
        store.put(
            CodeIndexKey.pluginFact(pluginId, "ui/Panel.kt", "site:20:1"),
            pluginFact("Second"),
        )

        val visited = mutableListOf<CodeIndexKey>()
        store.forEachPrefix(prefix) { key, _ ->
            visited += key
            false
        }

        assertEquals(listOf(first), visited)
    }

    private fun pluginFact(value: String): PluginFactRecord =
        PluginFactRecord(
            pluginId = "dev.example.plugin",
            relativeFile = "ui/Panel.kt",
            factKey = "site",
            encodedValue = value,
        )
}
