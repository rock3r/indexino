package dev.sebastiano.indexino.model

public class CheckRequest
private constructor(public val pluginId: PluginId, public val checkId: String) {
    public companion object {
        @JvmStatic
        public fun of(pluginId: PluginId, checkId: String): CheckRequest {
            require(checkId.isNotBlank()) { "Check ID must not be blank" }
            return CheckRequest(pluginId, checkId)
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is CheckRequest && pluginId == other.pluginId && checkId == other.checkId

    override fun hashCode(): Int = 31 * pluginId.hashCode() + checkId.hashCode()

    override fun toString(): String = "CheckRequest(pluginId=$pluginId, checkId=$checkId)"
}
