package com.kotlincodeindex.application.selectioncontext

import com.kotlincodeindex.core.key.CodeIndexKey
import com.kotlincodeindex.core.record.ComposeSelectionSiteRecord
import com.kotlincodeindex.core.store.CodeIndexStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SelectionContextQueryRow(
    val file: String,
    val line: Int,
    val column: Int,
    val callee: String,
    val inSelectionContainer: Boolean,
    val selectionContainerCount: Int,
    val excludedByDisableSelection: Boolean,
    val selectionContainers: List<com.kotlincodeindex.core.record.SelectionContainerRef>,
    val disableSelection: com.kotlincodeindex.core.record.DisableSelectionRef? = null,
    val confidence: String,
    val target: String? = null,
    val module: String? = null,
    val topology: String? = null,
)

class SelectionContextQueryService(
    private val store: CodeIndexStore,
    private val scopeTarget: String? = null,
    private val scopeTopology: String? = null,
    private val presetLoader: PresetConfigLoader = PresetConfigLoader(),
) {
    fun queryPreset(preset: String): List<SelectionContextQueryRow> {
        val sites = loadAllSites()
        return when (preset) {
            "interactive-in-sc" -> {
                val callees = presetLoader.loadInteractiveCallees()
                sites.filter { row ->
                    row.callee in callees &&
                        row.inSelectionContainer &&
                        !row.excludedByDisableSelection
                }
            }
            "nested-selection-container" ->
                sites.filter { it.selectionContainerCount > 1 }
            "all-call-sites" -> sites
            else -> error("Unknown preset: $preset")
        }
    }

    fun queryPoint(file: String, line: Int, column: Int? = null): List<SelectionContextQueryRow> {
        val prefix = if (column != null) {
            CodeIndexKey.composeSelectionSite(file, line, column).value
        } else {
            "compose:selection-site:$file:$line:"
        }
        return if (column != null) {
            store.get(CodeIndexKey.parse(prefix))?.let { recordToRow(prefix, it) }?.let { listOf(it) }
                ?: emptyList()
        } else {
            store.prefixScan(prefix).map { (key, record) -> recordToRow(key.value, record) }.toList()
        }
    }

    private fun loadAllSites(): List<SelectionContextQueryRow> =
        store.prefixScan("compose:selection-site:")
            .map { (key, record) -> recordToRow(key.value, record) }
            .toList()

    private fun recordToRow(rawKey: String, record: com.kotlincodeindex.core.record.CodeIndexRecord): SelectionContextQueryRow {
        val compose = record as ComposeSelectionSiteRecord
        val parts = rawKey.removePrefix("compose:selection-site:").split(":")
        val column = parts.last().toInt()
        val line = parts[parts.size - 2].toInt()
        val file = parts.dropLast(2).joinToString(":")
        return SelectionContextQueryRow(
            file = file,
            line = line,
            column = column,
            callee = compose.callee,
            inSelectionContainer = compose.inSelectionContainer,
            selectionContainerCount = compose.selectionContainerCount,
            excludedByDisableSelection = compose.excludedByDisableSelection,
            selectionContainers = compose.selectionContainers,
            disableSelection = compose.disableSelection,
            confidence = compose.confidence,
            target = if (scopeTopology?.startsWith("gradle") == true) null else scopeTarget,
            module = if (scopeTopology?.startsWith("gradle") == true) scopeTarget else null,
            topology = scopeTopology,
        )
    }
}

object SelectionContextJsonlFormatter {
    private val json = Json { encodeDefaults = true }

    fun formatLines(rows: List<SelectionContextQueryRow>): List<String> =
        rows.map { json.encodeToString(SelectionContextQueryRow.serializer(), it) }
}

class PresetConfigLoader {
    fun loadInteractiveCallees(): Set<String> {
        val stream = javaClass.classLoader.getResourceAsStream("presets/interactive-in-sc.json")
            ?: error("Missing preset config: presets/interactive-in-sc.json")
        val config = Json.decodeFromString<InteractivePresetConfig>(stream.bufferedReader().readText())
        return config.callees.toSet()
    }
}

@Serializable
private data class InteractivePresetConfig(val callees: List<String>)
