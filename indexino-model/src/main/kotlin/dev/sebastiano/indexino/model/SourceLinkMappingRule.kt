package dev.sebastiano.indexino.model

public class SourceLinkMappingRule
private constructor(
    public val kind: String,
    public val binaryPrefix: String,
    public val sourceRoot: String,
) {
    public companion object {
        @JvmStatic
        public fun packagePrefix(binaryPrefix: String, sourceRoot: String): SourceLinkMappingRule {
            require(binaryPrefix.isNotBlank()) { "Binary package prefix must not be blank" }
            require(sourceRoot.isNotBlank()) { "Source root must not be blank" }
            return SourceLinkMappingRule(kind = "package-prefix", binaryPrefix, sourceRoot)
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SourceLinkMappingRule &&
                kind == other.kind &&
                binaryPrefix == other.binaryPrefix &&
                sourceRoot == other.sourceRoot

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + binaryPrefix.hashCode()
        result = 31 * result + sourceRoot.hashCode()
        return result
    }

    override fun toString(): String =
        "SourceLinkMappingRule(kind=$kind, binaryPrefix=$binaryPrefix, sourceRoot=$sourceRoot)"
}
