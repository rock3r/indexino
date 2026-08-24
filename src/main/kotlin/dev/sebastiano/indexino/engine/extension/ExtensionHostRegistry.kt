package dev.sebastiano.indexino.engine.extension

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Workspace-scoped extension hosts sharing worker concurrency limits. */
internal object ExtensionHostRegistry {
    private val hosts = ConcurrentHashMap<String, ExtensionHost>()

    fun hostFor(cacheRoot: Path, workspaceId: String, parent: ClassLoader): ExtensionHost =
        hosts.computeIfAbsent(cacheRoot.toString()) {
            ExtensionHost.create(cacheRoot = cacheRoot, workspaceId = workspaceId, parent = parent)
        }

    fun clearForTests() {
        hosts.values.forEach(ExtensionHost::close)
        hosts.clear()
    }
}
