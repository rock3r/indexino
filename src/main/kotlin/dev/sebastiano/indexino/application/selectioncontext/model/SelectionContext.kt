package dev.sebastiano.indexino.application.selectioncontext.model

internal data class SelectionContainerInfo(val file: String, val line: Int, val function: String)

internal data class DisableSelectionInfo(val file: String, val line: Int, val function: String)

internal data class SelectionContext(
    val callee: String,
    val inSelectionContainer: Boolean,
    val selectionContainerCount: Int,
    val excludedByDisableSelection: Boolean,
    val selectionContainers: List<SelectionContainerInfo>,
    val disableSelection: DisableSelectionInfo? = null,
    val confidence: String = "lexical",
)
