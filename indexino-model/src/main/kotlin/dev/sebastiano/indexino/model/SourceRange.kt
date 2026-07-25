package dev.sebastiano.indexino.model

public class SourceRange
private constructor(public val start: SourceLocation, public val end: SourceLocation) {
    public companion object {
        @JvmStatic
        public fun of(start: SourceLocation, end: SourceLocation): SourceRange {
            require(start.file == end.file) {
                "Source range locations must belong to the same file"
            }
            require(start.precedesOrEquals(end)) { "Source range end must not precede its start" }
            return SourceRange(start, end)
        }

        private fun SourceLocation.precedesOrEquals(other: SourceLocation): Boolean {
            if (offset != null && other.offset != null) return offset <= other.offset
            if (line != other.line) return line < other.line
            return column == null || other.column == null || column <= other.column
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is SourceRange && start == other.start && end == other.end

    override fun hashCode(): Int = 31 * start.hashCode() + end.hashCode()

    override fun toString(): String = "SourceRange(start=$start, end=$end)"
}
