package dev.sebastiano.indexino.api

public class RefreshOutcome private constructor(public val value: String) {
    public companion object {
        @JvmField public val UPDATED: RefreshOutcome = RefreshOutcome("UPDATED")
        @JvmField public val UNCHANGED: RefreshOutcome = RefreshOutcome("UNCHANGED")
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is RefreshOutcome && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "RefreshOutcome(value=$value)"
}
