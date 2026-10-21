package dev.sebastiano.indexino.producer

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.core.store.WorktreeOverlayIndexStore
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import org.junit.jupiter.api.io.TempDir

class SourceRecordCleanupTest {
    @Test
    fun `overlay cleanup visits only the writable delta, never the base families`(
        @TempDir root: Path
    ) {
        val base = XodusCodeIndexStore.open(root.resolve("base"))
        val delta = XodusCodeIndexStore.open(root.resolve("delta"))
        val edited = IndexedSource.workspace(root, "src/Panel.java")
        val baseKey = CodeIndexKey.parse("sym:old")
        val deltaKey = CodeIndexKey.parse("sym:previous-edit")
        base.put(baseKey, SymbolRecord("old", edited.path, 1, kind = "class", name = "old"))
        delta.put(
            deltaKey,
            SymbolRecord("previous-edit", edited.path, 1, kind = "class", name = "previous-edit"),
        )
        val guarded =
            object : CodeIndexStore by base {
                override fun forEachPrefix(
                    prefix: String,
                    action: (CodeIndexKey, CodeIndexRecord) -> Boolean,
                ) {
                    fail("Cleanup scanned base family $prefix")
                }

                override fun prefixScan(
                    prefix: String
                ): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> =
                    fail("Cleanup materialized base family $prefix")
            }
        WorktreeOverlayIndexStore(
                guarded,
                delta,
                listOf(
                    WorktreeOverlayIndexStore.tombstonePrefixForSource(edited.originId, edited.path)
                ),
            )
            .use { overlay ->
                SourceRecordCleanup.deleteLanguageOriginRecords(
                    overlay,
                    "java",
                    ".java",
                    setOf(edited),
                )
                assertEquals(emptyList(), delta.prefixScan("sym:").toList())
            }
    }

    @Test
    fun `empty language invalidation never scans records`(@TempDir root: Path) {
        XodusCodeIndexStore.open(root.resolve("store")).use { actual ->
            val guarded =
                object : CodeIndexStore by actual {
                    override fun prefixScan(
                        prefix: String
                    ): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> =
                        fail("Empty invalidation must not scan $prefix")
                }
            SourceRecordCleanup.deleteLanguageOriginRecords(guarded, "java", ".java", emptySet())
            SourceRecordCleanup.deleteLanguageOriginRecords(guarded, "kotlin", ".kt", emptySet())
        }
    }

    @Test
    fun `empty XML invalidation never scans records`(@TempDir root: Path) {
        XodusCodeIndexStore.open(root.resolve("store")).use { actual ->
            val guarded =
                object : CodeIndexStore by actual {
                    override fun prefixScan(
                        prefix: String
                    ): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> =
                        fail("Empty invalidation must not scan $prefix")

                    override fun forEachPrefix(
                        prefix: String,
                        action: (CodeIndexKey, CodeIndexRecord) -> Boolean,
                    ) {
                        fail("Empty invalidation must not scan $prefix")
                    }
                }
            SourceRecordCleanup.deleteXmlOriginRecords(guarded, emptySet())
        }
    }

    @Test
    fun `language cleanup streams records and preserves other origins and files`(
        @TempDir root: Path
    ) {
        XodusCodeIndexStore.open(root.resolve("store")).use { actual ->
            val records =
                listOf(
                    SymbolRecord(
                        fqn = "remove",
                        relativeFile = "src/Panel.java",
                        line = 1,
                        originId = "workspace",
                        kind = "class",
                        name = "remove",
                    ),
                    SymbolRecord(
                        fqn = "otherOrigin",
                        relativeFile = "src/Panel.java",
                        line = 1,
                        originId = "nested",
                        kind = "class",
                        name = "otherOrigin",
                    ),
                    SymbolRecord(
                        fqn = "otherFile",
                        relativeFile = "src/Other.java",
                        line = 1,
                        originId = "workspace",
                        kind = "class",
                        name = "otherFile",
                    ),
                )
            records.forEach { actual.put(CodeIndexKey.parse("sym:${it.fqn}"), it) }
            val guarded =
                object : CodeIndexStore by actual {
                    override fun prefixScan(
                        prefix: String
                    ): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> =
                        fail("Cleanup must not materialize records for $prefix")
                }
            SourceRecordCleanup.deleteLanguageOriginRecords(
                guarded,
                "java",
                ".java",
                setOf(IndexedSource.workspace(root, "src/Panel.java")),
            )
            assertEquals(
                records.drop(1).toSet(),
                actual.prefixScan("sym:").map { it.second }.toSet(),
            )
        }
    }

    @Test
    fun `XML cleanup streams records and preserves other origins and files`(@TempDir root: Path) {
        XodusCodeIndexStore.open(root.resolve("store")).use { actual ->
            val records =
                listOf(
                    SymbolRecord(
                        fqn = "remove",
                        relativeFile = "res/values/a.xml",
                        line = 1,
                        originId = "workspace",
                        kind = "resource",
                        name = "remove",
                    ),
                    SymbolRecord(
                        fqn = "otherOrigin",
                        relativeFile = "res/values/a.xml",
                        line = 1,
                        originId = "nested",
                        kind = "resource",
                        name = "otherOrigin",
                    ),
                    SymbolRecord(
                        fqn = "otherFile",
                        relativeFile = "res/values/b.xml",
                        line = 1,
                        originId = "workspace",
                        kind = "resource",
                        name = "otherFile",
                    ),
                )
            records.forEach { actual.put(CodeIndexKey.parse("sym:${it.fqn}"), it) }
            val guarded =
                object : CodeIndexStore by actual {
                    override fun prefixScan(
                        prefix: String
                    ): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> =
                        fail("Cleanup must not materialize records for $prefix")
                }
            SourceRecordCleanup.deleteXmlOriginRecords(
                guarded,
                setOf(IndexedSource.workspace(root, "res/values/a.xml")),
            )
            assertEquals(
                records.drop(1).toSet(),
                actual.prefixScan("sym:").map { it.second }.toSet(),
            )
        }
    }
}
