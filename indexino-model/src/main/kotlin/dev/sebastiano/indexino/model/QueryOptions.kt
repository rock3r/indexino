package dev.sebastiano.indexino.model

public class QueryOptions
private constructor(
    public val limit: Int,
    public val offset: Int,
    public val afterCursor: String?,
) {
    public companion object {
        @JvmStatic public fun page(limit: Int): QueryOptions = page(limit, 0)

        @JvmStatic
        public fun page(limit: Int, offset: Int): QueryOptions {
            validateLimit(limit)
            require(offset >= 0) { "Query offset must not be negative" }
            return QueryOptions(limit = limit, offset = offset, afterCursor = null)
        }

        @JvmStatic
        public fun after(limit: Int, cursor: String): QueryOptions {
            validateLimit(limit)
            require(cursor.isNotBlank()) { "Query cursor must not be blank" }
            return QueryOptions(limit = limit, offset = 0, afterCursor = cursor)
        }

        private fun validateLimit(limit: Int) {
            require(limit > 0) { "Query limit must be positive" }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is QueryOptions &&
                limit == other.limit &&
                offset == other.offset &&
                afterCursor == other.afterCursor

    override fun hashCode(): Int {
        var result = limit
        result = 31 * result + offset
        result = 31 * result + (afterCursor?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "QueryOptions(limit=$limit, offset=$offset, afterCursor=$afterCursor)"
}
