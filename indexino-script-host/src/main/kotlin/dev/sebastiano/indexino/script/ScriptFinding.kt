package dev.sebastiano.indexino.script

import dev.sebastiano.indexino.model.ExperimentalIndexinoApi
import dev.sebastiano.indexino.model.SourceRange
import java.util.Collections

/** A finding reported by a trusted Indexino script. */
@ExperimentalIndexinoApi
public class ScriptFinding
private constructor(
    public val message: String,
    public val range: SourceRange?,
    properties: Map<String, String>,
) {
    public val properties: Map<String, String> =
        Collections.unmodifiableMap(LinkedHashMap(properties))

    public companion object {
        @JvmStatic public fun at(range: SourceRange): Builder = Builder(range)

        @JvmStatic
        public fun messageOnly(message: String): ScriptFinding =
            Builder(null).message(message).build()
    }

    public class Builder internal constructor(private val range: SourceRange?) {
        private var message: String? = null
        private val properties = linkedMapOf<String, String>()

        public fun message(message: String): Builder {
            require(message.isNotBlank()) { "Script finding message must not be blank" }
            this.message = message
            return this
        }

        public fun property(key: String, value: String): Builder {
            require(key.isNotBlank()) { "Script finding property key must not be blank" }
            properties[key] = value
            return this
        }

        public fun build(): ScriptFinding =
            ScriptFinding(
                requireNotNull(message) { "Script finding message is required" },
                range,
                properties,
            )
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ScriptFinding &&
                message == other.message &&
                range == other.range &&
                properties == other.properties

    override fun hashCode(): Int {
        var result = message.hashCode()
        result = 31 * result + (range?.hashCode() ?: 0)
        result = 31 * result + properties.hashCode()
        return result
    }

    override fun toString(): String =
        "ScriptFinding(message=$message, range=$range, properties=$properties)"
}
