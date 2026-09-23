package dev.sebastiano.indexino.core.xodus

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.record.CodeIndexRecordCodec
import dev.sebastiano.indexino.core.record.SymbolRecord
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
private constructor(
    private val environment: Environment,
    private val readOnly: Boolean,
    private val hasSymbolIndex: Boolean,
    private val hasCallIndex: Boolean,
) : CodeIndexStore {
    companion object {
        private const val STORE_NAME = "CodeIndex"
        private const val SYMBOL_INDEX_STORE_NAME = "SymbolNames"
        private const val CALL_INDEX_STORE_NAME = "CallFiles"
        private const val LOCK_TIMEOUT_MILLIS = 30_000L

        fun open(path: Path, readOnly: Boolean = false): XodusCodeIndexStore {
            path.parent?.createDirectories()
            val config =
                EnvironmentConfig()
                    .setEnvIsReadonly(readOnly)
                    .setLogLockTimeout(LOCK_TIMEOUT_MILLIS)
            val environment = Environments.newContextualInstance(path.toString(), config)
            return runCatching {
                    var hasSymbolIndex = false
                    var hasCallIndex = false
                    environment.executeInTransaction { txn ->
                        val primary =
                            environment.openStore(STORE_NAME, StoreConfig.WITHOUT_DUPLICATES, txn)
                        hasSymbolIndex = environment.storeExists(SYMBOL_INDEX_STORE_NAME, txn)
                        if (!readOnly && !hasSymbolIndex) {
                            val index =
                                environment.openStore(
                                    SYMBOL_INDEX_STORE_NAME,
                                    StoreConfig.WITH_DUPLICATES,
                                    txn,
                                )
                            val cursor = primary.openCursor(txn)
                            try {
                                var hasEntry = cursor.getNext()
                                while (hasEntry) {
                                    val key = StringBinding.entryToString(cursor.key)
                                    if (key.startsWith("sym:")) {
                                        val record =
                                            CodeIndexRecordCodec.decode(cursor.value.bytesUnsafe)
                                        if (record is SymbolRecord) {
                                            addSymbolEntries(index, txn, key, record)
                                        }
                                    }
                                    hasEntry = cursor.getNext()
                                }
                            } finally {
                                cursor.close()
                            }
                            hasSymbolIndex = true
                        }
                        hasCallIndex = initializeCallIndex(environment, primary, txn, readOnly)
                    }
                    XodusCodeIndexStore(environment, readOnly, hasSymbolIndex, hasCallIndex)
                }
                .getOrElse { failure -> environment.use { throw failure } }
        }

        private fun symbolTerms(record: SymbolRecord): Set<String> = buildSet {
            add(record.name)
            add(record.fqn)
            addAll(record.aliases)
        }

        private fun callFileTerm(originId: String, relativeFile: String): String =
            "${originId.length}:$originId$relativeFile"

        private fun initializeCallIndex(
            environment: Environment,
            primary: Store,
            txn: jetbrains.exodus.env.Transaction,
            readOnly: Boolean,
        ): Boolean {
            if (environment.storeExists(CALL_INDEX_STORE_NAME, txn)) return true
            if (readOnly) return false
            val index =
                environment.openStore(CALL_INDEX_STORE_NAME, StoreConfig.WITH_DUPLICATES, txn)
            val cursor = primary.openCursor(txn)
            try {
                var hasEntry =
                    cursor.getSearchKeyRange(StringBinding.stringToEntry("call:")) != null
                while (hasEntry) {
                    val key = StringBinding.entryToString(cursor.key)
                    if (!key.startsWith("call:")) break
                    val call =
                        CodeIndexRecordCodec.decode(cursor.value.bytesUnsafe) as? CallSiteRecord
                    if (call != null) {
                        index.put(
                            txn,
                            StringBinding.stringToEntry(
                                callFileTerm(call.originId, call.relativeFile)
                            ),
                            cursor.key,
                        )
                    }
                    hasEntry = cursor.getNext()
                }
            } finally {
                cursor.close()
            }
            return true
        }

        private fun addSymbolEntries(
            index: Store,
            txn: jetbrains.exodus.env.Transaction,
            rawKey: String,
            record: SymbolRecord,
        ) {
            val value = StringBinding.stringToEntry(rawKey)
            symbolTerms(record).forEach { term ->
                index.put(txn, StringBinding.stringToEntry(term), value)
            }
        }
    }

    private fun store(txn: jetbrains.exodus.env.Transaction): Store =
        environment.openStore(STORE_NAME, StoreConfig.WITHOUT_DUPLICATES, txn)

    private fun symbolIndex(txn: jetbrains.exodus.env.Transaction): Store =
        environment.openStore(SYMBOL_INDEX_STORE_NAME, StoreConfig.WITH_DUPLICATES, txn)

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
            val primary = store(txn)
            removeExistingSymbolEntries(primary, txn, key)
            removeExistingCallEntry(primary, txn, key)
            primary.put(
                txn,
                StringBinding.stringToEntry(key.value),
                ArrayByteIterable(CodeIndexRecordCodec.encode(record)),
            )
            if (record is SymbolRecord && key.value.startsWith("sym:")) {
                addSymbolEntries(symbolIndex(txn), txn, key.value, record)
            }
            if (record is CallSiteRecord && key.value.startsWith("call:")) {
                callIndex(txn)
                    .put(
                        txn,
                        StringBinding.stringToEntry(
                            callFileTerm(record.originId, record.relativeFile)
                        ),
                        StringBinding.stringToEntry(key.value),
                    )
            }
        }
    }

    override fun delete(key: CodeIndexKey) {
        check(!readOnly) { "Store is read-only" }
        environment.executeInTransaction { txn ->
            val primary = store(txn)
            removeExistingSymbolEntries(primary, txn, key)
            removeExistingCallEntry(primary, txn, key)
            primary.delete(txn, StringBinding.stringToEntry(key.value))
        }
    }

    private fun removeExistingSymbolEntries(
        primary: Store,
        txn: jetbrains.exodus.env.Transaction,
        key: CodeIndexKey,
    ) {
        if (!key.value.startsWith("sym:")) return
        val old = primary.get(txn, StringBinding.stringToEntry(key.value)) ?: return
        val record = CodeIndexRecordCodec.decode(old.bytesUnsafe) as? SymbolRecord ?: return
        val index = symbolIndex(txn)
        val value = StringBinding.stringToEntry(key.value)
        symbolTerms(record).forEach { term ->
            val cursor = index.openCursor(txn)
            try {
                if (cursor.getSearchBoth(StringBinding.stringToEntry(term), value)) {
                    cursor.deleteCurrent()
                }
            } finally {
                cursor.close()
            }
        }
    }

    private fun callIndex(txn: jetbrains.exodus.env.Transaction): Store =
        environment.openStore(CALL_INDEX_STORE_NAME, StoreConfig.WITH_DUPLICATES, txn)

    private fun removeExistingCallEntry(
        primary: Store,
        txn: jetbrains.exodus.env.Transaction,
        key: CodeIndexKey,
    ) {
        if (!key.value.startsWith("call:")) return
        val old = primary.get(txn, StringBinding.stringToEntry(key.value)) ?: return
        val call = CodeIndexRecordCodec.decode(old.bytesUnsafe) as? CallSiteRecord ?: return
        val cursor = callIndex(txn).openCursor(txn)
        try {
            if (
                cursor.getSearchBoth(
                    StringBinding.stringToEntry(callFileTerm(call.originId, call.relativeFile)),
                    StringBinding.stringToEntry(key.value),
                )
            )
                cursor.deleteCurrent()
        } finally {
            cursor.close()
        }
    }

    override fun forEachCallInFile(
        originId: String,
        relativeFile: String,
        action: (CodeIndexKey, CallSiteRecord) -> Boolean,
    ) {
        if (!hasCallIndex) {
            super.forEachCallInFile(originId, relativeFile, action)
            return
        }
        environment.computeInTransaction(
            TransactionalComputable { txn ->
                val cursor = callIndex(txn).openCursor(txn)
                val term = callFileTerm(originId, relativeFile)
                try {
                    var hasEntry = cursor.getSearchKey(StringBinding.stringToEntry(term)) != null
                    while (hasEntry && StringBinding.entryToString(cursor.key) == term) {
                        val rawKey = StringBinding.entryToString(cursor.value)
                        val value = store(txn).get(txn, StringBinding.stringToEntry(rawKey))
                        val record =
                            value?.let { CodeIndexRecordCodec.decode(it.bytesUnsafe) }
                                as? CallSiteRecord
                        if (record != null && !action(CodeIndexKey.parse(rawKey), record)) break
                        hasEntry = cursor.getNext()
                    }
                } finally {
                    cursor.close()
                }
            }
        )
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

    override fun forEachPrefix(prefix: String, action: (CodeIndexKey, CodeIndexRecord) -> Boolean) {
        environment.computeInTransaction(
            TransactionalComputable { txn ->
                val cursor = store(txn).openCursor(txn)
                try {
                    val searchKey = StringBinding.stringToEntry(prefix)
                    var hasEntry = cursor.getSearchKeyRange(searchKey) != null
                    while (hasEntry && StringBinding.entryToString(cursor.key).startsWith(prefix)) {
                        val rawKey = StringBinding.entryToString(cursor.key)
                        val continueScan =
                            action(
                                CodeIndexKey.parse(rawKey),
                                CodeIndexRecordCodec.decode(cursor.value.bytesUnsafe),
                            )
                        hasEntry = continueScan && cursor.getNext()
                    }
                } finally {
                    cursor.close()
                }
            }
        )
    }

    override fun forEachSymbolMatching(
        name: String,
        prefix: Boolean,
        action: (CodeIndexKey, SymbolRecord) -> Boolean,
    ) {
        if (!hasSymbolIndex) {
            super.forEachSymbolMatching(name, prefix, action)
            return
        }
        environment.computeInTransaction(
            TransactionalComputable { txn ->
                val indexCursor = symbolIndex(txn).openCursor(txn)
                try {
                    val search = StringBinding.stringToEntry(name)
                    var hasEntry =
                        if (prefix) indexCursor.getSearchKeyRange(search) != null
                        else indexCursor.getSearchKey(search) != null
                    var continueScan = true
                    while (hasEntry && continueScan) {
                        val term = StringBinding.entryToString(indexCursor.key)
                        continueScan =
                            (prefix && term.startsWith(name)) || (!prefix && term == name)
                        if (continueScan) {
                            val rawKey = StringBinding.entryToString(indexCursor.value)
                            val value = store(txn).get(txn, StringBinding.stringToEntry(rawKey))
                            val record = value?.let { CodeIndexRecordCodec.decode(it.bytesUnsafe) }
                            if (record is SymbolRecord) {
                                val canonical =
                                    symbolTerms(record)
                                        .asSequence()
                                        .filter { if (prefix) it.startsWith(name) else it == name }
                                        .minOrNull()
                                if (term == canonical) {
                                    continueScan = action(CodeIndexKey.parse(rawKey), record)
                                }
                            }
                        }
                        if (continueScan) hasEntry = indexCursor.getNext()
                    }
                } finally {
                    indexCursor.close()
                }
            }
        )
    }

    override fun <T> transaction(block: () -> T): T =
        environment.computeInTransaction(TransactionalComputable { block() })

    override fun close() {
        environment.close()
    }
}
