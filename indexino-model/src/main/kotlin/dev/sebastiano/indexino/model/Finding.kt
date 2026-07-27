package dev.sebastiano.indexino.model

public class Finding
@IndexinoInternalApi
public constructor(
    public val plugin: PluginId,
    public val checkId: String,
    public val message: String,
    public val range: SourceRange?,
    properties: Map<String, String>,
) {
    public val properties: Map<String, String> = properties.toMap()

    init {
        require(checkId.isNotBlank()) { "Check ID must not be blank" }
        require(message.isNotBlank()) { "Finding message must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        other is Finding &&
            plugin == other.plugin &&
            checkId == other.checkId &&
            message == other.message &&
            range == other.range &&
            properties == other.properties

    override fun hashCode(): Int {
        var result = plugin.hashCode()
        result = 31 * result + checkId.hashCode()
        result = 31 * result + message.hashCode()
        result = 31 * result + (range?.hashCode() ?: 0)
        result = 31 * result + properties.hashCode()
        return result
    }

    override fun toString(): String =
        "Finding(plugin=$plugin, checkId=$checkId, message=$message, range=$range, properties=$properties)"
}
