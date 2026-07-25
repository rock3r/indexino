package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IndexSnapshotQueriesTest {
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
                reference.toPublicReference(emptyMap())
            }
        val second =
            with(IndexSnapshotQueries(WorkspaceGenerationId.of("generation-b"))) {
                reference.toPublicReference(emptyMap())
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
        val symbolsByName =
            with(queries) { indexSymbolsByName(listOf(intOverload, stringOverload)) }
        val public = with(queries) { reference.toPublicReference(symbolsByName) }

        assertTrue(public.symbolId.value.startsWith("indexino:ambiguous:v1:"))
        assertEquals(2, public.candidateSymbolIds.size)
        assertTrue(with(queries) { reference.matchesSymbolId(public.symbolId, symbolsByName) })
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
        val symbolsByName = with(queries) { indexSymbolsByName(listOf(localCandidate)) }
        val public = with(queries) { reference.toPublicReference(symbolsByName) }

        assertTrue(public.symbolId.value.startsWith("indexino:external:v1:"))
        assertTrue(with(queries) { reference.matchesSymbolId(public.symbolId, symbolsByName) })
    }
}
