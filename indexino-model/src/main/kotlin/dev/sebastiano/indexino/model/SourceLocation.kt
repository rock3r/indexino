package dev.sebastiano.indexino.model

public class SourceLocation
private constructor(
    public val file: SourceFile,
    public val line: Int,
    public val column: Int?,
    public val offset: Int?,
) {
    public companion object {
        @JvmStatic
        public fun of(file: SourceFile, line: Int): SourceLocation =
            of(file = file, line = line, column = null, offset = null)

        @JvmStatic
        public fun of(file: SourceFile, line: Int, column: Int?, offset: Int?): SourceLocation {
            require(line >= 1) { "Source line must be at least 1" }
            require(column == null || column >= 1) { "Source column must be at least 1" }
            require(offset == null || offset >= 0) { "Source offset must not be negative" }
            return SourceLocation(file, line, column, offset)
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SourceLocation &&
                file == other.file &&
                line == other.line &&
                column == other.column &&
                offset == other.offset

    override fun hashCode(): Int {
        var result = file.hashCode()
        result = 31 * result + line
        result = 31 * result + (column ?: 0)
        result = 31 * result + (offset ?: 0)
        return result
    }

    override fun toString(): String =
        "SourceLocation(file=$file, line=$line, column=$column, offset=$offset)"
}
