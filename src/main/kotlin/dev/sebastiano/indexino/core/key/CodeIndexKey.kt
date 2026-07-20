package dev.sebastiano.indexino.core.key

/**
 * Typed index key. All persisted keys must be built through these factories — no ad hoc
 * concatenation.
 */
@JvmInline
internal value class CodeIndexKey(val value: String) {
    init {
        require(value.isNotBlank()) { "CodeIndexKey must not be blank" }
        require(':' in value) { "CodeIndexKey must contain namespace separator: $value" }
    }

    fun namespace(): String = value.substringBefore(':')

    fun hasPrefix(prefix: String): Boolean = value.startsWith(prefix)

    override fun toString(): String = value

    companion object {
        fun parse(raw: String): CodeIndexKey = CodeIndexKey(raw)

        fun sym(fqn: String): CodeIndexKey = CodeIndexKey("sym:$fqn")

        fun symbolDefinition(
            fqn: String,
            relativeFile: String,
            line: Int,
            column: Int,
        ): CodeIndexKey = CodeIndexKey("sym:$fqn:$relativeFile:$line:$column")

        fun ref(symbolFqn: String, relativeFile: String, line: Int): CodeIndexKey =
            CodeIndexKey("ref:$symbolFqn:$relativeFile:$line")

        fun ref(symbolFqn: String, relativeFile: String, line: Int, column: Int): CodeIndexKey =
            CodeIndexKey("ref:$symbolFqn:$relativeFile:$line:$column")

        fun resource(type: String, name: String, relativeFile: String, line: Int): CodeIndexKey =
            CodeIndexKey("res:$type:$name:$relativeFile:$line")

        fun file(relativeFile: String, contentHash: String): CodeIndexKey =
            CodeIndexKey("file:$relativeFile:$contentHash")

        fun composeSelectionSite(relativeFile: String, line: Int, column: Int): CodeIndexKey =
            CodeIndexKey("compose:selection-site:$relativeFile:$line:$column")

        fun composeSelectionSiteFilePrefix(relativeFile: String): String =
            "compose:selection-site:$relativeFile:"

        fun metaIndexerVersion(): CodeIndexKey = CodeIndexKey("meta:indexer:version")
    }
}
