package dev.sebastiano.indexino.model

/** An opaque call-site identity that is stable only within one generation. */
public class CallSiteId private constructor(public val value: String) {
    public companion object {
        @JvmStatic
        public fun of(value: String): CallSiteId {
            require(value.isNotBlank()) { "Call site ID must not be blank" }
            return CallSiteId(value)
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is CallSiteId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "CallSiteId(value=$value)"
}
