package dev.sebastiano.indexino.core.cache

internal object WorktreeOverlayPolicy {
    const val MAX_CHAIN_DEPTH: Int = 8
    const val METADATA_BUDGET_BYTES: Long = 64L * 1024L
    const val REPRESENTATION_OVERLAY: String = "overlay"
    const val REPRESENTATION_MATERIALIZED: String = "materialized"

    /**
     * Published-pack storage format. Version 1 packs are ZIP archives of expanded Xodus
     * environments; version 2 packs are raw immutable segment files mapped via MemorySegment.
     * Readers reject generations below the current version so legacy caches rebuild safely.
     */
    const val STORAGE_FORMAT_XODUS_PACK: Int = 1
    const val STORAGE_FORMAT_SEGMENT_PACK: Int = 2
    const val CURRENT_STORAGE_FORMAT: Int = STORAGE_FORMAT_SEGMENT_PACK
}
