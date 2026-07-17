package com.kotlincodeindex.cli

import com.kotlincodeindex.core.record.CodeIndexRecord
import com.kotlincodeindex.core.record.CodeIndexRecordCodec
import com.kotlincodeindex.producer.MACHINE_PROGRESS_PROTOCOL_VERSION
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class JsonlLookupProgressReporter(private val emitLine: (String) -> Unit) {
    fun started(command: String, query: JsonObject) {
        emitEvent(event = "lookup_started", command = command, query = query)
    }

    fun match(command: String, emittedMatchCount: Int, record: CodeIndexRecord) {
        emitEvent(
            event = "lookup_match",
            command = command,
            emittedMatchCount = emittedMatchCount,
            record = Json.parseToJsonElement(CodeIndexRecordCodec.encode(record).decodeToString()),
        )
    }

    fun completed(command: String, totalMatchCount: Int, durationMillis: Long) {
        emitEvent(
            event = "lookup_completed",
            command = command,
            totalMatchCount = totalMatchCount,
            durationMillis = durationMillis,
        )
    }

    fun failed(command: String, reason: String, message: String, durationMillis: Long) {
        emitEvent(
            event = "lookup_failed",
            command = command,
            failureReason = reason,
            message = message,
            durationMillis = durationMillis,
        )
    }

    private fun emitEvent(
        event: String,
        command: String,
        query: JsonObject? = null,
        emittedMatchCount: Int? = null,
        record: JsonElement? = null,
        totalMatchCount: Int? = null,
        durationMillis: Long? = null,
        failureReason: String? = null,
        message: String? = null,
    ) {
        val fields = linkedMapOf<String, JsonElement>()
        fields["version"] = JsonPrimitive(MACHINE_PROGRESS_PROTOCOL_VERSION)
        fields["event"] = JsonPrimitive(event)
        fields["command"] = JsonPrimitive(command)
        query?.let { fields["query"] = it }
        emittedMatchCount?.let { fields["emittedMatchCount"] = JsonPrimitive(it) }
        record?.let { fields["record"] = it }
        totalMatchCount?.let { fields["totalMatchCount"] = JsonPrimitive(it) }
        durationMillis?.let { fields["durationMillis"] = JsonPrimitive(it) }
        failureReason?.let { fields["failureReason"] = JsonPrimitive(it) }
        message?.let { fields["message"] = JsonPrimitive(it) }
        emitLine(Json.encodeToString(JsonObject(fields)))
    }
}
