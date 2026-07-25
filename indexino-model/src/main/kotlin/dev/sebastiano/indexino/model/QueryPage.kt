package dev.sebastiano.indexino.model

import java.util.Collections

public class QueryPage<T>
@IndexinoInternalApi
public constructor(
    items: List<T>,
    public val offset: Int,
    public val limit: Int,
    public val hasMore: Boolean,
    public val nextCursor: String?,
    public val totalCount: Int?,
) {
    public val items: List<T> = Collections.unmodifiableList(ArrayList(items))

    init {
        require(offset >= 0) { "Query page offset must not be negative" }
        require(limit > 0) { "Query page limit must be positive" }
        require(totalCount == null || totalCount >= 0) {
            "Query page total count must not be negative"
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is QueryPage<*> &&
                items == other.items &&
                offset == other.offset &&
                limit == other.limit &&
                hasMore == other.hasMore &&
                nextCursor == other.nextCursor &&
                totalCount == other.totalCount

    override fun hashCode(): Int {
        var result = items.hashCode()
        result = 31 * result + offset
        result = 31 * result + limit
        result = 31 * result + hasMore.hashCode()
        result = 31 * result + (nextCursor?.hashCode() ?: 0)
        result = 31 * result + (totalCount ?: 0)
        return result
    }

    override fun toString(): String =
        "QueryPage(items=$items, offset=$offset, limit=$limit, hasMore=$hasMore, " +
            "nextCursor=$nextCursor, totalCount=$totalCount)"
}
