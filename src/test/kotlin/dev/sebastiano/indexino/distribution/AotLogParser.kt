package dev.sebastiano.indexino.distribution

internal object AotLogParser {
    private val cachePathPattern =
        Regex("(?:[A-Za-z]:\\\\|/)[^\\r\\n]*?classes\\.jsa", RegexOption.IGNORE_CASE)
    private val linkedClassesPattern =
        Regex("Using AOT-linked classes:\\s*(true|false)", RegexOption.IGNORE_CASE)
    private val rejectionPatterns =
        listOf(
            Regex("unable to use (?:the )?AOT cache", RegexOption.IGNORE_CASE),
            Regex("AOT cache.*(?:reject|invalid|corrupt)", RegexOption.IGNORE_CASE),
            Regex("unable to use shared archive", RegexOption.IGNORE_CASE),
            Regex("shared archive.*(?:not found|invalid|corrupt)", RegexOption.IGNORE_CASE),
            Regex("specified AOT cache not found", RegexOption.IGNORE_CASE),
            Regex("loading static archive failed", RegexOption.IGNORE_CASE),
            Regex("unable to map shared spaces", RegexOption.IGNORE_CASE),
        )

    fun parse(log: String): AotLogFacts {
        val explicitlyLinkedClasses =
            linkedClassesPattern.findAll(log).lastOrNull()?.groupValues?.get(1)?.toBooleanStrict()
        val rejected = rejectionPatterns.any { it.containsMatchIn(log) }
        val linkedClasses = explicitlyLinkedClasses ?: if (rejected) false else null
        return AotLogFacts(
            cachePath = cachePathPattern.find(log)?.value?.trimEnd('.', ',', ':', ';'),
            accepted = linkedClasses == true,
            rejected = rejected,
            linkedClasses = linkedClasses,
        )
    }
}

internal data class AotLogFacts(
    val cachePath: String?,
    val accepted: Boolean,
    val rejected: Boolean,
    val linkedClasses: Boolean?,
)
