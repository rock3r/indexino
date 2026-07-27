package dev.sebastiano.indexino.core.store

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.MetaIndexerVersionRecord
import dev.sebastiano.indexino.core.record.PluginFactRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OverlayCodeIndexStoreTest {
    private lateinit var base: XodusCodeIndexStore
    private lateinit var delta: XodusCodeIndexStore
    private lateinit var overlay: OverlayCodeIndexStore
    private lateinit var tempDir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("overlay-test-")
        base = XodusCodeIndexStore.open(tempDir.resolve("base.xodus"), readOnly = false)
        delta = XodusCodeIndexStore.open(tempDir.resolve("delta.xodus"), readOnly = false)
        overlay = OverlayCodeIndexStore(base, delta)
    }

    @AfterTest
    fun tearDown() {
        overlay.close()
    }

    @Test
    fun `delta overrides base on get`() {
        val key = CodeIndexKey.metaIndexerVersion()
        base.put(key, MetaIndexerVersionRecord("base"))
        delta.put(key, MetaIndexerVersionRecord("delta"))
        assertEquals(MetaIndexerVersionRecord("delta"), overlay.get(key))
    }

    @Test
    fun `get falls back to base when delta missing`() {
        val key = CodeIndexKey.sym("com.foo.Bar")
        base.put(key, MetaIndexerVersionRecord("base-only"))
        assertEquals(MetaIndexerVersionRecord("base-only"), overlay.get(key))
    }

    @Test
    fun `put writes to delta only`() {
        val key = pluginKey("Panel.kt", "site:1:1")
        val record = pluginFact("New")
        overlay.put(key, record)
        assertEquals(record, overlay.get(key))
        assertNull(base.get(key))
    }

    @Test
    fun `prefixScan merges delta over base`() {
        val prefix = "plugin:dev.example.plugin:ui/"
        val baseKey = pluginKey("ui/A.kt", "site:1:1")
        val deltaKey = pluginKey("ui/B.kt", "site:2:1")
        base.put(baseKey, pluginFact("Base"))
        delta.put(deltaKey, pluginFact("Delta"))
        val keys = overlay.prefixScan(prefix).map { it.first }.toSet()
        assertEquals(setOf(baseKey, deltaKey), keys)
    }

    private fun pluginKey(relativeFile: String, factKey: String): CodeIndexKey =
        CodeIndexKey.pluginFact("dev.example.plugin", relativeFile, factKey)

    private fun pluginFact(value: String): PluginFactRecord =
        PluginFactRecord(
            pluginId = "dev.example.plugin",
            relativeFile = "ui/A.kt",
            factKey = "site",
            encodedValue = value,
        )
}
