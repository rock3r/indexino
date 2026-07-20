package dev.sebastiano.indexino.core.record

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal sealed interface CodeIndexRecord

@Serializable
@SerialName("meta_indexer_version")
internal data class MetaIndexerVersionRecord(val version: String) : CodeIndexRecord

@Serializable
@SerialName("file_hash")
internal data class FileHashRecord(val relativePath: String, val contentHash: String) :
    CodeIndexRecord

@Serializable
@SerialName("symbol")
internal data class SymbolRecord(
    val fqn: String,
    val relativeFile: String,
    val line: Int,
    val kind: String,
    val name: String,
    val language: String = "unknown",
    val ownerFqn: String? = null,
    val signature: String? = null,
    val arity: Int? = null,
    val aliases: List<String> = emptyList(),
) : CodeIndexRecord

@Serializable
@SerialName("reference")
internal data class ReferenceRecord(
    val symbolFqn: String,
    val relativeFile: String,
    val line: Int,
    val column: Int,
    val context: String = "call",
    val language: String = "unknown",
    val referencedName: String = symbolFqn.substringAfterLast('#').substringAfterLast('.'),
    val qualifier: String? = null,
    val candidateSymbolFqns: List<String> = listOf(symbolFqn),
    val arity: Int? = null,
) : CodeIndexRecord

@Serializable
internal data class SelectionContainerRef(val file: String, val line: Int, val function: String)

@Serializable
internal data class DisableSelectionRef(val file: String, val line: Int, val function: String)

@Serializable
@SerialName("compose_selection_site")
internal data class ComposeSelectionSiteRecord(
    val callee: String,
    val inSelectionContainer: Boolean,
    val selectionContainerCount: Int,
    val excludedByDisableSelection: Boolean,
    val selectionContainers: List<SelectionContainerRef>,
    val disableSelection: DisableSelectionRef? = null,
    val confidence: String = "lexical",
    val indexedFromCommit: String? = null,
) : CodeIndexRecord
