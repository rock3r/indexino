package dev.sebastiano.indexino.application.selectioncontext

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class KnownWrapperRule(
    val callee: String,
    val providesSelectionWhenNamedArgument: String,
    val providesSelectionWhenValue: String,
)

internal object KnownWrapperConfig {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<KnownWrapperRule> =
        javaClass
            .getResourceAsStream("/presets/known-wrappers.json")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?.let { json.decodeFromString<List<KnownWrapperRule>>(it) }
            .orEmpty()
}
