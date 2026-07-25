package dev.sebastiano.indexino.model

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaginationTest {
    @Test
    fun `query options expose validated Java factories`() {
        assertTrue(
            Modifier.isStatic(
                QueryOptions::class.java.getMethod("page", Int::class.javaPrimitiveType).modifiers
            )
        )
        assertTrue(
            Modifier.isStatic(
                QueryOptions::class
                    .java
                    .getMethod("page", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .modifiers
            )
        )
        assertTrue(
            Modifier.isStatic(
                QueryOptions::class
                    .java
                    .getMethod("after", Int::class.javaPrimitiveType, String::class.java)
                    .modifiers
            )
        )

        val firstPage = QueryOptions.page(50)
        val equalFirstPage = QueryOptions.page(50, 0)
        val laterPage = QueryOptions.page(50, 100)
        val cursorPage = QueryOptions.after(25, "next")

        assertEquals(firstPage, equalFirstPage)
        assertEquals(firstPage.hashCode(), equalFirstPage.hashCode())
        assertEquals(100, laterPage.offset)
        assertEquals("next", cursorPage.afterCursor)
        assertNull(firstPage.afterCursor)
        assertTrue(firstPage.toString().contains("limit=50"))

        assertFailsWith<IllegalArgumentException> { QueryOptions.page(0) }
        assertFailsWith<IllegalArgumentException> { QueryOptions.page(10, -1) }
        assertFailsWith<IllegalArgumentException> { QueryOptions.after(10, " ") }
    }

    @OptIn(IndexinoInternalApi::class)
    @Test
    fun `query pages defensively copy their items`() {
        val mutableItems = mutableListOf("first", "second")
        val page = QueryPage(mutableItems, 0, 2, true, null, 3)
        val equalPage = QueryPage(listOf("first", "second"), 0, 2, true, null, 3)

        mutableItems += "third"

        assertEquals(listOf("first", "second"), page.items)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST") (page.items as MutableList<Any?>).add("third")
        }
        assertEquals(page, equalPage)
        assertEquals(page.hashCode(), equalPage.hashCode())
        assertTrue(page.toString().contains("hasMore=true"))

        assertFailsWith<IllegalArgumentException> {
            QueryPage(emptyList<String>(), -1, 10, false, null, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            QueryPage(emptyList<String>(), 0, 0, false, null, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            QueryPage(emptyList<String>(), 0, 10, false, null, -1)
        }
    }
}
