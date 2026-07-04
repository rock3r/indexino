package com.kotlincodeindex.application.selectioncontext

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class KnownWrapperRule(
    val callee: String,
    val providesSelectionWhenNamedArgument: String,
    val providesSelectionWhenValue: String,
)

object KnownWrapperConfig {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<KnownWrapperRule> =
        javaClass.getResourceAsStream("/presets/known-wrappers.json")
            ?.bufferedReader()
            ?.readText()
            ?.let { json.decodeFromString<List<KnownWrapperRule>>(it) }
            ?: emptyList()
}
