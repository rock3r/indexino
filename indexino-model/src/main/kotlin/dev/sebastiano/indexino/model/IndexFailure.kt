package dev.sebastiano.indexino.model

public class IndexFailure
private constructor(
    public val category: IndexFailureCategory,
    public val code: String,
    public val message: String,
    public val retryable: Boolean,
) {
    public companion object {
        @IndexinoInternalApi
        @JvmStatic
        public fun of(
            category: IndexFailureCategory,
            code: String,
            message: String,
            retryable: Boolean,
        ): IndexFailure = IndexFailure(category, code, message, retryable)
    }

    init {
        require(code.isNotBlank()) { "Index failure code must not be blank" }
        require(message.isNotBlank()) { "Index failure message must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is IndexFailure &&
                category == other.category &&
                code == other.code &&
                message == other.message &&
                retryable == other.retryable

    override fun hashCode(): Int {
        var result = category.hashCode()
        result = 31 * result + code.hashCode()
        result = 31 * result + message.hashCode()
        result = 31 * result + retryable.hashCode()
        return result
    }

    override fun toString(): String =
        "IndexFailure(category=$category, code=$code, message=$message, retryable=$retryable)"
}
