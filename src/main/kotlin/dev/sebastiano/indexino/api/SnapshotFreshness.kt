package dev.sebastiano.indexino.api

public class SnapshotFreshness private constructor(public val value: String) {
    public companion object {
        @JvmField public val CURRENT: SnapshotFreshness = SnapshotFreshness("CURRENT")
        @JvmField public val DIRTY: SnapshotFreshness = SnapshotFreshness("DIRTY")
        @JvmField public val UNKNOWN: SnapshotFreshness = SnapshotFreshness("UNKNOWN")
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is SnapshotFreshness && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "SnapshotFreshness(value=$value)"
}
