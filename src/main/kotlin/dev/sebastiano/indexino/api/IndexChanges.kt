package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.model.IndexinoInternalApi

public class IndexChanges
@IndexinoInternalApi
public constructor(
    public val changedFileCount: Int,
    public val unchangedFileCount: Int,
    public val removedFileCount: Int,
) {
    init {
        require(changedFileCount >= 0) { "Changed file count must not be negative" }
        require(unchangedFileCount >= 0) { "Unchanged file count must not be negative" }
        require(removedFileCount >= 0) { "Removed file count must not be negative" }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is IndexChanges &&
                changedFileCount == other.changedFileCount &&
                unchangedFileCount == other.unchangedFileCount &&
                removedFileCount == other.removedFileCount

    override fun hashCode(): Int {
        var result = changedFileCount
        result = 31 * result + unchangedFileCount
        result = 31 * result + removedFileCount
        return result
    }

    override fun toString(): String =
        "IndexChanges(changedFileCount=$changedFileCount, " +
            "unchangedFileCount=$unchangedFileCount, removedFileCount=$removedFileCount)"
}
