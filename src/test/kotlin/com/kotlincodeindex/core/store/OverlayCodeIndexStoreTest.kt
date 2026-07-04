package com.kotlincodeindex.core.store

import com.kotlincodeindex.core.key.CodeIndexKey
import com.kotlincodeindex.core.record.ComposeSelectionSiteRecord
import com.kotlincodeindex.core.record.MetaIndexerVersionRecord
import com.kotlincodeindex.core.record.SelectionContainerRef
import com.kotlincodeindex.core.xodus.XodusCodeIndexStore
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
        val key = CodeIndexKey.composeSelectionSite("Panel.kt", 1, 1)
        val record = composeRecord("New")
        overlay.put(key, record)
        assertEquals(record, overlay.get(key))
        assertNull(base.get(key))
    }

    @Test
    fun `prefixScan merges delta over base`() {
        val prefix = "compose:selection-site:ui/"
        val baseKey = CodeIndexKey.composeSelectionSite("ui/A.kt", 1, 1)
        val deltaKey = CodeIndexKey.composeSelectionSite("ui/B.kt", 2, 1)
        base.put(baseKey, composeRecord("Base"))
        delta.put(deltaKey, composeRecord("Delta"))
        val keys = overlay.prefixScan(prefix).map { it.first }.toSet()
        assertEquals(setOf(baseKey, deltaKey), keys)
    }

    private fun composeRecord(callee: String): ComposeSelectionSiteRecord =
        ComposeSelectionSiteRecord(
            callee = callee,
            inSelectionContainer = false,
            selectionContainerCount = 0,
            excludedByDisableSelection = false,
            selectionContainers = listOf(
                SelectionContainerRef("ui/A.kt", 1, "Fn"),
            ),
        )
}
