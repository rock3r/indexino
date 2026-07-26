package dev.sebastiano.indexino.model

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallFactModelTest {
    @Test
    fun `call IDs and query factories are Java usable structural values`() {
        val id = CallSiteId.of("call:one")
        assertEquals(id, CallSiteId.of("call:one"))
        assertFailsWith<IllegalArgumentException> { CallSiteId.of(" ") }
        assertTrue(
            Modifier.isStatic(CallSiteId::class.java.getMethod("of", String::class.java).modifiers)
        )

        val file = SourceFile.of(SourceOriginId.of("workspace"), "src/App.kt", "src/App.kt")
        assertEquals("Panel", CallQuery.to("Panel").calleeName)
        assertEquals(id, CallQuery.byId(id).callSiteId)
        assertEquals(file, CallQuery.inFile(file).file)
        assertNull(CallQuery.enclosedBy(SymbolId.of("symbol:owner")).calleeName)
        assertFailsWith<IllegalArgumentException> { CallQuery.to(" ") }
    }

    @OptIn(IndexinoInternalApi::class)
    @Test
    fun `call facts defensively copy lists and retain nested relationships`() {
        val file = SourceFile.of(SourceOriginId.of("workspace"), "src/App.kt", "src/App.kt")
        val range =
            SourceRange.of(SourceLocation.of(file, 1, 1, 0), SourceLocation.of(file, 1, 10, 9))
        val nested = mutableListOf(CallSiteId.of("call:child"))
        val argument = CallArgument(0, "content", ArgumentKind.TRAILING_LAMBDA, range, nested)
        nested += CallSiteId.of("call:other")
        assertEquals(listOf(CallSiteId.of("call:child")), argument.nestedCallIds)

        val candidates = mutableListOf(SymbolId.of("symbol:Panel"))
        val call =
            CallSite(
                CallSiteId.of("call:parent"),
                "Panel",
                candidates,
                null,
                SymbolId.of("symbol:owner"),
                null,
                range,
                listOf(argument),
                ResolutionConfidence.RESOLVED,
            )
        candidates += SymbolId.of("symbol:other")
        assertEquals(listOf(SymbolId.of("symbol:Panel")), call.candidateSymbolIds)
        assertEquals(ArgumentKind.TRAILING_LAMBDA, call.arguments.single().kind)
        assertEquals(ResolutionConfidence.RESOLVED, call.confidence)
    }
}
