package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CallArgumentRecord
import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.model.CallQuery
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.NameMatchMode
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.ReferenceQuery
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.SourceOriginRevision
import dev.sebastiano.indexino.model.SymbolId
import dev.sebastiano.indexino.model.SymbolQuery
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import dev.sebastiano.indexino.model.WorkspaceRevision
import java.util.Locale
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
    fun `queries do not materialize rows beyond the requested page`() {
        val snapshot =
            snapshotWithRecords(
                CodeIndexKey.symbolDefinition("demo.First", "First.kt", 1, 1) to
                    SymbolRecord(
                        fqn = "demo.First",
                        relativeFile = "First.kt",
                        line = 1,
                        kind = "class",
                        name = "First",
                    ),
                CodeIndexKey.symbolDefinition("demo.Later", "Later.kt", 0, 1) to
                    SymbolRecord(
                        fqn = "demo.Later",
                        relativeFile = "Later.kt",
                        line = 0,
                        kind = "class",
                        name = "Later",
                    ),
                CodeIndexKey.ref("demo.First", "First.kt", 1) to
                    ReferenceRecord(
                        symbolFqn = "demo.First",
                        relativeFile = "First.kt",
                        line = 1,
                        column = 1,
                    ),
                CodeIndexKey.ref("demo.First", "Later.kt", 0) to
                    ReferenceRecord(
                        symbolFqn = "demo.First",
                        relativeFile = "Later.kt",
                        line = 0,
                        column = 1,
                    ),
            )
        try {
            val symbols =
                runCatching {
                        runSuspend {
                            snapshot.findSymbols(
                                SymbolQuery.named("demo.").withMatch(NameMatchMode.PREFIX),
                                QueryOptions.page(limit = 1),
                            )
                        }
                    }
                    .getOrNull()
            assertTrue(symbols != null, "The first symbol page must not materialize later rows")
            assertEquals(listOf("First"), symbols.items.map { it.name })
            assertTrue(symbols.hasMore)
            assertEquals(null, symbols.totalCount)

            val references =
                runCatching {
                        runSuspend {
                            snapshot.findReferences(
                                ReferenceQuery.to(symbols.items.single().id),
                                QueryOptions.page(limit = 1),
                            )
                        }
                    }
                    .getOrNull()
            assertTrue(
                references != null,
                "The first reference page must not materialize later rows",
            )
            assertEquals(listOf("First.kt"), references.items.map { it.location.file.path })
            assertTrue(references.hasMore)
            assertEquals(null, references.totalCount)
        } finally {
            snapshot.close()
        }
    }

    @OptIn(IndexinoInternalApi::class)
    @Test
    fun `findSymbols uses the bounded store scan rather than prefixScan materialization`() {
        val record =
            CodeIndexKey.symbolDefinition("demo.Streamed", "Streamed.kt", 1, 1) to
                SymbolRecord(
                    fqn = "demo.Streamed",
                    relativeFile = "Streamed.kt",
                    line = 1,
                    kind = "class",
                    name = "Streamed",
                )
        val reference =
            CodeIndexKey.ref("demo.Streamed", "Use.kt", 1) to
                ReferenceRecord(
                    symbolFqn = "demo.Streamed",
                    relativeFile = "Use.kt",
                    line = 1,
                    column = 1,
                )
        val snapshot = snapshotWithStreamingRecords(record, reference)
        try {
            val page = runSuspend {
                snapshot.findSymbols(SymbolQuery.named("Streamed"), QueryOptions.page(limit = 1))
            }
            assertEquals(listOf("Streamed"), page.items.map { it.name })
            val references = runSuspend {
                snapshot.findReferences(
                    ReferenceQuery.to(page.items.single().id),
                    QueryOptions.page(limit = 1),
                )
            }
            assertEquals(listOf("Use.kt"), references.items.map { it.location.file.path })
        } finally {
            snapshot.close()
        }
    }

    @OptIn(IndexinoInternalApi::class)
    @Test
    fun `findSymbols batches owner resolution into one additional store scan`() {
        val store =
            CountingRecordsCodeIndexStore(
                listOf(
                    CodeIndexKey.symbolDefinition("demo.Owner", "Owner.kt", 1, 1) to
                        SymbolRecord(
                            fqn = "demo.Owner",
                            relativeFile = "Owner.kt",
                            line = 1,
                            kind = "class",
                            name = "Owner",
                        ),
                    CodeIndexKey.symbolDefinition("demo.Owner.first", "Owner.kt", 2, 1) to
                        SymbolRecord(
                            fqn = "demo.Owner.first",
                            relativeFile = "Owner.kt",
                            line = 2,
                            kind = "function",
                            name = "first",
                            ownerFqn = "demo.Owner",
                        ),
                    CodeIndexKey.symbolDefinition("demo.Owner.second", "Owner.kt", 3, 1) to
                        SymbolRecord(
                            fqn = "demo.Owner.second",
                            relativeFile = "Owner.kt",
                            line = 3,
                            kind = "function",
                            name = "second",
                            ownerFqn = "demo.Owner",
                        ),
                )
            )
        val snapshot =
            IndexSnapshot.create(
                store = store,
                revision = workspaceRevision(),
                generation = WorkspaceGenerationId.of("generation"),
            )
        try {
            val symbols = runSuspend {
                snapshot.findSymbols(
                    SymbolQuery.named("demo.").withMatch(NameMatchMode.PREFIX),
                    QueryOptions.page(limit = 3),
                )
            }
            assertEquals(3, symbols.items.size)
            assertEquals(2, store.forEachPrefixCalls)
        } finally {
            snapshot.close()
        }
    }

    @OptIn(IndexinoInternalApi::class)
    @Test
    fun `external direct references remain queryable when another candidate resolves locally`() {
        val generation = WorkspaceGenerationId.of("generation")
        val snapshot =
            snapshotWithRecords(
                CodeIndexKey.symbolDefinition("local.Candidate", "Local.kt", 1, 1) to
                    SymbolRecord(
                        fqn = "local.Candidate",
                        relativeFile = "Local.kt",
                        line = 1,
                        kind = "function",
                        name = "Candidate",
                        arity = 1,
                    ),
                CodeIndexKey.ref("missing.Shared", "Use.kt", 1) to
                    ReferenceRecord(
                        symbolFqn = "missing.Shared",
                        candidateSymbolFqns = listOf("local.Candidate"),
                        relativeFile = "Use.kt",
                        line = 1,
                        column = 1,
                        arity = 1,
                    ),
            )
        try {
            val externalId =
                with(IndexSnapshotQueries(generation)) { externalSymbolId("missing.Shared") }
            val references = runSuspend {
                snapshot.findReferences(ReferenceQuery.to(externalId), QueryOptions.page(limit = 1))
            }
            assertEquals(listOf(externalId), references.items.map { it.symbolId })
        } finally {
            snapshot.close()
        }
    }

    @OptIn(IndexinoInternalApi::class)
    @Test
    fun `ambiguous reference ids round-trip through findReferences`() {
        val generation = WorkspaceGenerationId.of("generation")
        val snapshot =
            snapshotWithRecords(
                CodeIndexKey.symbolDefinition("demo.shared", "One.kt", 1, 1) to
                    SymbolRecord(
                        fqn = "demo.shared",
                        relativeFile = "One.kt",
                        line = 1,
                        kind = "function",
                        name = "shared",
                        arity = 1,
                    ),
                CodeIndexKey.symbolDefinition("demo.shared", "Two.kt", 1, 1) to
                    SymbolRecord(
                        fqn = "demo.shared",
                        relativeFile = "Two.kt",
                        line = 1,
                        kind = "function",
                        name = "shared",
                        arity = 1,
                    ),
                CodeIndexKey.ref("demo.shared", "Use.kt", 1) to
                    ReferenceRecord(
                        symbolFqn = "demo.shared",
                        relativeFile = "Use.kt",
                        line = 1,
                        column = 1,
                        arity = 1,
                    ),
            )
        try {
            val ambiguousId =
                with(IndexSnapshotQueries(generation)) { ambiguousSymbolId("demo.shared") }
            val references = runSuspend {
                snapshot.findReferences(
                    ReferenceQuery.to(ambiguousId),
                    QueryOptions.page(limit = 1),
                )
            }
            assertEquals(listOf(ambiguousId), references.items.map { it.symbolId })
        } finally {
            snapshot.close()
        }
    }

    @OptIn(IndexinoInternalApi::class)
    @Test
    fun `queries reject offsets beyond the host maximum before allocating a page window`() {
        val snapshot = snapshotWithThrowingStore()
        try {
            val failure =
                assertFailsWith<IndexinoException> {
                    runSuspend {
                        snapshot.findSymbols(
                            SymbolQuery.named("demo"),
                            QueryOptions.page(limit = 1, offset = 10_001),
                        )
                    }
                }
            assertEquals("INVALID_REQUEST", failure.failure.category.value)
            assertEquals("offset_exceeds_maximum", failure.failure.code)
        } finally {
            snapshot.close()
        }
    }

    @OptIn(IndexinoInternalApi::class)
    @Test
    fun `queries fail rather than expose an unusable cursor beyond the host window`() {
        val records =
            (0..10_000).map { index ->
                val name = "demo.%05d".format(Locale.ROOT, index)
                CodeIndexKey.symbolDefinition(name, "$index.kt", 1, 1) to
                    SymbolRecord(
                        fqn = name,
                        relativeFile = "$index.kt",
                        line = 1,
                        kind = "class",
                        name = name,
                    )
            }
        val snapshot = snapshotWithRecords(*records.toTypedArray())
        try {
            val failure =
                assertFailsWith<IndexinoException> {
                    runSuspend {
                        snapshot.findSymbols(
                            SymbolQuery.named("demo.").withMatch(NameMatchMode.PREFIX),
                            QueryOptions.page(limit = 1, offset = 9_999),
                        )
                    }
                }
            assertEquals("INVALID_REQUEST", failure.failure.category.value)
            assertEquals("result_window_exceeds_maximum", failure.failure.code)
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
    @Test
    fun `findCalls preserves nested call containment and resolves call IDs`() {
        val outer = "App.kt:0"
        val inner = "App.kt:20"
        val snapshot =
            snapshotWithRecords(
                CodeIndexKey.symbolDefinition("sample.Container", "App.kt", 1, 1) to
                    SymbolRecord(
                        fqn = "sample.Container",
                        relativeFile = "App.kt",
                        line = 1,
                        kind = "function",
                        name = "Container",
                        language = "kotlin",
                    ),
                CodeIndexKey.call(outer) to
                    CallSiteRecord(
                        identity = outer,
                        calleeName = "Container",
                        candidateSymbolFqns = listOf("sample.Container"),
                        relativeFile = "App.kt",
                        startLine = 1,
                        startColumn = 1,
                        startOffset = 0,
                        endLine = 3,
                        endColumn = 2,
                        endOffset = 40,
                        arguments =
                            listOf(
                                CallArgumentRecord(
                                    position = 0,
                                    resolvedName = "content",
                                    kind = "TRAILING_LAMBDA",
                                    startLine = 1,
                                    startColumn = 12,
                                    startOffset = 11,
                                    endLine = 3,
                                    endColumn = 1,
                                    endOffset = 39,
                                    nestedCallIdentities = listOf(inner),
                                )
                            ),
                        confidence = "RESOLVED",
                    ),
                CodeIndexKey.call(inner) to
                    CallSiteRecord(
                        identity = inner,
                        calleeName = "Child",
                        candidateSymbolFqns = listOf("sample.Child"),
                        parentCallIdentity = outer,
                        relativeFile = "App.kt",
                        startLine = 2,
                        startColumn = 3,
                        startOffset = 20,
                        endLine = 2,
                        endColumn = 10,
                        endOffset = 27,
                        confidence = "HEURISTIC",
                    ),
            )
        try {
            val containers = runSuspend {
                snapshot.findCalls(CallQuery.to("Container"), QueryOptions.page(10))
            }
            val container = containers.items.single()
            val localContainer =
                runSuspend {
                        snapshot.findSymbols(SymbolQuery.named("Container"), QueryOptions.page(1))
                    }
                    .items
                    .single()
            assertEquals(listOf(localContainer.id), container.candidateSymbolIds)
            assertEquals(
                listOf("Child"),
                container.arguments.single().nestedCallIds.map { id ->
                    runSuspend { snapshot.findCalls(CallQuery.byId(id), QueryOptions.page(1)) }
                        .items
                        .single()
                        .calleeName
                },
            )
            assertEquals("content", container.arguments.single().resolvedName)
        } finally {
            snapshot.close()
        }
    }

    @OptIn(IndexinoInternalApi::class)
    private fun snapshotWithRecords(
        vararg records: Pair<CodeIndexKey, CodeIndexRecord>
    ): IndexSnapshot =
        IndexSnapshot.create(
            store = RecordsCodeIndexStore(records.asList()),
            revision = workspaceRevision(),
            generation = WorkspaceGenerationId.of("generation"),
        )

    @OptIn(IndexinoInternalApi::class)
    private fun snapshotWithStreamingRecords(
        vararg records: Pair<CodeIndexKey, CodeIndexRecord>
    ): IndexSnapshot =
        IndexSnapshot.create(
            store = StreamingRecordsCodeIndexStore(records.asList()),
            revision = workspaceRevision(),
            generation = WorkspaceGenerationId.of("generation"),
        )

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

    @OptIn(IndexinoInternalApi::class)
    private fun workspaceRevision(): WorkspaceRevision =
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
        )

    private class RecordsCodeIndexStore(
        private val records: List<Pair<CodeIndexKey, CodeIndexRecord>>
    ) : CodeIndexStore {
        override fun get(key: CodeIndexKey): CodeIndexRecord? =
            records.firstOrNull { it.first == key }?.second

        override fun put(key: CodeIndexKey, record: CodeIndexRecord) = unsupported()

        override fun delete(key: CodeIndexKey) = unsupported()

        override fun prefixScan(prefix: String): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> =
            records.asSequence().filter { it.first.value.startsWith(prefix) }

        override fun <T> transaction(block: () -> T): T = block()

        override fun close() = Unit

        private fun unsupported(): Nothing =
            throw UnsupportedOperationException("RecordsCodeIndexStore is read-only")
    }

    private class CountingRecordsCodeIndexStore(
        private val records: List<Pair<CodeIndexKey, CodeIndexRecord>>
    ) : CodeIndexStore {
        var forEachPrefixCalls: Int = 0
            private set

        override fun get(key: CodeIndexKey): CodeIndexRecord? =
            records.firstOrNull { it.first == key }?.second

        override fun put(key: CodeIndexKey, record: CodeIndexRecord) = unsupported()

        override fun delete(key: CodeIndexKey) = unsupported()

        override fun prefixScan(prefix: String): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> =
            records.asSequence().filter { it.first.value.startsWith(prefix) }

        override fun forEachPrefix(
            prefix: String,
            action: (CodeIndexKey, CodeIndexRecord) -> Boolean,
        ) {
            forEachPrefixCalls += 1
            for ((key, record) in records) {
                if (key.value.startsWith(prefix) && !action(key, record)) return
            }
        }

        override fun <T> transaction(block: () -> T): T = block()

        override fun close() = Unit

        private fun unsupported(): Nothing =
            throw UnsupportedOperationException("CountingRecordsCodeIndexStore is read-only")
    }

    private class StreamingRecordsCodeIndexStore(
        private val records: List<Pair<CodeIndexKey, CodeIndexRecord>>
    ) : CodeIndexStore {
        override fun get(key: CodeIndexKey): CodeIndexRecord? =
            records.firstOrNull { it.first == key }?.second

        override fun put(key: CodeIndexKey, record: CodeIndexRecord) = unsupported()

        override fun delete(key: CodeIndexKey) = unsupported()

        override fun prefixScan(prefix: String): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> =
            throw SimulatedStorageFailure("prefixScan must not be used")

        override fun forEachPrefix(
            prefix: String,
            action: (CodeIndexKey, CodeIndexRecord) -> Boolean,
        ) {
            for ((key, record) in records) {
                if (key.value.startsWith(prefix) && !action(key, record)) {
                    return
                }
            }
        }

        override fun <T> transaction(block: () -> T): T = block()

        override fun close() = Unit

        private fun unsupported(): Nothing =
            throw UnsupportedOperationException("StreamingRecordsCodeIndexStore is read-only")
    }

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
