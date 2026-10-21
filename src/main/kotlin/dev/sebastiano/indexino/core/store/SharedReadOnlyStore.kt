package dev.sebastiano.indexino.core.store

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Xodus requires one environment owner per directory, including read-only environments. */
internal object SharedReadOnlyStore {
    private class Entry(var store: CodeIndexStore? = null, var pins: Int = 0)

    private val entries = ConcurrentHashMap<Path, Entry>()

    fun open(
        path: Path,
        openStore: (Path) -> CodeIndexStore = { XodusCodeIndexStore.open(it, readOnly = true) },
    ): CodeIndexStore {
        val canonical = path.toRealPath()
        while (true) {
            val entry = entries.computeIfAbsent(canonical) { Entry() }
            synchronized(entry) {
                // A waiter can hold a retired entry after its last owner closes. Retry on the
                // current entry rather than opening two Xodus environments for the same path.
                if (entries[canonical] !== entry) return@synchronized
                val store =
                    entry.store
                        ?: try {
                            openStore(canonical).also { entry.store = it }
                        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
                            entries.remove(canonical, entry)
                            throw failure
                        }
                entry.pins++
                return object : CodeIndexStore by store {
                    private val closed = AtomicBoolean()

                    override fun forEachSymbolInFile(
                        originId: String,
                        relativeFile: String,
                        action: (CodeIndexKey, SymbolRecord) -> Boolean,
                    ) = store.forEachSymbolInFile(originId, relativeFile, action)

                    override fun close() {
                        if (!closed.compareAndSet(false, true)) return
                        synchronized(entry) {
                            entry.pins--
                            if (entry.pins == 0) {
                                try {
                                    store.close()
                                } finally {
                                    entries.remove(canonical, entry)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
