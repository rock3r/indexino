package com.kotlincodeindex.core.store

import com.kotlincodeindex.core.record.SymbolRecord

internal fun CodeIndexStore.hasSymbol(fqn: String): Boolean =
    prefixScan("sym:$fqn:").any { (_, record) -> record is SymbolRecord && record.fqn == fqn }
