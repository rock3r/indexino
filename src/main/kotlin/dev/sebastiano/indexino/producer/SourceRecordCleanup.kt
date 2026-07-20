package dev.sebastiano.indexino.producer

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
    }

    fun deleteXmlRecords(store: CodeIndexStore, affectedFiles: Set<String>) {
        deleteMatching(store, "sym:", "xml", ".xml", affectedFiles)
        deleteMatching(store, "ref:", "xml", ".xml", affectedFiles)
        deleteMatching(store, "res:", "xml", ".xml", affectedFiles)
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
                    else -> false
                }
            }
            .map { it.first }
            .toList()
            .forEach(store::delete)
    }
}
