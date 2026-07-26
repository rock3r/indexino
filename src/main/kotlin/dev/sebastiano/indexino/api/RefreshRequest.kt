package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.model.PluginId
import java.util.Collections

public class RefreshRequest
private constructor(public val scope: IndexScope, plugins: Set<PluginId>) {
    public val plugins: Set<PluginId> = Collections.unmodifiableSet(LinkedHashSet(plugins))

    public companion object {
        @JvmStatic
        public fun forScope(scope: IndexScope): RefreshRequest = RefreshRequest(scope, emptySet())
    }

    public fun withPlugin(plugin: PluginId): RefreshRequest =
        RefreshRequest(scope, plugins + plugin)

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is RefreshRequest && scope == other.scope && plugins == other.plugins

    override fun hashCode(): Int = 31 * scope.hashCode() + plugins.hashCode()

    override fun toString(): String = "RefreshRequest(scope=$scope, plugins=$plugins)"
}
