package dev.sebastiano.indexino.producer

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.ResourceDefinitionRecord
import dev.sebastiano.indexino.core.record.ResourceUsageRecord
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore

internal object SourceRecordCleanup {
    fun deleteLanguageRecords(
        store: CodeIndexStore,
        language: String,
        extension: String,
        affectedFiles: Set<String>,
    ) {
        deleteMatching(store, "sym:", language, extension, affectedFiles)
        deleteMatching(store, "ref:", language, extension, affectedFiles)
        deleteMatching(store, "call:", language, extension, affectedFiles)
        deleteMatching(store, "resuse:", language, extension, affectedFiles)
    }

    fun deleteLanguageOriginRecords(
        store: CodeIndexStore,
        language: String,
        extension: String,
        affectedSources: Set<IndexedSource>,
    ) {
        if (affectedSources.isEmpty()) return
        deleteOriginMatching(store, "sym:", language, extension, affectedSources)
        deleteOriginMatching(store, "ref:", language, extension, affectedSources)
        deleteOriginMatching(store, "call:", language, extension, affectedSources)
        deleteOriginMatching(store, "resuse:", language, extension, affectedSources)
    }

    fun deleteXmlRecords(store: CodeIndexStore, affectedFiles: Set<String>) {
        deleteMatching(store, "sym:", "xml", ".xml", affectedFiles)
        deleteMatching(store, "ref:", "xml", ".xml", affectedFiles)
        deleteMatching(store, "res:", "xml", ".xml", affectedFiles)
        deleteMatching(store, "resdef:", "xml", ".xml", affectedFiles)
    }

    fun deleteXmlOriginRecords(store: CodeIndexStore, affectedSources: Set<IndexedSource>) {
        if (affectedSources.isEmpty()) return
        val affectedKeys = affectedSources.mapTo(mutableSetOf()) { it.originId to it.path }
        for (prefix in listOf("sym:", "ref:", "res:", "resdef:", "resuse:")) {
            val keys = mutableListOf<CodeIndexKey>()
            store.forEachPrefix(prefix) { key, record ->
                val originId: String
                val relativeFile: String
                when (record) {
                    is SymbolRecord -> {
                        originId = record.originId
                        relativeFile = record.relativeFile
                    }
                    is ReferenceRecord -> {
                        originId = record.originId
                        relativeFile = record.relativeFile
                    }
                    is ResourceDefinitionRecord -> {
                        originId = record.originId
                        relativeFile = record.relativeFile
                    }
                    is ResourceUsageRecord -> {
                        originId = record.originId
                        relativeFile = record.relativeFile
                    }
                    else -> return@forEachPrefix true
                }
                if ((originId to relativeFile) in affectedKeys) keys += key
                true
            }
            keys.chunked(DELETE_BATCH_SIZE).forEach { batch ->
                store.transaction { batch.forEach(store::delete) }
            }
        }
    }

    private const val DELETE_BATCH_SIZE = 256

    private fun deleteOriginMatching(
        store: CodeIndexStore,
        prefix: String,
        language: String,
        extension: String,
        affectedSources: Set<IndexedSource>,
    ) {
        val affectedKeys = affectedSources.mapTo(mutableSetOf()) { it.originId to it.path }
        store
            .prefixScan(prefix)
            .filter { (_, record) ->
                val matchesLanguage =
                    when (record) {
                        is SymbolRecord ->
                            record.language == language || record.relativeFile.endsWith(extension)
                        is ReferenceRecord ->
                            record.language == language || record.relativeFile.endsWith(extension)
                        is CallSiteRecord -> record.relativeFile.endsWith(extension)
                        is ResourceUsageRecord ->
                            record.language == language || record.relativeFile.endsWith(extension)
                        else -> false
                    }
                matchesLanguage &&
                    when (record) {
                        is SymbolRecord -> (record.originId to record.relativeFile) in affectedKeys
                        is ReferenceRecord ->
                            (record.originId to record.relativeFile) in affectedKeys
                        is CallSiteRecord ->
                            (record.originId to record.relativeFile) in affectedKeys
                        is ResourceUsageRecord ->
                            (record.originId to record.relativeFile) in affectedKeys
                        else -> false
                    }
            }
            .map { it.first }
            .toList()
            .forEach(store::delete)
    }

    private fun deleteMatching(
        store: CodeIndexStore,
        prefix: String,
        language: String,
        extension: String,
        affectedFiles: Set<String>,
    ) {
        store
            .prefixScan(prefix)
            .filter { (_, record) ->
                when (record) {
                    is SymbolRecord ->
                        record.relativeFile in affectedFiles &&
                            (record.language == language || record.relativeFile.endsWith(extension))
                    is ReferenceRecord ->
                        record.relativeFile in affectedFiles &&
                            (record.language == language || record.relativeFile.endsWith(extension))
                    is CallSiteRecord ->
                        record.relativeFile in affectedFiles &&
                            record.relativeFile.endsWith(extension)
                    is ResourceDefinitionRecord ->
                        record.relativeFile in affectedFiles &&
                            record.relativeFile.endsWith(extension)
                    is ResourceUsageRecord ->
                        record.relativeFile in affectedFiles &&
                            (record.language == language || record.relativeFile.endsWith(extension))
                    else -> false
                }
            }
            .map { it.first }
            .toList()
            .forEach(store::delete)
    }
}
