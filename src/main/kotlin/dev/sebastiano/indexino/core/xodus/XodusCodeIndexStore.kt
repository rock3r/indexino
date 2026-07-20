package dev.sebastiano.indexino.core.xodus

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.record.CodeIndexRecordCodec
import dev.sebastiano.indexino.core.store.CodeIndexStore
import java.nio.file.Path
import jetbrains.exodus.ArrayByteIterable
import jetbrains.exodus.bindings.StringBinding
import jetbrains.exodus.env.Environment
import jetbrains.exodus.env.EnvironmentConfig
import jetbrains.exodus.env.Environments
import jetbrains.exodus.env.Store
import jetbrains.exodus.env.StoreConfig
import jetbrains.exodus.env.TransactionalComputable
import kotlin.io.path.createDirectories

internal class XodusCodeIndexStore
private constructor(private val environment: Environment, private val readOnly: Boolean) :
    CodeIndexStore {
    companion object {
        private const val STORE_NAME = "CodeIndex"
        private const val LOCK_TIMEOUT_MILLIS = 30_000L

        fun open(path: Path, readOnly: Boolean = false): XodusCodeIndexStore {
            path.parent?.createDirectories()
            val config =
                EnvironmentConfig()
                    .setEnvIsReadonly(readOnly)
                    .setLogLockTimeout(LOCK_TIMEOUT_MILLIS)
            val environment = Environments.newInstance(path.toString(), config)
            environment.executeInTransaction { txn ->
                environment.openStore(STORE_NAME, StoreConfig.WITHOUT_DUPLICATES, txn)
            }
            return XodusCodeIndexStore(environment, readOnly)
        }
    }

    private fun store(txn: jetbrains.exodus.env.Transaction): Store =
        environment.openStore(STORE_NAME, StoreConfig.WITHOUT_DUPLICATES, txn)

    override fun get(key: CodeIndexKey): CodeIndexRecord? =
        environment.computeInTransaction(
            TransactionalComputable { txn ->
                val entry = store(txn).get(txn, StringBinding.stringToEntry(key.value))
                entry?.let { CodeIndexRecordCodec.decode(it.bytesUnsafe) }
            }
        )

    override fun put(key: CodeIndexKey, record: CodeIndexRecord) {
        check(!readOnly) { "Store is read-only" }
        environment.executeInTransaction { txn ->
            store(txn)
                .put(
                    txn,
                    StringBinding.stringToEntry(key.value),
                    ArrayByteIterable(CodeIndexRecordCodec.encode(record)),
                )
        }
    }

    override fun delete(key: CodeIndexKey) {
        check(!readOnly) { "Store is read-only" }
        environment.executeInTransaction { txn ->
            store(txn).delete(txn, StringBinding.stringToEntry(key.value))
        }
    }

    override fun prefixScan(prefix: String): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> =
        environment
            .computeInTransaction(
                TransactionalComputable { txn ->
                    val store = store(txn)
                    val cursor = store.openCursor(txn)
                    val results = mutableListOf<Pair<CodeIndexKey, CodeIndexRecord>>()
                    val searchKey = StringBinding.stringToEntry(prefix)
                    if (cursor.getSearchKeyRange(searchKey) != null) {
                        var hasEntry = true
                        while (hasEntry) {
                            val rawKey = StringBinding.entryToString(cursor.key)
                            if (!rawKey.startsWith(prefix)) {
                                break
                            }
                            val value = cursor.value
                            results +=
                                CodeIndexKey.parse(rawKey) to
                                    CodeIndexRecordCodec.decode(value.bytesUnsafe)
                            hasEntry = cursor.getNext()
                        }
                    }
                    cursor.close()
                    results
                }
            )
            .asSequence()

    override fun <T> transaction(block: () -> T): T =
        environment.computeInTransaction(TransactionalComputable { block() })

    override fun close() {
        environment.close()
    }
}
