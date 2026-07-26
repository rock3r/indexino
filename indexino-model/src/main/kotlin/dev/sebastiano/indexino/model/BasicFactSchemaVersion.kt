package dev.sebastiano.indexino.model

public class BasicFactSchemaVersion private constructor(public val value: Int) {
    public companion object {
        @JvmStatic
        public fun of(value: Int): BasicFactSchemaVersion {
            require(value > 0) { "Basic fact schema version must be positive" }
            return BasicFactSchemaVersion(value)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is BasicFactSchemaVersion && value == other.value

    override fun hashCode(): Int = value

    override fun toString(): String = "BasicFactSchemaVersion(value=$value)"
}
