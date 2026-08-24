package dev.sebastiano.indexino.plugin.compose.decoration

import dev.sebastiano.indexino.model.ArgumentKind
import dev.sebastiano.indexino.model.CallArgument
import dev.sebastiano.indexino.model.CallSite
import dev.sebastiano.indexino.model.CallSiteId
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.ResolutionConfidence
import dev.sebastiano.indexino.model.SourceFile
import dev.sebastiano.indexino.model.SourceLocation
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.SourceRange
import dev.sebastiano.indexino.model.SymbolId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(IndexinoInternalApi::class)
class ModifierChainBuilderTest {
    private val origin = SourceOriginId.of("test")
    private val file = SourceFile.of(origin, "Sample.kt", "Sample.kt")

    @Test
    fun `direct modifier chain preserves order`() {
        val fillMax =
            call(
                "fillMaxWidth",
                receiver = "Modifier.padding(8)",
                offset = 30,
                id = "fill",
                parent = null,
            )
        val padding =
            call("padding", receiver = "Modifier", offset = 10, id = "padding", parent = fillMax)
        val text =
            composableCall(
                callee = "Text",
                modifierNested = listOf(padding.id, fillMax.id),
                offset = 0,
            )
        val callsById = mapOf(padding.id to padding, fillMax.id to fillMax, text.id to text)

        val site = ModifierChainBuilder.buildDecorationSite(text, callsById)

        assertTrue(site.hasModifierArgument)
        assertEquals(listOf("padding", "fillMaxWidth"), site.chain.links.map { it.calleeName })
        assertEquals(ModifierLinkKind.DIRECT, site.chain.links[0].kind)
    }

    @Test
    fun `then splits receiver and argument chains`() {
        val then =
            call("then", receiver = "Modifier.padding(8)", offset = 30, id = "then", parent = null)
        val padding =
            call("padding", receiver = "Modifier", offset = 10, id = "padding", parent = then)
        val background =
            call("background", receiver = "Modifier", offset = 50, id = "background", parent = then)
        val row =
            composableCall(
                callee = "Row",
                modifierNested = listOf(then.id, padding.id, background.id),
                offset = 0,
            )
        val callsById =
            mapOf(
                padding.id to padding,
                background.id to background,
                then.id to then,
                row.id to row,
            )

        val site = ModifierChainBuilder.buildDecorationSite(row, callsById)

        assertEquals(
            listOf("padding", "then", "background"),
            site.chain.links.map { it.calleeName },
        )
        assertEquals(ModifierLinkKind.THEN, site.chain.links[1].kind)
    }

    @Test
    fun `helper returned modifier is classified as helper`() {
        val helper = call("screenModifier", receiver = null, offset = 10, id = "helper")
        val box = composableCall(callee = "Box", modifierNested = listOf(helper.id), offset = 0)
        val callsById = mapOf(helper.id to helper, box.id to box)

        val site = ModifierChainBuilder.buildDecorationSite(box, callsById)

        assertEquals(ModifierLinkKind.HELPER, site.chain.links.single().kind)
        assertEquals("screenModifier", site.chain.links.single().calleeName)
    }

    @Test
    fun `composable without modifier records empty chain`() {
        val column = composableCall(callee = "Column", modifierNested = null, offset = 0)
        val site = ModifierChainBuilder.buildDecorationSite(column, mapOf(column.id to column))

        assertFalse(site.hasModifierArgument)
        assertTrue(site.chain.links.isEmpty())
    }

    @Test
    fun `conditional branches are grouped into a conditional link`() {
        val thenBranch =
            call(
                "fillMaxWidth",
                receiver = "Modifier",
                offset = 40,
                id = "then-branch",
                parent = null,
            )
        val elseBranch =
            call("padding", receiver = "Modifier", offset = 70, id = "else-branch", parent = null)
        val column =
            composableCall(
                callee = "Column",
                modifierNested = listOf(thenBranch.id, elseBranch.id),
                offset = 0,
            )
        val callsById =
            mapOf(thenBranch.id to thenBranch, elseBranch.id to elseBranch, column.id to column)

        val site = ModifierChainBuilder.buildDecorationSite(column, callsById)

        assertEquals(ModifierLinkKind.CONDITIONAL, site.chain.links.single().kind)
        assertEquals(2, site.chain.links.single().branches.size)
    }

    @Test
    fun `composed modifier is classified as composed`() {
        val composed = call("composed", receiver = "Modifier", offset = 20, id = "composed")
        val box = composableCall(callee = "Box", modifierNested = listOf(composed.id), offset = 0)
        val site =
            ModifierChainBuilder.buildDecorationSite(
                box,
                mapOf(composed.id to composed, box.id to box),
            )

        assertEquals(ModifierLinkKind.COMPOSED, site.chain.links.single().kind)
    }

    @Test
    fun `unresolved callee uses unresolved confidence`() {
        val unknown =
            call(
                "maybeModifier",
                receiver = null,
                offset = 10,
                id = "unknown",
                confidence = ResolutionConfidence.UNRESOLVED,
            )
        val box = composableCall(callee = "Box", modifierNested = listOf(unknown.id), offset = 0)
        val site =
            ModifierChainBuilder.buildDecorationSite(
                box,
                mapOf(unknown.id to unknown, box.id to box),
            )

        assertEquals(ModifierLinkKind.UNRESOLVED, site.chain.links.single().kind)
        assertEquals(ResolutionConfidence.UNRESOLVED, site.chainConfidence)
    }

    private fun composableCall(
        callee: String,
        modifierNested: List<CallSiteId>?,
        offset: Int,
    ): CallSite {
        val id = CallSiteId.of("call:$callee:$offset")
        val range = range(offset, offset + 80)
        val arguments =
            if (modifierNested == null) {
                emptyList()
            } else {
                listOf(
                    CallArgument(
                        position = 1,
                        resolvedName = "modifier",
                        kind = ArgumentKind.VALUE,
                        range = range(offset + 20, offset + 60),
                        nestedCallIds = modifierNested,
                    )
                )
            }
        return CallSite(
            id = id,
            calleeName = callee,
            candidateSymbolIds = emptyList(),
            receiver = null,
            enclosingSymbolId = null,
            parentCallId = null,
            range = range,
            arguments = arguments,
            confidence = ResolutionConfidence.RESOLVED,
        )
    }

    private fun call(
        callee: String,
        receiver: String?,
        offset: Int,
        id: String,
        parent: CallSite? = null,
        confidence: ResolutionConfidence = ResolutionConfidence.HEURISTIC,
    ): CallSite =
        CallSite(
            id = CallSiteId.of("call:$id"),
            calleeName = callee,
            candidateSymbolIds = emptyList(),
            receiver = receiver,
            enclosingSymbolId = SymbolId.of("symbol:owner"),
            parentCallId = parent?.id,
            range = range(offset, offset + 15),
            arguments = emptyList(),
            confidence = confidence,
        )

    private fun range(start: Int, end: Int): SourceRange =
        SourceRange.of(SourceLocation.of(file, 1, 1, start), SourceLocation.of(file, 1, 1, end))
}
