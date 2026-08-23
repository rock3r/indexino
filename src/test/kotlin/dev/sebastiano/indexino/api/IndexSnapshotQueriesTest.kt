package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IndexSnapshotQueriesTest {
    @Test
    fun `declaration columns distinguish same line symbol ids`() {
        val queries = IndexSnapshotQueries(WorkspaceGenerationId.of("generation-a"))
        val first =
            SymbolRecord(
                fqn = "sample.Value",
                relativeFile = "Values.kt",
                line = 1,
                column = 1,
                kind = "class",
                name = "Value",
            )
        val second = first.copy(column = 20)

        assertNotEquals(
            with(queries) { first.definitionId() },
            with(queries) { second.definitionId() },
        )
    }

    @Test
    fun `materializes record locations with their source origin`() {
        val queries = IndexSnapshotQueries(WorkspaceGenerationId.of("generation-a"))
        val symbol =
            SymbolRecord(
                fqn = "android.Panel",
                relativeFile = "src/main/kotlin/Panel.kt",
                line = 1,
                column = 9,
                kind = "class",
                name = "Panel",
                originId = "git:android",
            )
        val reference =
            ReferenceRecord(
                symbolFqn = "android.Panel",
                relativeFile = "src/main/kotlin/UsePanel.kt",
                line = 4,
                column = 5,
                originId = "git:android",
            )

        val publicSymbol = with(queries) { symbol.toPublicSymbol(null) }
        val publicReference = with(queries) { reference.toPublicReference(listOf(symbol)) }

        assertEquals(SourceOriginId.of("git:android"), publicSymbol.location.file.originId)
        assertEquals(9, publicSymbol.location.column)
        assertEquals(SourceOriginId.of("git:android"), publicReference.location.file.originId)
    }

    @Test
    fun `external symbol ids are generation-local`() {
        val reference =
            ReferenceRecord(
                symbolFqn = "missing.Shared",
                relativeFile = "ui/src/main/kotlin/Missing.kt",
                line = 3,
                column = 5,
                arity = 1,
            )
        val first =
            with(IndexSnapshotQueries(WorkspaceGenerationId.of("generation-a"))) {
                reference.toPublicReference(emptyList())
            }
        val second =
            with(IndexSnapshotQueries(WorkspaceGenerationId.of("generation-b"))) {
                reference.toPublicReference(emptyList())
            }

        assertNotEquals(first.symbolId, second.symbolId)
        assertNotEquals(first.candidateSymbolIds, second.candidateSymbolIds)
    }

    @Test
    fun `ambiguous same-arity overloads use an ambiguous id not storage order`() {
        val generation = WorkspaceGenerationId.of("generation-a")
        val queries = IndexSnapshotQueries(generation)
        val intOverload =
            SymbolRecord(
                fqn = "demo.foo",
                relativeFile = "ui/src/main/kotlin/FooInt.kt",
                line = 1,
                kind = "function",
                name = "foo",
                signature = "(Int)",
                arity = 1,
            )
        val stringOverload =
            SymbolRecord(
                fqn = "demo.foo",
                relativeFile = "ui/src/main/kotlin/FooString.kt",
                line = 1,
                kind = "function",
                name = "foo",
                signature = "(String)",
                arity = 1,
            )
        val reference =
            ReferenceRecord(
                symbolFqn = "demo.foo",
                relativeFile = "ui/src/main/kotlin/UseFoo.kt",
                line = 4,
                column = 5,
                arity = 1,
            )
        val candidates = listOf(intOverload, stringOverload)
        val public = with(queries) { reference.toPublicReference(candidates) }

        assertTrue(public.symbolId.value.startsWith("indexino:ambiguous:v1:"))
        assertEquals(2, public.candidateSymbolIds.size)
        assertTrue(with(queries) { reference.matchesSymbolId(public.symbolId, candidates) })
    }

    @Test
    fun `external direct ids round-trip even when other candidate names resolve locally`() {
        val generation = WorkspaceGenerationId.of("generation-a")
        val queries = IndexSnapshotQueries(generation)
        val localCandidate =
            SymbolRecord(
                fqn = "local.Candidate",
                relativeFile = "ui/src/main/kotlin/Local.kt",
                line = 1,
                kind = "function",
                name = "Candidate",
                arity = 1,
            )
        val reference =
            ReferenceRecord(
                symbolFqn = "missing.Shared",
                relativeFile = "ui/src/main/kotlin/Missing.kt",
                line = 3,
                column = 5,
                arity = 1,
                candidateSymbolFqns = listOf("missing.Shared", "local.Candidate"),
            )
        val candidates = listOf(localCandidate)
        val public = with(queries) { reference.toPublicReference(candidates) }

        assertTrue(public.symbolId.value.startsWith("indexino:external:v1:"))
        assertTrue(public.candidateSymbolIds.any { it.value.startsWith("indexino:external:v1:") })
        assertTrue(with(queries) { reference.matchesSymbolId(public.symbolId, candidates) })
    }
}
