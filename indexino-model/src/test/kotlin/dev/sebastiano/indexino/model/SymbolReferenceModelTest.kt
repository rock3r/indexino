package dev.sebastiano.indexino.model

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SymbolReferenceModelTest {
    @Test
    fun `symbol queries are immutable structural values`() {
        assertTrue(
            Modifier.isStatic(
                SymbolQuery::class.java.getMethod("named", String::class.java).modifiers
            )
        )
        assertTrue(
            Modifier.isStatic(
                SymbolQuery::class.java.getMethod("inFile", SourceFile::class.java).modifiers
            )
        )

        val first = SymbolQuery.named("Panel").withKind("class").withLanguage("kotlin")
        val prefix = first.withMatch(NameMatchMode.PREFIX)

        assertNotEquals(first, prefix)
        assertEquals("Panel", first.name)
        assertEquals("class", first.kind)
        assertEquals("kotlin", first.language)
        assertTrue(prefix.toString().contains("match=PREFIX"))

        val file = SourceFile.of(SourceOriginId.of("workspace"), "src/Panel.kt", "src/Panel.kt")
        val fileQuery = SymbolQuery.inFile(file)
        assertEquals(file, fileQuery.file)
        assertNull(fileQuery.name)

        assertFailsWith<IllegalArgumentException> { SymbolQuery.named(" ") }
        assertFailsWith<IllegalArgumentException> { first.withKind(" ") }
        assertFailsWith<IllegalArgumentException> { first.withLanguage(" ") }
    }

    @OptIn(IndexinoInternalApi::class)
    @Test
    fun `symbol and reference results defensively copy engine data`() {
        val symbolId = SymbolId.of("symbol:Panel")
        val ownerId = SymbolId.of("symbol:Owner")
        val file = SourceFile.of(SourceOriginId.of("workspace"), "src/Panel.kt", "src/Panel.kt")
        val location = SourceLocation.of(file, 4, 3, 28)
        val aliases = mutableListOf("sample.Panel")
        val symbol =
            Symbol(
                symbolId,
                "Panel",
                "class",
                "kotlin",
                location,
                null,
                ownerId,
                "sample.Panel",
                null,
                aliases,
            )
        aliases += "changed"

        assertEquals(listOf("sample.Panel"), symbol.aliases)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST") (symbol.aliases as MutableList<Any?>).add(null)
        }

        val candidateIds = mutableListOf(symbolId)
        val reference =
            Reference(symbolId, "Panel", "kotlin", location, "sample", candidateIds, null)
        candidateIds += ownerId

        assertEquals(listOf(symbolId), reference.candidateSymbolIds)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (reference.candidateSymbolIds as MutableList<Any?>).add(null)
        }

        val equalReference =
            Reference(symbolId, "Panel", "kotlin", location, "sample", listOf(symbolId), null)
        assertEquals(reference, equalReference)
        assertEquals(reference.hashCode(), equalReference.hashCode())

        assertTrue(
            Modifier.isStatic(
                ReferenceQuery::class.java.getMethod("to", SymbolId::class.java).modifiers
            )
        )
        val query = ReferenceQuery.to(symbolId)
        assertEquals(symbolId, query.symbolId)
    }
}
