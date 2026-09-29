// SPDX-License-Identifier: UEL-1.0
package dev.sebastiano.indexino.acceptance

import com.sun.management.ThreadMXBean
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.record.CodeIndexRecordCodec
import dev.sebastiano.indexino.core.record.SymbolRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.measureNanoTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Opt-in test-runtime microbenchmark. This is deliberately not a production codec or JMH test. */
internal object SymbolCodecPerformanceDriver {
    private const val DEFAULT_RECORD_COUNT = 10_000
    private const val MAX_RECORD_COUNT = 100_000
    private const val WARMUPS = 3
    private const val MEASUREMENTS = 8
    private const val ALLOCATION_TOLERANCE = 1.1
    private val cachedSerializer = CodeIndexRecord.serializer()

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..3) { "report-path [record-count] [--verify-serializer-reuse]" }
        require(args.getOrNull(2) in listOf(null, "--verify-serializer-reuse"))
        val reportPath = Path.of(args[0]).toAbsolutePath()
        val recordCount = args.getOrNull(1)?.toInt() ?: DEFAULT_RECORD_COUNT
        require(recordCount in 1..MAX_RECORD_COUNT) {
            "record-count must be between 1 and $MAX_RECORD_COUNT"
        }

        val records = List(recordCount) { samples[it % samples.size].vary(it) }
        val jsonBytes = records.map(CodeIndexRecordCodec::encode)
        val binaryBytes = records.map(SymbolBinaryCodec::encode)
        records.indices.forEach { index ->
            check(
                CodeIndexRecordCodec.json
                    .encodeToString(cachedSerializer, records[index])
                    .toByteArray()
                    .contentEquals(jsonBytes[index])
            ) {
                "JSON bytes changed at record $index"
            }
            check(CodeIndexRecordCodec.decode(jsonBytes[index]) == records[index]) {
                "JSON roundtrip mismatch at record $index"
            }
            check(SymbolBinaryCodec.decode(binaryBytes[index]) == records[index]) {
                "Binary roundtrip mismatch at record $index"
            }
        }

        var consumed = 0L
        repeat(WARMUPS) { iteration ->
            codecs(jsonBytes, binaryBytes, iteration).forEach { codec ->
                consumed = consumed xor measureEncode(records, codec).second
                consumed = consumed xor measureDecode(codec.bytes, codec).second
            }
        }

        val results =
            linkedMapOf(
                "json" to Timings(),
                "cachedJson" to Timings(),
                "explicitBinary" to Timings(),
            )
        repeat(MEASUREMENTS) { iteration ->
            codecs(jsonBytes, binaryBytes, iteration).forEach { codec ->
                val encode = measureEncode(records, codec)
                val decode = measureDecode(codec.bytes, codec)
                results.getValue(codec.name).encodeNanos += encode.first
                results.getValue(codec.name).decodeNanos += decode.first
                consumed = consumed xor encode.second xor decode.second
            }
        }
        val allocations = decodeAllocations(codecs(jsonBytes, binaryBytes, 0))

        val report = buildJsonObject {
            put("schema", 1)
            put("benchmark", "SymbolRecord codec microbenchmark")
            put(
                "jvm",
                System.getProperty("java.runtime.name") +
                    " " +
                    System.getProperty("java.runtime.version"),
            )
            put("recordCount", recordCount)
            put("warmups", WARMUPS)
            put("measurements", MEASUREMENTS)
            put("order", "alternating by measurement")
            put("checksum", consumed)
            put("fieldsCovered", JsonArray(FIELDS.map(::JsonPrimitive)))
            put("jsonEncodedBytes", jsonBytes.sumOf { it.size.toLong() })
            put("explicitBinaryEncodedBytes", binaryBytes.sumOf { it.size.toLong() })
            put(
                "decodeAllocatedBytes",
                buildJsonObject { allocations.forEach { (name, bytes) -> put(name, bytes) } },
            )
            results.forEach { (name, timings) ->
                put("${name}EncodeNanos", JsonArray(timings.encodeNanos.map(::JsonPrimitive)))
                put("${name}DecodeNanos", JsonArray(timings.decodeNanos.map(::JsonPrimitive)))
            }
            put(
                "limitation",
                "This symbol-only in-process microbenchmark cannot establish full-store or " +
                    "end-to-end gains and cannot alone justify adopting a production codec, " +
                    "Protocol Buffers, or FlatBuffers.",
            )
        }
        reportPath.parent?.let { Files.createDirectories(it) }
        Files.writeString(reportPath, report.toString() + "\n")
        println(report)
        if (args.size == 3) {
            check(
                allocations.getValue("json") <=
                    allocations.getValue("cachedJson") * ALLOCATION_TOLERANCE
            ) {
                "Production JSON decoder allocates materially more than the reusable-serializer control: $allocations"
            }
        }
    }

    private fun decodeAllocations(codecs: List<Codec>): Map<String, Long> {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return emptyMap()
        if (!bean.isThreadAllocatedMemorySupported || !bean.isThreadAllocatedMemoryEnabled)
            return emptyMap()
        val threadId = Thread.currentThread().threadId()
        return codecs.associate { codec ->
            val before = bean.getThreadAllocatedBytes(threadId)
            check(measureDecode(codec.bytes, codec).second > 0)
            codec.name to (bean.getThreadAllocatedBytes(threadId) - before)
        }
    }

    private fun codecs(
        jsonBytes: List<ByteArray>,
        binaryBytes: List<ByteArray>,
        iteration: Int,
    ): List<Codec> {
        val json =
            Codec("json", CodeIndexRecordCodec::encode, jsonBytes) { bytes ->
                CodeIndexRecordCodec.decode(bytes) as SymbolRecord
            }
        val cachedJson =
            Codec(
                "cachedJson",
                { record ->
                    CodeIndexRecordCodec.json.encodeToString(cachedSerializer, record).toByteArray()
                },
                jsonBytes,
            ) { bytes ->
                CodeIndexRecordCodec.json.decodeFromString(cachedSerializer, bytes.decodeToString())
                    as SymbolRecord
            }
        val binary =
            Codec(
                "explicitBinary",
                SymbolBinaryCodec::encode,
                binaryBytes,
                SymbolBinaryCodec::decode,
            )
        val ordered = listOf(json, cachedJson, binary)
        return if (iteration % 2 == 0) ordered else ordered.reversed()
    }

    private fun measureEncode(records: List<SymbolRecord>, codec: Codec): Pair<Long, Long> {
        var checksum = 0L
        val nanos = measureNanoTime {
            records.forEach { record ->
                val bytes = codec.encode(record)
                checksum += bytes.size + (bytes.firstOrNull()?.toLong() ?: 0L)
            }
        }
        return nanos to checksum
    }

    private fun measureDecode(bytes: List<ByteArray>, codec: Codec): Pair<Long, Long> {
        var checksum = 0L
        val nanos = measureNanoTime {
            bytes.forEach { encoded ->
                val record = codec.decode(encoded)
                checksum += record.fqn.length + record.line + record.parameterNames.size
            }
        }
        return nanos to checksum
    }

    private data class Codec(
        val name: String,
        val encode: (SymbolRecord) -> ByteArray,
        val bytes: List<ByteArray>,
        val decode: (ByteArray) -> SymbolRecord,
    )

    private data class Timings(
        val encodeNanos: MutableList<Long> = mutableListOf(),
        val decodeNanos: MutableList<Long> = mutableListOf(),
    )

    private object SymbolBinaryCodec {
        private const val VERSION = 1

        fun encode(record: SymbolRecord): ByteArray =
            ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.writeByte(VERSION)
                    output.writeString(record.fqn)
                    output.writeString(record.relativeFile)
                    output.writeInt(record.line)
                    output.writeInt(record.column)
                    output.writeString(record.kind)
                    output.writeString(record.name)
                    output.writeString(record.language)
                    output.writeNullableString(record.ownerFqn)
                    output.writeNullableString(record.signature)
                    output.writeNullableInt(record.arity)
                    output.writeStrings(record.parameterNames)
                    output.writeBoolean(record.isVararg)
                    output.writeStrings(record.aliases)
                    output.writeString(record.originId)
                }
                buffer.toByteArray()
            }

        fun decode(bytes: ByteArray): SymbolRecord =
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                check(input.readUnsignedByte() == VERSION) { "Unsupported binary symbol version" }
                val record =
                    SymbolRecord(
                        fqn = input.readString(),
                        relativeFile = input.readString(),
                        line = input.readInt(),
                        column = input.readInt(),
                        kind = input.readString(),
                        name = input.readString(),
                        language = input.readString(),
                        ownerFqn = input.readNullableString(),
                        signature = input.readNullableString(),
                        arity = input.readNullableInt(),
                        parameterNames = input.readStrings(),
                        isVararg = input.readBoolean(),
                        aliases = input.readStrings(),
                        originId = input.readString(),
                    )
                check(input.available() == 0) { "Trailing binary symbol data" }
                record
            }

        private fun DataOutputStream.writeString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeInt(bytes.size)
            write(bytes)
        }

        private fun DataInputStream.readString(): String {
            val size = readInt()
            require(size >= 0 && size <= available()) { "Invalid UTF-8 byte length: $size" }
            return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
        }

        private fun DataOutputStream.writeNullableString(value: String?) {
            writeBoolean(value != null)
            if (value != null) writeString(value)
        }

        private fun DataInputStream.readNullableString(): String? =
            if (readBoolean()) readString() else null

        private fun DataOutputStream.writeNullableInt(value: Int?) {
            writeBoolean(value != null)
            if (value != null) writeInt(value)
        }

        private fun DataInputStream.readNullableInt(): Int? = if (readBoolean()) readInt() else null

        private fun DataOutputStream.writeStrings(values: List<String>) {
            writeInt(values.size)
            values.forEach { writeString(it) }
        }

        private fun DataInputStream.readStrings(): List<String> {
            val count = readInt()
            require(count in 0..10_000) { "Invalid string count: $count" }
            return List(count) { readString() }
        }
    }

    private fun SymbolRecord.vary(index: Int): SymbolRecord =
        copy(
            fqn = "$fqn#${index % 97}",
            relativeFile = relativeFile.replace("{n}", (index % 43).toString()),
            line = line + index % 211,
            originId = if (index % 5 == 0) "dependency://org.example:lib:${index % 7}" else originId,
        )

    private val samples =
        listOf(
            SymbolRecord(
                fqn = "dev.example.ui.Card.render(kotlin.String)",
                relativeFile = "src/main/kotlin/dev/example/ui/Card{n}.kt",
                line = 42,
                column = 9,
                kind = "function",
                name = "render",
                language = "kotlin",
                ownerFqn = "dev.example.ui.Card",
                signature = "render(title: kotlin.String): kotlin.Unit",
                arity = 1,
                parameterNames = listOf("title"),
                aliases = listOf("draw", "renderCard"),
            ),
            SymbolRecord(
                fqn = "org.example.Parser.parse(java.lang.String,int)",
                relativeFile = "third_party/parser/src/Parser{n}.java",
                line = 118,
                column = 5,
                kind = "method",
                name = "parse",
                language = "java",
                ownerFqn = "org.example.Parser",
                signature = "parse(String input, int flags)",
                arity = 2,
                parameterNames = listOf("input", "flags"),
                isVararg = false,
                originId = "maven://org.example:parser:2.1",
            ),
            SymbolRecord(
                fqn = "例.工具.結合(kotlin.Array<out kotlin.String>)",
                relativeFile = "src/main/kotlin/例/工具{n}.kt",
                line = 7,
                column = 1,
                kind = "function",
                name = "結合",
                language = "kotlin",
                ownerFqn = null,
                signature = null,
                arity = null,
                parameterNames = listOf("項目", "emoji🚀"),
                isVararg = true,
                aliases = listOf("join", "связать", "σύνδεση"),
                originId = "generated://国際化",
            ),
            SymbolRecord(
                fqn = "com.acme.Repository.find(long)",
                relativeFile = "modules/data/src/Repository{n}.kt",
                line = 63,
                column = 13,
                kind = "function",
                name = "find",
                language = "kotlin",
                ownerFqn = "com.acme.Repository",
                signature = "find(id: Long): Entity?",
                arity = 1,
                parameterNames = listOf("id"),
            ),
            SymbolRecord(
                fqn = "com.acme.Repository.find(kotlin.String)",
                relativeFile = "modules/data/src/Repository{n}.kt",
                line = 67,
                kind = "function",
                name = "find",
                language = "kotlin",
                ownerFqn = "com.acme.Repository",
                signature = "find(key: String): Entity?",
                arity = 1,
                parameterNames = listOf("key"),
                aliases = listOf("lookup"),
            ),
        )

    private val FIELDS =
        listOf(
            "fqn",
            "relativeFile",
            "line",
            "column",
            "kind",
            "name",
            "language",
            "ownerFqn",
            "signature",
            "arity",
            "parameterNames",
            "isVararg",
            "aliases",
            "originId",
        )
}
