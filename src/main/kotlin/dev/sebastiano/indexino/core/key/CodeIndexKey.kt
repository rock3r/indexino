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

    @Suppress("TooManyFunctions")
    companion object {
        fun parse(raw: String): CodeIndexKey = CodeIndexKey(raw)

        fun sym(fqn: String): CodeIndexKey = CodeIndexKey("sym:$fqn")

        fun symbolDefinition(
            fqn: String,
            relativeFile: String,
            line: Int,
            column: Int,
        ): CodeIndexKey = CodeIndexKey("sym:$fqn:$relativeFile:$line:$column")

        fun symbolDefinition(
            fqn: String,
            originId: String,
            relativeFile: String,
            line: Int,
            column: Int,
        ): CodeIndexKey = CodeIndexKey("sym:$fqn:$originId:$relativeFile:$line:$column")

        fun ref(symbolFqn: String, relativeFile: String, line: Int): CodeIndexKey =
            CodeIndexKey("ref:$symbolFqn:$relativeFile:$line")

        fun ref(symbolFqn: String, relativeFile: String, line: Int, column: Int): CodeIndexKey =
            CodeIndexKey("ref:$symbolFqn:$relativeFile:$line:$column")

        fun ref(
            symbolFqn: String,
            originId: String,
            relativeFile: String,
            line: Int,
            column: Int,
        ): CodeIndexKey = CodeIndexKey("ref:$symbolFqn:$originId:$relativeFile:$line:$column")

        fun call(identity: String): CodeIndexKey = CodeIndexKey("call:$identity")

        fun resource(type: String, name: String, relativeFile: String, line: Int): CodeIndexKey =
            CodeIndexKey("res:$type:$name:$relativeFile:$line")

        fun resource(
            type: String,
            name: String,
            originId: String,
            relativeFile: String,
            line: Int,
            column: Int,
        ): CodeIndexKey = CodeIndexKey("res:$type:$name:$originId:$relativeFile:$line:$column")

        fun file(relativeFile: String, contentHash: String): CodeIndexKey =
            CodeIndexKey("file:$relativeFile:$contentHash")

        fun pluginFact(pluginId: String, relativeFile: String, factKey: String): CodeIndexKey =
            CodeIndexKey("plugin:$pluginId:$relativeFile:$factKey")

        fun pluginFact(
            pluginId: String,
            originId: String,
            relativeFile: String,
            factKey: String,
        ): CodeIndexKey = CodeIndexKey("plugin:$pluginId:$originId:$relativeFile:$factKey")

        fun pluginFactFilePrefix(pluginId: String, relativeFile: String): String =
            "plugin:$pluginId:$relativeFile:"

        fun pluginFactPluginPrefix(pluginId: String): String = "plugin:$pluginId:"

        fun metaIndexerVersion(): CodeIndexKey = CodeIndexKey("meta:indexer:version")
    }
}
