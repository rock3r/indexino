package dev.sebastiano.indexino.model

public class SourceFile
private constructor(
    public val originId: SourceOriginId,
    public val path: String,
    @ExcludeFromEquality public val displayPath: String,
) {
    public companion object {
        @JvmStatic
        public fun of(originId: SourceOriginId, path: String, displayPath: String): SourceFile {
            require(path.isNormalizedRelativePath()) {
                "Source path must be a normalized origin-relative path"
            }
            require(displayPath.isNotBlank()) { "Source display path must not be blank" }
            return SourceFile(originId, path, displayPath)
        }

        private fun String.isNormalizedRelativePath(): Boolean {
            if (
                isBlank() ||
                    startsWith('/') ||
                    startsWith('\\') ||
                    contains('\\') ||
                    DRIVE_PREFIX.matchesAt(this, 0)
            ) {
                return false
            }
            return split('/').all { segment ->
                segment.isNotEmpty() && segment != "." && segment != ".."
            }
        }

        private val DRIVE_PREFIX: Regex = Regex("[A-Za-z]:")
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is SourceFile && originId == other.originId && path == other.path

    override fun hashCode(): Int {
        var result = originId.hashCode()
        result = 31 * result + path.hashCode()
        return result
    }

    override fun toString(): String =
        "SourceFile(originId=$originId, path=$path, displayPath=$displayPath)"
}
