package dev.sebastiano.indexino.plugin.selection

/**
 * Line-separator normalization for the embedded Kotlin parser.
 *
 * The parser mis-attaches annotations when carriage returns reach it, and the IntelliJ documents
 * behind line-number lookups reject `\r` entirely, so the PSI is built from text with `\n`
 * separators only. Line and column numbers are identical in both texts; [toOriginalOffset] maps
 * offsets back to the original file bytes the caller supplied.
 */
internal class NormalizedSourceText(originalText: String) {
    /** Text for the PSI: every `\r\n` pair or lone `\r` becomes one `\n`. */
    val text: String = originalText.replace("\r\n", "\n").replace("\r", "\n")

    private val normalizedLineStarts: IntArray
    private val originalLineStarts: IntArray

    init {
        val normalized = mutableListOf(0)
        val original = mutableListOf(0)
        var normalizedOffset = 0
        var originalOffset = 0
        while (originalOffset < originalText.length) {
            when (originalText[originalOffset]) {
                '\r' -> {
                    if (
                        originalOffset + 1 < originalText.length &&
                            originalText[originalOffset + 1] == '\n'
                    ) {
                        originalOffset += 2
                    } else {
                        originalOffset += 1
                    }
                    normalizedOffset += 1
                    normalized += normalizedOffset
                    original += originalOffset
                }
                '\n' -> {
                    originalOffset += 1
                    normalizedOffset += 1
                    normalized += normalizedOffset
                    original += originalOffset
                }
                else -> {
                    originalOffset += 1
                    normalizedOffset += 1
                }
            }
        }
        normalizedLineStarts = normalized.toIntArray()
        originalLineStarts = original.toIntArray()
    }

    /** The [originalText] offset that [normalizedOffset] in [text] corresponds to. */
    fun toOriginalOffset(normalizedOffset: Int): Int {
        val searchResult = normalizedLineStarts.binarySearch(normalizedOffset)
        val line = if (searchResult >= 0) searchResult else -searchResult - 2
        return originalLineStarts[line] + (normalizedOffset - normalizedLineStarts[line])
    }
}
