package com.kotlincodeindex.core.store

import com.kotlincodeindex.core.path.IndexPathResolver
import com.kotlincodeindex.core.xodus.XodusCodeIndexStore
import java.nio.file.Path

object IndexStoreOpener {
    fun openForQuery(project: Path, commit: String, sessionId: String? = null): CodeIndexStore {
        val resolver = IndexPathResolver(project)
        val basePath = resolver.resolveBaseStore(commit)
        if (sessionId.isNullOrBlank()) {
            return XodusCodeIndexStore.open(basePath, readOnly = true)
        }
        return OverlayCodeIndexStore.open(basePath, resolver.resolveSessionDeltaStore(sessionId))
    }
}
