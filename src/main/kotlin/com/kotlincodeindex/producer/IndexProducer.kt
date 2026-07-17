package com.kotlincodeindex.producer

import com.kotlincodeindex.core.store.CodeIndexStore

interface IndexProducer {
    val id: String
    val namespace: String
        get() = id

    val displayName: String

    val progressTotal: ((IndexBuildContext) -> Int?)?
        get() = null

    fun produce(context: IndexBuildContext, store: CodeIndexStore = context.store)
}
