package dev.sebastiano.indexino.api

/**
 * Freshness of a snapshot relative to the bound workspace at acquisition time.
 *
 * S1 reports [UNKNOWN] because it has no watcher and does not re-hash sources when acquiring a
 * snapshot, so currency cannot be proven. [PROVISIONAL] means the published generation used a
 * scoped refresh, so other indexed sources may be stale until a full audit. [CURRENT] requires
 * certified evidence; [DIRTY] asserts known-stale and must not be invented without evidence.
 */
public class SnapshotFreshness private constructor(public val value: String) {
    public companion object {
        @JvmField public val CURRENT: SnapshotFreshness = SnapshotFreshness("CURRENT")
        @JvmField public val DIRTY: SnapshotFreshness = SnapshotFreshness("DIRTY")
        @JvmField public val PROVISIONAL: SnapshotFreshness = SnapshotFreshness("PROVISIONAL")
        @JvmField public val UNKNOWN: SnapshotFreshness = SnapshotFreshness("UNKNOWN")
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is SnapshotFreshness && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "SnapshotFreshness(value=$value)"
}
