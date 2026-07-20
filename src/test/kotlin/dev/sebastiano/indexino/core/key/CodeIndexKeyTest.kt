package dev.sebastiano.indexino.core.key

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeIndexKeyTest {
    @Test
    fun `compose selection site key round-trips`() {
        val key = CodeIndexKey.composeSelectionSite("src/Panel.kt", 142, 12)
        assertEquals("compose:selection-site:src/Panel.kt:142:12", key.value)
        assertEquals("compose", key.namespace())
        assertEquals(key, CodeIndexKey.parse(key.value))
    }

    @Test
    fun `sym and file keys round-trip`() {
        val sym = CodeIndexKey.sym("com.foo.Bar")
        assertEquals("sym:com.foo.Bar", sym.value)
        assertEquals(sym, CodeIndexKey.parse(sym.value))

        val file = CodeIndexKey.file("src/Foo.kt", "sha256:abc")
        assertEquals("file:src/Foo.kt:sha256:abc", file.value)
        assertEquals(file, CodeIndexKey.parse(file.value))
    }

    @Test
    fun `file prefix helper matches selection site keys`() {
        val prefix = CodeIndexKey.composeSelectionSiteFilePrefix("ui/Panel.kt")
        val key = CodeIndexKey.composeSelectionSite("ui/Panel.kt", 10, 5)
        assertTrue(key.hasPrefix(prefix))
    }
}
