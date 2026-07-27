package dev.sebastiano.indexino.core.plugin

import dev.sebastiano.indexino.model.PluginFactValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal object PluginFactValueCodec {
    private val json = Json { encodeDefaults = true }

    internal fun encode(value: PluginFactValue): String =
        json.encodeToString(JsonElement.serializer(), value.toJson())

    internal fun decode(encoded: String): PluginFactValue =
        fromJson(json.parseToJsonElement(encoded))

    private fun PluginFactValue.toJson(): JsonElement =
        when (this) {
            is PluginFactValue.Text -> JsonObject(mapOf("text" to JsonPrimitive(value)))
            is PluginFactValue.Integer -> JsonObject(mapOf("integer" to JsonPrimitive(value)))
            is PluginFactValue.Bool -> JsonObject(mapOf("bool" to JsonPrimitive(value)))
            is PluginFactValue.TextList ->
                JsonObject(mapOf("textList" to JsonArray(values.map(::JsonPrimitive))))
            is PluginFactValue.Struct ->
                JsonObject(mapOf("struct" to JsonObject(fields.mapValues { it.value.toJson() })))
        }

    private fun fromJson(element: JsonElement): PluginFactValue {
        val objectValue = element.jsonObject
        return when {
            "text" in objectValue ->
                PluginFactValue.Text.of(objectValue.getValue("text").jsonPrimitive.content)
            "integer" in objectValue ->
                PluginFactValue.Integer.of(objectValue.getValue("integer").jsonPrimitive.long)
            "bool" in objectValue ->
                PluginFactValue.Bool.of(objectValue.getValue("bool").jsonPrimitive.boolean)
            "textList" in objectValue ->
                PluginFactValue.TextList.of(
                    objectValue.getValue("textList").jsonArray.map { it.jsonPrimitive.content }
                )
            "struct" in objectValue ->
                PluginFactValue.Struct.of(
                    objectValue.getValue("struct").jsonObject.mapValues { fromJson(it.value) }
                )
            else -> error("Unknown plugin fact value encoding")
        }
    }
}
