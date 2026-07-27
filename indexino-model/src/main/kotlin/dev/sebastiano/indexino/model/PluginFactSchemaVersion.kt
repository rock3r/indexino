package dev.sebastiano.indexino.model

public class PluginFactSchemaVersion private constructor(public val value: Int) {
    public companion object {
        @JvmStatic
        public fun of(value: Int): PluginFactSchemaVersion {
            require(value >= 1) { "Plugin fact schema version must be positive" }
            return PluginFactSchemaVersion(value)
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is PluginFactSchemaVersion && value == other.value

    override fun hashCode(): Int = value

    override fun toString(): String = "PluginFactSchemaVersion(value=$value)"
}
