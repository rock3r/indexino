package dev.sebastiano.indexino.producer

import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.ReferenceRecord
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
    }

    fun deleteLanguageOriginRecords(
        store: CodeIndexStore,
        language: String,
        extension: String,
        affectedSources: Set<IndexedSource>,
    ) {
        deleteOriginMatching(store, "sym:", language, extension, affectedSources)
        deleteOriginMatching(store, "ref:", language, extension, affectedSources)
        deleteOriginMatching(store, "call:", language, extension, affectedSources)
    }

    fun deleteXmlRecords(store: CodeIndexStore, affectedFiles: Set<String>) {
        deleteMatching(store, "sym:", "xml", ".xml", affectedFiles)
        deleteMatching(store, "ref:", "xml", ".xml", affectedFiles)
        deleteMatching(store, "res:", "xml", ".xml", affectedFiles)
    }

    fun deleteXmlOriginRecords(store: CodeIndexStore, affectedSources: Set<IndexedSource>) {
        val affectedKeys = affectedSources.mapTo(mutableSetOf()) { it.originId to it.path }
        store
            .prefixScan("sym:")
            .plus(store.prefixScan("ref:"))
            .plus(store.prefixScan("res:"))
            .filter { (_, record) ->
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
                    else -> return@filter false
                }
                (originId to relativeFile) in affectedKeys
            }
            .map { it.first }
            .toList()
            .forEach(store::delete)
    }

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
                        else -> false
                    }
                matchesLanguage &&
                    when (record) {
                        is SymbolRecord -> (record.originId to record.relativeFile) in affectedKeys
                        is ReferenceRecord ->
                            (record.originId to record.relativeFile) in affectedKeys
                        is CallSiteRecord ->
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
                    else -> false
                }
            }
            .map { it.first }
            .toList()
            .forEach(store::delete)
    }
}
