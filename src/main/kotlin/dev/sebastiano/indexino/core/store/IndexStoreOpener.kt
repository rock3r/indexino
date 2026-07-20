package dev.sebastiano.indexino.core.store

import dev.sebastiano.indexino.core.path.IndexPathResolver
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import java.nio.file.Path

internal object IndexStoreOpener {
    fun openForQuery(project: Path, commit: String, sessionId: String? = null): CodeIndexStore {
        val resolver = IndexPathResolver(project)
        val basePath = resolver.resolveBaseStore(commit)
        if (sessionId.isNullOrBlank()) {
            return XodusCodeIndexStore.open(basePath, readOnly = true)
        }
        return OverlayCodeIndexStore.open(basePath, resolver.resolveSessionDeltaStore(sessionId))
    }
}
