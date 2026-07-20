package dev.sebastiano.indexino.producer

import dev.sebastiano.indexino.core.store.CodeIndexStore

internal interface IndexProducer {
    val id: String
    val namespace: String
        get() = id

    val displayName: String

    val progressTotal: ((IndexBuildContext) -> Int?)?
        get() = null

    fun produce(context: IndexBuildContext, store: CodeIndexStore = context.store)
}
