package dev.sebastiano.indexino.core.key

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeIndexKeyTest {
    @Test
    fun `plugin fact key round-trips`() {
        val key = CodeIndexKey.pluginFact("dev.example.plugin", "src/Panel.kt", "site:142:12")
        assertEquals("plugin:dev.example.plugin:src/Panel.kt:site:142:12", key.value)
        assertEquals("plugin", key.namespace())
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
    fun `file prefix helper matches plugin fact keys`() {
        val prefix = CodeIndexKey.pluginFactFilePrefix("dev.example.plugin", "ui/Panel.kt")
        val key = CodeIndexKey.pluginFact("dev.example.plugin", "ui/Panel.kt", "site:10:5")
        assertTrue(key.hasPrefix(prefix))
    }
}
