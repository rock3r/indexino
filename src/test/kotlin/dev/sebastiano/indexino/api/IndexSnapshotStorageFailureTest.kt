package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.ReferenceQuery
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.SourceOriginRevision
import dev.sebastiano.indexino.model.SymbolId
import dev.sebastiano.indexino.model.SymbolQuery
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import dev.sebastiano.indexino.model.WorkspaceRevision
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IndexSnapshotStorageFailureTest {
    @OptIn(IndexinoInternalApi::class)
    @Test
    fun `findSymbols maps unexpected storage failures to IndexinoException INTERNAL`() {
        val snapshot = snapshotWithThrowingStore()
        try {
            val failure =
                assertFailsWith<IndexinoException> {
                    runSuspend {
                        snapshot.findSymbols(
                            SymbolQuery.named("Demo"),
                            QueryOptions.page(limit = 10),
                        )
                    }
                }
            assertEquals("INTERNAL", failure.failure.category.value)
            assertEquals("internal", failure.failure.code)
            assertTrue(failure.cause is SimulatedStorageFailure)
        } finally {
            snapshot.close()
        }
    }

    @OptIn(IndexinoInternalApi::class)
    @Test
    fun `findReferences maps unexpected storage failures to IndexinoException INTERNAL`() {
        val snapshot = snapshotWithThrowingStore()
        try {
            val failure =
                assertFailsWith<IndexinoException> {
                    runSuspend {
                        snapshot.findReferences(
                            ReferenceQuery.to(SymbolId.of("kotlin:demo.Demo#demo()")),
                            QueryOptions.page(limit = 10),
                        )
                    }
                }
            assertEquals("INTERNAL", failure.failure.category.value)
            assertEquals("internal", failure.failure.code)
            assertTrue(failure.cause is SimulatedStorageFailure)
        } finally {
            snapshot.close()
        }
    }

    @OptIn(IndexinoInternalApi::class)
    @Test
    fun `close maps unexpected store close failures to IndexinoException INTERNAL`() {
        val snapshot =
            IndexSnapshot.create(
                store = ThrowingOnCloseCodeIndexStore(),
                revision =
                    WorkspaceRevision(
                        fingerprint = "fingerprint",
                        origins =
                            listOf(
                                SourceOriginRevision(
                                    originId = SourceOriginId.of("workspace"),
                                    revision = "deadbeef",
                                    stateFingerprint = "state",
                                    expectedRevision = null,
                                )
                            ),
                    ),
                generation = WorkspaceGenerationId.of("generation"),
            )
        val failure = assertFailsWith<IndexinoException> { snapshot.close() }
        assertEquals("INTERNAL", failure.failure.category.value)
        assertEquals("internal", failure.failure.code)
        assertTrue(failure.cause is SimulatedStorageFailure)
    }

    @OptIn(IndexinoInternalApi::class)
    private fun snapshotWithThrowingStore(): IndexSnapshot =
        IndexSnapshot.create(
            store = ThrowingCodeIndexStore(),
            revision =
                WorkspaceRevision(
                    fingerprint = "fingerprint",
                    origins =
                        listOf(
                            SourceOriginRevision(
                                originId = SourceOriginId.of("workspace"),
                                revision = "deadbeef",
                                stateFingerprint = "state",
                                expectedRevision = null,
                            )
                        ),
                ),
            generation = WorkspaceGenerationId.of("generation"),
        )

    private class ThrowingCodeIndexStore : CodeIndexStore {
        override fun get(key: CodeIndexKey): CodeIndexRecord? = unsupported()

        override fun put(key: CodeIndexKey, record: CodeIndexRecord) = unsupported()

        override fun delete(key: CodeIndexKey) = unsupported()

        override fun prefixScan(prefix: String): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> =
            throw SimulatedStorageFailure("simulated storage failure for $prefix")

        override fun <T> transaction(block: () -> T): T = unsupported()

        override fun close() = Unit

        private fun unsupported(): Nothing =
            throw UnsupportedOperationException("ThrowingCodeIndexStore stub")
    }

    private class ThrowingOnCloseCodeIndexStore : CodeIndexStore {
        override fun get(key: CodeIndexKey): CodeIndexRecord? = unsupported()

        override fun put(key: CodeIndexKey, record: CodeIndexRecord) = unsupported()

        override fun delete(key: CodeIndexKey) = unsupported()

        override fun prefixScan(prefix: String): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> =
            emptySequence()

        override fun <T> transaction(block: () -> T): T = unsupported()

        override fun close() {
            throw SimulatedStorageFailure("simulated store close failure")
        }

        private fun unsupported(): Nothing =
            throw UnsupportedOperationException("ThrowingOnCloseCodeIndexStore stub")
    }

    private class SimulatedStorageFailure(message: String) : Exception(message)

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            }
        )
        return checkNotNull(outcome).getOrThrow()
    }
}
