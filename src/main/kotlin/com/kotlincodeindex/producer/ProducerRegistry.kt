package com.kotlincodeindex.producer

object ProducerRegistry {
    private val producers = linkedMapOf<String, IndexProducer>()

    init {
        register(FileHashProducer())
        register(com.kotlincodeindex.producer.kotlinpsi.KotlinPsiSymbolProducer())
        register(com.kotlincodeindex.producer.selectioncontext.SelectionContextProducer())
    }

    fun register(producer: IndexProducer) {
        producers[producer.id] = producer
    }

    fun get(id: String): IndexProducer? = producers[id]

    fun all(): Collection<IndexProducer> = producers.values

    fun forApplications(applicationIds: List<String>): List<IndexProducer> =
        buildList {
            add(FileHashProducer())
            add(com.kotlincodeindex.producer.kotlinpsi.KotlinPsiSymbolProducer())
            for (id in applicationIds) {
                producers[id]?.let { add(it) }
            }
        }
}
