package com.kotlincodeindex.core.record

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

object CodeIndexRecordCodec {
    private val module = SerializersModule {
        polymorphic(CodeIndexRecord::class) {
            subclass(MetaIndexerVersionRecord::class)
            subclass(FileHashRecord::class)
            subclass(SymbolRecord::class)
            subclass(ReferenceRecord::class)
            subclass(ComposeSelectionSiteRecord::class)
        }
    }

    val json: Json = Json {
        serializersModule = module
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(record: CodeIndexRecord): ByteArray = json.encodeToString(CodeIndexRecord.serializer(), record).toByteArray()

    fun decode(bytes: ByteArray): CodeIndexRecord =
        json.decodeFromString(CodeIndexRecord.serializer(), bytes.decodeToString())
}
