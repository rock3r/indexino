package dev.sebastiano.indexino.topology.bazel

internal object BuildFileComments {
    fun isCommentedOutInBlock(blockBody: String, index: Int): Boolean {
        val lineStart = blockBody.lastIndexOf('\n', index - 1) + 1
        val lineEnd = blockBody.indexOf('\n', index).let { if (it < 0) blockBody.length else it }
        val line = blockBody.substring(lineStart, lineEnd)
        val relativeIndex = index - lineStart

        var inString: Char? = null
        var cursor = 0
        while (cursor < relativeIndex) {
            when (val ch = line[cursor]) {
                '"',
                '\'' ->
                    when {
                        inString == null -> inString = ch
                        inString == ch -> inString = null
                    }
                '#' ->
                    if (inString == null) {
                        return true
                    }
            }
            cursor++
        }
        return false
    }
}

internal object BuildFileSrcsParser {
    fun findSrcsValueEnd(content: String, valueStart: Int): Int? {
        var depth = 0
        var inString: Char? = null
        var index = valueStart
        while (index < content.length) {
            val ch = content[index]
            inString = toggleSrcsQuote(ch, inString)
            if (inString == null && ch == '#') {
                index = skipToLineEnd(content, index)
                continue
            }
            if (inString == null) {
                if (ch == ')' && depth == 0) {
                    return index
                }
                depth = adjustSrcsDepth(ch, depth)
                if (ch == ',' && depth == 0) {
                    return index
                }
            }
            index++
        }
        return if (index > valueStart) index else null
    }

    private fun toggleSrcsQuote(ch: Char, inString: Char?): Char? =
        when {
            ch != '"' && ch != '\'' -> inString
            inString == null -> ch
            inString == ch -> null
            else -> inString
        }

    private fun skipToLineEnd(content: String, index: Int): Int =
        content.indexOf('\n', index).let { if (it < 0) content.length else it }

    private fun adjustSrcsDepth(ch: Char, depth: Int): Int =
        when (ch) {
            '(',
            '[',
            '{' -> depth + 1
            ')',
            ']',
            '}' -> depth - 1
            else -> depth
        }
}
