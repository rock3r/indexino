package dev.sebastiano.indexino.model

public class IndexFailureCategory private constructor(public val value: String) {
    public companion object {
        @JvmField
        public val INVALID_REQUEST: IndexFailureCategory = IndexFailureCategory("INVALID_REQUEST")
        @JvmField
        public val INDEX_NOT_FOUND: IndexFailureCategory = IndexFailureCategory("INDEX_NOT_FOUND")
        @JvmField public val TOPOLOGY: IndexFailureCategory = IndexFailureCategory("TOPOLOGY")
        @JvmField
        public val STORAGE_BUSY: IndexFailureCategory = IndexFailureCategory("STORAGE_BUSY")
        @JvmField public val IO: IndexFailureCategory = IndexFailureCategory("IO")
        @JvmField public val PARSE: IndexFailureCategory = IndexFailureCategory("PARSE")
        @JvmField public val PLUGIN: IndexFailureCategory = IndexFailureCategory("PLUGIN")
        @JvmField public val SCRIPT: IndexFailureCategory = IndexFailureCategory("SCRIPT")
        @JvmField
        public val WORKSPACE_LOST: IndexFailureCategory = IndexFailureCategory("WORKSPACE_LOST")
        @JvmField public val CLOSED: IndexFailureCategory = IndexFailureCategory("CLOSED")
        @JvmField public val INTERNAL: IndexFailureCategory = IndexFailureCategory("INTERNAL")
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is IndexFailureCategory && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "IndexFailureCategory(value=$value)"
}
