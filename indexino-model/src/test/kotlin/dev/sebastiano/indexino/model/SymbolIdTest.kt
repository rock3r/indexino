package dev.sebastiano.indexino.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SymbolIdTest {
    @Test
    fun `symbol IDs are validated structural values`() {
        val first = SymbolId.of("sample.Panel")
        val equal = SymbolId.of("sample.Panel")
        val other = SymbolId.of("sample.Other")

        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertNotEquals(first, other)
        assertEquals("sample.Panel", first.value)
        assertTrue(first.toString().contains(first.value))
        assertFailsWith<IllegalArgumentException> { SymbolId.of(" ") }
    }

    @Test
    fun `symbol ID factory is a JVM static method`() {
        val factory = SymbolId::class.java.getMethod("of", String::class.java)

        assertTrue(java.lang.reflect.Modifier.isStatic(factory.modifiers))
    }
}
