package dev.sebastiano.indexino.core.cache

internal object WorktreeOverlayPolicy {
    const val MAX_CHAIN_DEPTH: Int = 8
    const val METADATA_BUDGET_BYTES: Long = 64L * 1024L
    const val REPRESENTATION_OVERLAY: String = "overlay"
    const val REPRESENTATION_MATERIALIZED: String = "materialized"
}
