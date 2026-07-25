package dev.sebastiano.indexino.model

public class PluginId private constructor(public val value: String) {
    public companion object {
        @JvmStatic
        public fun of(value: String): PluginId {
            require(value.isNotBlank()) { "Plugin ID must not be blank" }
            return PluginId(value)
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is PluginId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "PluginId(value=$value)"
}
