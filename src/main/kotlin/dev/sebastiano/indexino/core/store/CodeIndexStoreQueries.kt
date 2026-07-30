package dev.sebastiano.indexino.core.store

import dev.sebastiano.indexino.core.record.SymbolRecord

internal fun CodeIndexStore.hasSymbol(fqn: String): Boolean =
    prefixScan("sym:$fqn:").any { (_, record) -> record is SymbolRecord && record.fqn == fqn }

internal fun CodeIndexStore.hasSymbol(fqn: String, originId: String): Boolean =
    prefixScan("sym:$fqn:").any { (_, record) ->
        record is SymbolRecord && record.fqn == fqn && record.originId == originId
    }
