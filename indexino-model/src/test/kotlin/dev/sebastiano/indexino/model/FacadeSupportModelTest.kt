package dev.sebastiano.indexino.model

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FacadeSupportModelTest {
    @Test
    fun `facade identifiers are validated structural values`() {
        assertTrue(
            Modifier.isStatic(RefreshId::class.java.getMethod("of", String::class.java).modifiers)
        )
        assertTrue(
            Modifier.isStatic(
                WorkspaceGenerationId::class.java.getMethod("of", String::class.java).modifiers
            )
        )
        assertTrue(
            Modifier.isStatic(PluginId::class.java.getMethod("of", String::class.java).modifiers)
        )

        val refreshId = RefreshId.of("refresh-1")
        val equalRefreshId = RefreshId.of("refresh-1")
        val generationId = WorkspaceGenerationId.of("generation-1")
        val pluginId = PluginId.of("dev.sebastiano.selection-context")

        assertEquals(refreshId, equalRefreshId)
        assertEquals(refreshId.hashCode(), equalRefreshId.hashCode())
        assertTrue(refreshId.toString().contains("refresh-1"))
        assertTrue(generationId.toString().contains("generation-1"))
        assertTrue(pluginId.toString().contains("selection-context"))

        assertFailsWith<IllegalArgumentException> { RefreshId.of(" ") }
        assertFailsWith<IllegalArgumentException> { WorkspaceGenerationId.of(" ") }
        assertFailsWith<IllegalArgumentException> { PluginId.of(" ") }
    }

    @OptIn(IndexinoInternalApi::class)
    @Test
    fun `findings snapshot immutable properties`() {
        val mutableProperties = linkedMapOf("severity" to "warning")
        val finding =
            Finding(
                plugin = PluginId.of("dev.sebastiano.selection-context"),
                checkId = "interactive-in-selection",
                message = "Interactive call is selectable",
                range = null,
                properties = mutableProperties,
            )
        mutableProperties["severity"] = "error"

        assertEquals(mapOf("severity" to "warning"), finding.properties)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (finding.properties as MutableMap<String, String>)["severity"] = "error"
        }
    }

    @OptIn(IndexinoInternalApi::class)
    @Test
    fun `workspace provenance and failures are engine produced structural values`() {
        val originId = SourceOriginId.of("workspace")
        val originRevision =
            SourceOriginRevision(
                originId = originId,
                revision = "abc123",
                stateFingerprint = "state-1",
                expectedRevision = null,
            )
        val equalOriginRevision =
            SourceOriginRevision(
                originId = originId,
                revision = "abc123",
                stateFingerprint = "state-1",
                expectedRevision = null,
            )
        val otherOrigin =
            SourceOriginRevision(
                originId = SourceOriginId.of("other"),
                revision = "def456",
                stateFingerprint = "state-2",
                expectedRevision = "abc123",
            )
        val mutableOrigins = mutableListOf(otherOrigin, originRevision)
        val revision = WorkspaceRevision(fingerprint = "fingerprint-1", origins = mutableOrigins)
        mutableOrigins.clear()

        assertEquals(originRevision, equalOriginRevision)
        // WorkspaceRevision sorts origins by originId for stable equality.
        assertEquals(listOf(otherOrigin, originRevision), revision.origins)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST") (revision.origins as MutableList<Any?>).add(null)
        }
        assertFailsWith<IllegalArgumentException> {
            SourceOriginRevision(originId, " ", "state-1", null)
        }
        assertFailsWith<IllegalArgumentException> {
            SourceOriginRevision(originId, "abc123", " ", null)
        }
        assertFailsWith<IllegalArgumentException> { WorkspaceRevision(" ", listOf(originRevision)) }
        assertFailsWith<IllegalArgumentException> {
            WorkspaceRevision("fingerprint-1", emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            WorkspaceRevision("fingerprint-1", listOf(originRevision, equalOriginRevision))
        }

        assertNotEquals(IndexFailureCategory.INVALID_REQUEST, IndexFailureCategory.CLOSED)
        assertEquals("INVALID_REQUEST", IndexFailureCategory.INVALID_REQUEST.value)

        val failure =
            IndexFailure.of(
                IndexFailureCategory.INVALID_REQUEST,
                "bad-request",
                "invalid request",
                false,
            )
        val equalFailure =
            IndexFailure.of(
                IndexFailureCategory.INVALID_REQUEST,
                "bad-request",
                "invalid request",
                false,
            )
        assertEquals(failure, equalFailure)
        assertEquals(failure.hashCode(), equalFailure.hashCode())
        assertFailsWith<IllegalArgumentException> {
            IndexFailure.of(IndexFailureCategory.CLOSED, " ", "message", false)
        }
        assertFailsWith<IllegalArgumentException> {
            IndexFailure.of(IndexFailureCategory.CLOSED, "code", " ", false)
        }

        assertTrue(
            Modifier.isStatic(
                IndexFailure::class
                    .java
                    .getMethod(
                        "of",
                        IndexFailureCategory::class.java,
                        String::class.java,
                        String::class.java,
                        Boolean::class.javaPrimitiveType,
                    )
                    .modifiers
            )
        )
        // Host factory only — no public business constructors (Kotlin may emit a
        // DefaultConstructorMarker synthetic).
        assertTrue(
            IndexFailure::class.java.constructors.none { constructor ->
                constructor.parameterTypes.contentEquals(
                    arrayOf(
                        IndexFailureCategory::class.java,
                        String::class.java,
                        String::class.java,
                        Boolean::class.javaPrimitiveType,
                    )
                )
            }
        )
    }
}
