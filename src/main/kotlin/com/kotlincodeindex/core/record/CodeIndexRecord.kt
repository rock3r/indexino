package com.kotlincodeindex.core.record

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface CodeIndexRecord

@Serializable
@SerialName("meta_indexer_version")
data class MetaIndexerVersionRecord(
    val version: String,
) : CodeIndexRecord

@Serializable
@SerialName("file_hash")
data class FileHashRecord(
    val relativePath: String,
    val contentHash: String,
) : CodeIndexRecord

@Serializable
@SerialName("symbol")
data class SymbolRecord(
    val fqn: String,
    val relativeFile: String,
    val line: Int,
    val kind: String,
    val name: String,
) : CodeIndexRecord

@Serializable
@SerialName("reference")
data class ReferenceRecord(
    val symbolFqn: String,
    val relativeFile: String,
    val line: Int,
    val column: Int,
    val context: String = "call",
) : CodeIndexRecord

@Serializable
data class SelectionContainerRef(
    val file: String,
    val line: Int,
    val function: String,
)

@Serializable
data class DisableSelectionRef(
    val file: String,
    val line: Int,
    val function: String,
)

@Serializable
@SerialName("compose_selection_site")
data class ComposeSelectionSiteRecord(
    val callee: String,
    val inSelectionContainer: Boolean,
    val selectionContainerCount: Int,
    val excludedByDisableSelection: Boolean,
    val selectionContainers: List<SelectionContainerRef>,
    val disableSelection: DisableSelectionRef? = null,
    val confidence: String = "lexical",
    val indexedFromCommit: String? = null,
) : CodeIndexRecord
