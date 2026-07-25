package dev.sebastiano.indexino.model

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceValuesTest {
    @Test
    fun `source values expose Java factories and structural equality`() {
        assertTrue(
            Modifier.isStatic(
                SourceOriginId::class.java.getMethod("of", String::class.java).modifiers
            )
        )
        assertTrue(
            Modifier.isStatic(
                SourceFile::class
                    .java
                    .getMethod(
                        "of",
                        SourceOriginId::class.java,
                        String::class.java,
                        String::class.java,
                    )
                    .modifiers
            )
        )

        val origin = SourceOriginId.of("workspace")
        val equalOrigin = SourceOriginId.of("workspace")
        val file = SourceFile.of(origin, "src/Panel.kt", "src/Panel.kt")
        val equalFile = SourceFile.of(equalOrigin, "src/Panel.kt", "src/Panel.kt")
        val differentlyDisplayedFile =
            SourceFile.of(equalOrigin, "src/Panel.kt", "workspace/src/Panel.kt")
        val start = SourceLocation.of(file, 3)
        val equalStart = SourceLocation.of(equalFile, 3)
        val end = SourceLocation.of(file, 3, 8, 42)
        val equalEnd = SourceLocation.of(equalFile, 3, 8, 42)
        val range = SourceRange.of(start, end)
        val equalRange = SourceRange.of(equalStart, equalEnd)

        assertEquals(origin, equalOrigin)
        assertEquals(file, equalFile)
        assertEquals(file, differentlyDisplayedFile)
        assertEquals(file.hashCode(), differentlyDisplayedFile.hashCode())
        assertEquals(start, equalStart)
        assertEquals(end, equalEnd)
        assertEquals(range, equalRange)
        assertEquals(range.hashCode(), equalRange.hashCode())
        assertNotEquals(start, end)
        assertEquals("src/Panel.kt", file.path)
        assertNull(start.column)
        assertEquals(42, end.offset)
        assertTrue(range.toString().contains("src/Panel.kt"))
    }

    @Test
    fun `source factories reject invalid identity and positions`() {
        assertFailsWith<IllegalArgumentException> { SourceOriginId.of(" ") }
        val origin = SourceOriginId.of("workspace")
        assertFailsWith<IllegalArgumentException> {
            SourceFile.of(origin, "/absolute/Panel.kt", "Panel.kt")
        }
        assertFailsWith<IllegalArgumentException> {
            SourceFile.of(origin, "../Panel.kt", "Panel.kt")
        }

        val firstFile = SourceFile.of(origin, "src/Panel.kt", "src/Panel.kt")
        val secondFile = SourceFile.of(origin, "src/Other.kt", "src/Other.kt")
        assertFailsWith<IllegalArgumentException> { SourceLocation.of(firstFile, 0, null, null) }
        assertFailsWith<IllegalArgumentException> { SourceLocation.of(firstFile, 1, -1, null) }
        assertFailsWith<IllegalArgumentException> { SourceLocation.of(firstFile, 1, null, -1) }

        val firstLocation = SourceLocation.of(firstFile, 1, 1, 0)
        val secondLocation = SourceLocation.of(secondFile, 1, 1, 0)
        assertFailsWith<IllegalArgumentException> { SourceRange.of(firstLocation, secondLocation) }
    }
}
