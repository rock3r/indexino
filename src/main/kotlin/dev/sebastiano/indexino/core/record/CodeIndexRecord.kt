package dev.sebastiano.indexino.core.record

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal sealed interface CodeIndexRecord

@Serializable
@SerialName("meta_indexer_version")
internal data class MetaIndexerVersionRecord(val version: String) : CodeIndexRecord

@Serializable
@SerialName("file_hash")
internal data class FileHashRecord(
    val relativePath: String,
    val contentHash: String,
    val originId: String = "workspace",
) : CodeIndexRecord

@Serializable
@SerialName("symbol")
internal data class SymbolRecord(
    val fqn: String,
    val relativeFile: String,
    val line: Int,
    val column: Int = 1,
    val kind: String,
    val name: String,
    val language: String = "unknown",
    val ownerFqn: String? = null,
    val signature: String? = null,
    val arity: Int? = null,
    val parameterNames: List<String> = emptyList(),
    val isVararg: Boolean = false,
    val aliases: List<String> = emptyList(),
    val originId: String = "workspace",
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
    val originId: String = "workspace",
) : CodeIndexRecord

@Serializable
@SerialName("resource_definition")
internal data class ResourceDefinitionRecord(
    val packageName: String? = null,
    @SerialName("resourceType") val type: String,
    val name: String,
    val qualifiers: String,
    val relativeFile: String,
    val line: Int,
    val column: Int = 1,
    val offset: Int = 0,
    val originId: String = "workspace",
) : CodeIndexRecord

@Serializable
@SerialName("resource_usage")
internal data class ResourceUsageRecord(
    val packageName: String? = null,
    @SerialName("resourceType") val type: String,
    val name: String,
    val relativeFile: String,
    val line: Int,
    val column: Int,
    val offset: Int = 0,
    val language: String,
    val originId: String = "workspace",
) : CodeIndexRecord

@Serializable
internal data class CallArgumentRecord(
    val position: Int,
    val resolvedName: String? = null,
    val kind: String,
    val startLine: Int,
    val startColumn: Int,
    val startOffset: Int,
    val endLine: Int,
    val endColumn: Int,
    val endOffset: Int,
    val nestedCallIdentities: List<String> = emptyList(),
)

@Serializable
@SerialName("call_site")
internal data class CallSiteRecord(
    val identity: String,
    val calleeName: String,
    val candidateSymbolFqns: List<String>,
    val receiver: String? = null,
    val enclosingSymbolFqn: String? = null,
    val parentCallIdentity: String? = null,
    val relativeFile: String,
    val startLine: Int,
    val startColumn: Int,
    val startOffset: Int,
    val endLine: Int,
    val endColumn: Int,
    val endOffset: Int,
    val arguments: List<CallArgumentRecord> = emptyList(),
    val confidence: String,
    val originId: String = "workspace",
) : CodeIndexRecord

@Serializable
@SerialName("plugin_fact")
internal data class PluginFactRecord(
    val pluginId: String,
    val relativeFile: String,
    val factKey: String,
    val rangeStartLine: Int? = null,
    val rangeStartColumn: Int? = null,
    val rangeStartOffset: Int? = null,
    val rangeEndLine: Int? = null,
    val rangeEndColumn: Int? = null,
    val rangeEndOffset: Int? = null,
    val encodedValue: String,
    val originId: String = "workspace",
) : CodeIndexRecord
