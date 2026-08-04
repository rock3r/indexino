package dev.sebastiano.indexino.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ResourceModelTest {
    @OptIn(IndexinoInternalApi::class)
    @Test
    fun `resource identities queries and usages are structural values`() {
        val appTitle = ResourceId.of("com.example.app", "string", "title")
        val featureTitle = ResourceId.of("com.example.feature", "string", "title")
        assertNotEquals(appTitle, featureTitle)
        assertEquals(appTitle, ResourceId.of("com.example.app", "string", "title"))
        assertEquals(
            appTitle.hashCode(),
            ResourceId.of("com.example.app", "string", "title").hashCode(),
        )
        assertTrue(appTitle.toString().contains("com.example.app"))

        val exact = ResourceQuery.named(appTitle)
        val packageScoped =
            ResourceQuery.of(packageName = "com.example.app", type = null, name = null)
        val typed = ResourceQuery.of(packageName = null, type = "string", name = null)
        assertEquals(appTitle, exact.id)
        assertEquals("com.example.app", packageScoped.packageName)
        assertEquals("string", typed.type)
        assertNotEquals(exact, typed)
        assertFailsWith<IllegalArgumentException> {
            ResourceQuery.of(packageName = null, type = null, name = "title")
        }
        assertFailsWith<IllegalArgumentException> { ResourceId.of(" ", "string", "title") }

        val location =
            SourceLocation.of(
                SourceFile.of(
                    SourceOriginId.of("workspace"),
                    "src/main/kotlin/Screen.kt",
                    "Screen.kt",
                ),
                line = 4,
                column = 18,
                offset = 72,
            )
        val usage = ResourceUsage(appTitle, location, "kotlin")
        assertEquals(usage, ResourceUsage(appTitle, location, "kotlin"))
        assertEquals(usage.hashCode(), ResourceUsage(appTitle, location, "kotlin").hashCode())
        assertTrue(usage.toString().contains("kotlin"))
        assertFailsWith<IllegalArgumentException> { ResourceUsage(appTitle, location, " ") }
    }
}
