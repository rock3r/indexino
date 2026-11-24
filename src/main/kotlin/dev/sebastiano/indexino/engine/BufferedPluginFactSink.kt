package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.plugin.pluginFactEntry
import dev.sebastiano.indexino.core.record.PluginFactRecord
import dev.sebastiano.indexino.model.PluginFactValue
import dev.sebastiano.indexino.model.SourceRange
import dev.sebastiano.indexino.plugin.api.PluginFactSinkV1
import org.jetbrains.kotlin.psi.KtFile

/**
 * Buffers plugin facts produced on a Kotlin parse worker. The same object is the [Function1] the
 * bundled analyzer uses to take the live [KtFile]: an analyzer that understands this internal
 * convention asks the sink for the syntax tree of [sourceText] instead of reparsing.
 *
 * Host side of the handshake: the counterpart is `SelectionContextAnalyzer.sharedSyntax` in
 * `indexino-selection-context`. Only [PluginAnalyzerRunner.BUNDLED_KOTLIN_PLUGIN_IDS] receive this
 * sink; third-party analyzers get [BufferedPhaseFactSink], which is not a [Function1]. The public
 * plugin SPI still exposes only [PluginFactSinkV1], so no PSI type crosses the ABI.
 */
internal class BufferedPluginFactSink(
    private val pluginId: String,
    private val originId: String,
    private val relativeFile: String,
    private val sourceText: String,
    private val liveFile: KtFile,
) : PluginFactSinkV1, Function1<String, Any?> {
    private val buffered = mutableListOf<Pair<CodeIndexKey, PluginFactRecord>>()

    var tookSharedSyntax: Boolean = false
        private set

    override suspend fun put(key: String, value: PluginFactValue) {
        putAt(key, null, value)
    }

    override suspend fun putAt(key: String, range: SourceRange?, value: PluginFactValue) {
        buffered += pluginFactEntry(pluginId, originId, relativeFile, key, range, value)
    }

    override fun invoke(p1: String): Any? {
        if (p1 != sourceText) return null
        tookSharedSyntax = true
        return liveFile
    }

    fun drain(): List<Pair<CodeIndexKey, PluginFactRecord>> {
        val entries = buffered.toList()
        buffered.clear()
        return entries
    }
}

/** Buffers one plugin phase's facts into the phase-wide sorted write batch. */
internal class BufferedPhaseFactSink(
    private val pluginId: String,
    private val originId: String,
    private val relativeFile: String,
    private val sink: dev.sebastiano.indexino.producer.FactSink,
) : PluginFactSinkV1 {
    override suspend fun put(key: String, value: PluginFactValue) {
        putAt(key, null, value)
    }

    override suspend fun putAt(key: String, range: SourceRange?, value: PluginFactValue) {
        val (factKey, record) = pluginFactEntry(pluginId, originId, relativeFile, key, range, value)
        sink.put(factKey, record)
    }
}
