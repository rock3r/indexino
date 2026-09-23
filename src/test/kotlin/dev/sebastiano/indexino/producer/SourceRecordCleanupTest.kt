package dev.sebastiano.indexino.producer

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import org.junit.jupiter.api.io.TempDir

class SourceRecordCleanupTest {
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
