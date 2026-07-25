package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import kotlin.test.Test
import kotlin.test.assertNotEquals

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
}
