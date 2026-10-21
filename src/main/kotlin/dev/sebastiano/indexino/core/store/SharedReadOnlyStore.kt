package dev.sebastiano.indexino.core.store

import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Xodus requires one environment owner per directory, including read-only environments. */
internal object SharedReadOnlyStore {
    private class Entry(val store: CodeIndexStore, var pins: Int = 0)

    private val entries = mutableMapOf<Path, Entry>()

    fun open(path: Path): CodeIndexStore =
        synchronized(entries) {
            val canonical = path.toRealPath()
            val entry =
                entries.getOrPut(canonical) {
                    Entry(XodusCodeIndexStore.open(canonical, readOnly = true))
                }
            entry.pins++
            object : CodeIndexStore by entry.store {
                private val closed = AtomicBoolean()

                override fun close() {
                    if (!closed.compareAndSet(false, true)) return
                    synchronized(entries) {
                        entry.pins--
                        if (entry.pins == 0) {
                            try {
                                entry.store.close()
                            } finally {
                                entries.remove(canonical)
                            }
                        }
                    }
                }
            }
        }
}
