package dev.sebastiano.indexino.core.xodus

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.CodeIndexRecordCodec
import dev.sebastiano.indexino.core.record.MetaIndexerVersionRecord
import dev.sebastiano.indexino.core.record.PluginFactRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.store.SharedReadOnlyStore
import jetbrains.exodus.ArrayByteIterable
import jetbrains.exodus.bindings.StringBinding
import jetbrains.exodus.env.EnvironmentConfig
import jetbrains.exodus.env.Environments
import jetbrains.exodus.env.StoreConfig
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException

class XodusCodeIndexStoreTest {
    private lateinit var store: XodusCodeIndexStore
    private lateinit var tempDir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("xodus-test-")
        store = XodusCodeIndexStore.open(tempDir.resolve("base.xodus"))
    }

    @AfterTest
    fun tearDown() {
        store.close()
    }

    @Test
    fun `put get round-trips typed records`() {
        val key = CodeIndexKey.metaIndexerVersion()
        val record = MetaIndexerVersionRecord(version = "0.1.0")
        store.put(key, record)
        assertEquals(record, store.get(key))
    }

    @Test
    fun `delete removes key`() {
        val key = CodeIndexKey.sym("com.example.Foo")
        store.put(key, MetaIndexerVersionRecord("unused"))
        store.delete(key)
        assertNull(store.get(key))
    }

    @Test
    fun `transaction shares reads and nested writes and rolls back the whole batch`() {
        val original = CodeIndexKey.sym("original")
        val added = CodeIndexKey.sym("added")
        store.put(original, MetaIndexerVersionRecord("before"))
        assertFailsWith<IllegalStateException> {
            store.transaction {
                store.delete(original)
                store.transaction { store.put(added, MetaIndexerVersionRecord("during")) }
                assertNull(store.get(original))
                assertEquals(MetaIndexerVersionRecord("during"), store.get(added))
                assertEquals(listOf(added), store.prefixScan("sym:").map { it.first }.toList())
                val visited = mutableListOf<CodeIndexKey>()
                store.forEachPrefix("sym:") { key, _ ->
                    visited += key
                    true
                }
                assertEquals(listOf(added), visited)
                error("abort batch")
            }
        }
        assertEquals(MetaIndexerVersionRecord("before"), store.get(original))
        assertNull(store.get(added))
        store.transaction { store.put(added, MetaIndexerVersionRecord("after")) }
        assertEquals(MetaIndexerVersionRecord("after"), store.get(added))
    }

    @Test
    fun `prefix scan returns keys in lexicographic order`() {
        val pluginId = "dev.example.plugin"
        val prefix = CodeIndexKey.pluginFactFilePrefix(pluginId, "ui/Panel.kt")
        val key1 = CodeIndexKey.pluginFact(pluginId, "ui/Panel.kt", "site:10:1")
        val key2 = CodeIndexKey.pluginFact(pluginId, "ui/Panel.kt", "site:20:1")
        val keyOther = CodeIndexKey.pluginFact(pluginId, "ui/Other.kt", "site:10:1")

        store.put(key2, pluginFact("Later"))
        store.put(key1, pluginFact("Earlier"))
        store.put(keyOther, pluginFact("Other"))

        val scanned = store.prefixScan(prefix).toList()
        assertEquals(listOf(key1, key2), scanned.map { it.first })
        assertEquals("Earlier", (scanned[0].second as PluginFactRecord).encodedValue)
        assertEquals("Later", (scanned[1].second as PluginFactRecord).encodedValue)
    }

    @Test
    fun `forEachPrefix stops without materializing remaining cursor rows`() {
        val pluginId = "dev.example.plugin"
        val prefix = CodeIndexKey.pluginFactFilePrefix(pluginId, "ui/Panel.kt")
        val first = CodeIndexKey.pluginFact(pluginId, "ui/Panel.kt", "site:10:1")
        store.put(first, pluginFact("First"))
        store.put(
            CodeIndexKey.pluginFact(pluginId, "ui/Panel.kt", "site:20:1"),
            pluginFact("Second"),
        )

        val visited = mutableListOf<CodeIndexKey>()
        store.forEachPrefix(prefix) { key, _ ->
            visited += key
            false
        }

        assertEquals(listOf(first), visited)
    }

    @Test
    fun `shared snapshot forwards indexed lookups instead of inheriting scanning defaults`() {
        val wantedCall = CodeIndexKey.call("wanted")
        val wantedSymbol = CodeIndexKey.sym("pkg.Wanted")
        store.put(wantedCall, call("one", "File.kt"))
        store.put(wantedSymbol, symbol("Wanted", "pkg.Wanted", emptyList()))
        store.close()
        val path = tempDir.resolve("base.xodus")
        Environments.newContextualInstance(path.toString()).use { environment ->
            environment.executeInTransaction { txn ->
                val primary =
                    environment.openStore("CodeIndex", StoreConfig.WITHOUT_DUPLICATES, txn)
                for (key in listOf("sym:unrelated", "call:unrelated")) {
                    primary.put(
                        txn,
                        StringBinding.stringToEntry(key),
                        ArrayByteIterable(byteArrayOf(1, 2)),
                    )
                }
            }
        }
        SharedReadOnlyStore.open(path).use { snapshot ->
            val calls = mutableListOf<CodeIndexKey>()
            val symbols = mutableListOf<CodeIndexKey>()
            val callResult = runCatching {
                snapshot.forEachCallInFile("one", "File.kt") { key, _ ->
                    calls += key
                    true
                }
            }
            val symbolResult = runCatching {
                snapshot.forEachSymbolMatching("Wanted", false) { key, _ ->
                    symbols += key
                    true
                }
            }
            assertTrue(
                callResult.isSuccess && symbolResult.isSuccess,
                "Shared snapshot scanned: calls=${callResult.exceptionOrNull()}, " +
                    "symbols=${symbolResult.exceptionOrNull()}",
            )
            assertEquals(listOf(wantedCall), calls)
            assertEquals(listOf(wantedSymbol), symbols)
        }
    }

    @Test
    fun `call file index skips unrelated corrupt values and survives replacement rollback and deletion`() {
        val key = CodeIndexKey.call("arbitrary legacy identity")
        val record = call("one", "File.kt")
        store.put(key, record)
        store.put(CodeIndexKey.call("second"), record.copy(identity = "second", originId = "two"))
        store.close()
        val path = tempDir.resolve("base.xodus")
        Environments.newContextualInstance(path.toString()).use { env ->
            env.executeInTransaction { txn ->
                env.openStore("CodeIndex", StoreConfig.WITHOUT_DUPLICATES, txn)
                    .put(
                        txn,
                        StringBinding.stringToEntry("call:unrelated"),
                        ArrayByteIterable(byteArrayOf(1, 2)),
                    )
            }
        }
        store = XodusCodeIndexStore.open(path)
        val result = runCatching { callsInFile("one", "File.kt") }
        assertTrue(
            result.isSuccess,
            "File query scanned unrelated primary rows: ${result.exceptionOrNull()}",
        )
        assertEquals(listOf(key), result.getOrThrow())
        assertEquals(emptyList(), callsInFile("one", "Other.kt"))
        assertFailsWith<IllegalStateException> {
            store.transaction {
                store.put(key, record.copy(relativeFile = "Other.kt"))
                assertEquals(emptyList(), callsInFile("one", "File.kt"))
                assertEquals(listOf(key), callsInFile("one", "Other.kt"))
                error("abort")
            }
        }
        assertEquals(listOf(key), callsInFile("one", "File.kt"))
        store.put(key, record.copy(originId = "two"))
        assertEquals(emptyList(), callsInFile("one", "File.kt"))
        assertEquals(2, callsInFile("two", "File.kt").size)
        store.delete(key)
        assertEquals(listOf(CodeIndexKey.call("second")), callsInFile("two", "File.kt"))
    }

    @Test
    fun `legacy call lookup is read only and writable reopen creates derived index`() {
        val path = tempDir.resolve("legacy-calls")
        val key = CodeIndexKey.call("legacy")
        Environments.newContextualInstance(path.toString()).use { env ->
            env.executeInTransaction { txn ->
                env.openStore("CodeIndex", StoreConfig.WITHOUT_DUPLICATES, txn)
                    .put(
                        txn,
                        StringBinding.stringToEntry(key.value),
                        ArrayByteIterable(CodeIndexRecordCodec.encode(call("one", "File.kt"))),
                    )
            }
        }
        store.close()
        store = XodusCodeIndexStore.open(path, readOnly = true)
        assertEquals(listOf(key), callsInFile("one", "File.kt"))
        store.close()
        Environments.newContextualInstance(path.toString()).use { env ->
            env.executeInTransaction { txn ->
                assertEquals(false, env.storeExists("CallFiles", txn))
            }
        }
        store = XodusCodeIndexStore.open(path)
        assertEquals(listOf(key), callsInFile("one", "File.kt"))
        store.close()
        Environments.newContextualInstance(path.toString()).use { env ->
            env.executeInTransaction { txn -> assertTrue(env.storeExists("CallFiles", txn)) }
        }
        store = XodusCodeIndexStore.open(path, readOnly = true)
        assertEquals(listOf(key), callsInFile("one", "File.kt"))
    }

    private fun call(origin: String, file: String) =
        CallSiteRecord(
            identity = "arbitrary legacy identity",
            calleeName = "target",
            candidateSymbolFqns = emptyList(),
            relativeFile = file,
            startLine = 1,
            startColumn = 1,
            startOffset = 0,
            endLine = 1,
            endColumn = 8,
            endOffset = 7,
            confidence = "UNRESOLVED",
            originId = origin,
        )

    private fun callsInFile(origin: String, file: String): List<CodeIndexKey> {
        val keys = mutableListOf<CodeIndexKey>()
        store.forEachCallInFile(origin, file) { key, _ ->
            keys += key
            true
        }
        return keys
    }

    @Test
    fun `failed symbol backfill releases environment ownership`() {
        val path = tempDir.resolve("corrupt-legacy")
        Environments.newContextualInstance(path.toString()).use { environment ->
            environment.executeInTransaction { txn ->
                environment
                    .openStore("CodeIndex", StoreConfig.WITHOUT_DUPLICATES, txn)
                    .put(
                        txn,
                        StringBinding.stringToEntry("sym:Broken"),
                        ArrayByteIterable(byteArrayOf(1, 2)),
                    )
            }
        }
        assertFailsWith<SerializationException> { XodusCodeIndexStore.open(path) }
        val reopened = runCatching {
            Environments.newContextualInstance(
                    path.toString(),
                    EnvironmentConfig().setLogLockTimeout(50),
                )
                .use {}
        }
        assertTrue(
            reopened.isSuccess,
            "Failed initialization retained the store lock: ${reopened.exceptionOrNull()}",
        )
    }

    @Test
    fun `legacy read only lookup leaves store unchanged and writable reopen backfills names`() {
        val path = tempDir.resolve("legacy")
        val key = CodeIndexKey.sym("pkg.Café")
        val record = symbol("Café", "pkg.Café", listOf("Coffee", "Cafeteria"))
        Environments.newContextualInstance(path.toString()).use { environment ->
            environment.executeInTransaction { txn ->
                environment
                    .openStore("CodeIndex", StoreConfig.WITHOUT_DUPLICATES, txn)
                    .put(
                        txn,
                        StringBinding.stringToEntry(key.value),
                        ArrayByteIterable(CodeIndexRecordCodec.encode(record)),
                    )
            }
        }
        store.close()
        store = XodusCodeIndexStore.open(path, readOnly = true)
        assertEquals(listOf(key), matching("Coffee"))
        assertEquals(listOf(key), matching("Caf", prefix = true))
        assertEquals(emptyList(), matching("Caf"))
        store.close()
        Environments.newContextualInstance(path.toString()).use { environment ->
            environment.executeInTransaction { txn ->
                assertEquals(false, environment.storeExists("SymbolNames", txn))
            }
        }
        store = XodusCodeIndexStore.open(path)
        assertEquals(record, store.get(key))
        assertEquals(listOf(key), matching("Coffee"))
        assertEquals(listOf(key), matching("Caf", prefix = true))
        store.close()
        Environments.newContextualInstance(path.toString()).use { environment ->
            environment.executeInTransaction { txn ->
                assertTrue(environment.storeExists("SymbolNames", txn))
            }
        }
        store = XodusCodeIndexStore.open(path, readOnly = true)
        assertEquals(listOf(key), matching("pkg.Café"))
        assertEquals(listOf(key), matching("Caf", prefix = true))
    }

    @Test
    fun `symbol lookup uses persistent index and skips unrelated corrupt primary values`() {
        val wanted = CodeIndexKey.symbolDefinition("pkg.Café", "one", "Café.kt", 1, 0)
        store.put(wanted, symbol("Café", "pkg.Café", aliases = listOf("Coffee", "Café")))
        store.close()

        val path = tempDir.resolve("base.xodus")
        Environments.newContextualInstance(path.toString()).use { environment ->
            environment.executeInTransaction { txn ->
                environment
                    .openStore("CodeIndex", StoreConfig.WITHOUT_DUPLICATES, txn)
                    .put(
                        txn,
                        StringBinding.stringToEntry("sym:zzz.Corrupt:file.kt:1:0"),
                        ArrayByteIterable(byteArrayOf(1, 2, 3)),
                    )
            }
        }
        store = XodusCodeIndexStore.open(path)

        val found = mutableListOf<CodeIndexKey>()
        store.forEachSymbolMatching("Caf", prefix = true) { key, _ ->
            found += key
            true
        }
        assertEquals(listOf(wanted), found)
    }

    @Test
    fun `symbol index tracks replacement deletion rollback reopen duplicates and early stop`() {
        val first = CodeIndexKey.symbolDefinition("a.First", "one", "First.kt", 1, 0)
        val second = CodeIndexKey.symbolDefinition("b.Second", "two", "Second.kt", 1, 0)
        store.put(first, symbol("First", "a.First", listOf("Shared", "Shared")))
        store.put(second, symbol("Second", "b.Second", listOf("Shared")))
        assertEquals(listOf(first, second), matching("Shared"))
        store.put(first, symbol("Renamed", "a.Renamed", listOf("Other")))
        assertEquals(emptyList(), matching("First"))
        assertEquals(listOf(first), matching("Renamed"))
        assertFailsWith<IllegalStateException> {
            store.transaction {
                store.delete(first)
                error("rollback")
            }
        }
        store.close()
        store = XodusCodeIndexStore.open(tempDir.resolve("base.xodus"))
        assertEquals(listOf(first), matching("Renamed"))
        assertEquals(listOf(second), matching("Shared"))

        val visited = mutableListOf<CodeIndexKey>()
        store.forEachSymbolMatching("", prefix = true) { key, _ ->
            visited += key
            false
        }
        assertEquals(1, visited.size)
        store.delete(second)
        assertEquals(emptyList(), matching("Shared"))
    }

    private fun matching(name: String, prefix: Boolean = false): List<CodeIndexKey> = buildList {
        store.forEachSymbolMatching(name, prefix) { key, _ ->
            add(key)
            true
        }
    }

    private fun symbol(name: String, fqn: String, aliases: List<String>): SymbolRecord =
        SymbolRecord(
            fqn = fqn,
            relativeFile = "$name.kt",
            line = 1,
            kind = "class",
            name = name,
            aliases = aliases,
        )

    private fun pluginFact(value: String): PluginFactRecord =
        PluginFactRecord(
            pluginId = "dev.example.plugin",
            relativeFile = "ui/Panel.kt",
            factKey = "site",
            encodedValue = value,
        )
}
