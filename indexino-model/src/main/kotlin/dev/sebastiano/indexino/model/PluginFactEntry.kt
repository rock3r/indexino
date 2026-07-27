package dev.sebastiano.indexino.model

public class PluginFactEntry
@IndexinoInternalApi
public constructor(
    public val key: String,
    public val range: SourceRange?,
    public val value: PluginFactValue,
) {
    init {
        require(key.isNotBlank()) { "Plugin fact key must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        other is PluginFactEntry && key == other.key && range == other.range && value == other.value

    override fun hashCode(): Int =
        31 * (31 * key.hashCode() + (range?.hashCode() ?: 0)) + value.hashCode()

    override fun toString(): String = "PluginFactEntry(key=$key, range=$range, value=$value)"
}
