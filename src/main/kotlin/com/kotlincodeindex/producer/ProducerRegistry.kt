package com.kotlincodeindex.producer

object ProducerRegistry {
    private val producers = linkedMapOf<String, IndexProducer>()

    init {
        register(FileHashProducer())
        register(com.kotlincodeindex.producer.java.JavaSourceProducer())
        register(com.kotlincodeindex.producer.kotlinpsi.KotlinPsiSymbolProducer())
        register(com.kotlincodeindex.producer.xml.XmlResourceProducer())
        register(com.kotlincodeindex.producer.selectioncontext.SelectionContextProducer())
    }

    fun register(producer: IndexProducer) {
        producers[producer.id] = producer
    }

    fun get(id: String): IndexProducer? = producers[id]

    fun all(): Collection<IndexProducer> = producers.values

    fun forApplications(applicationIds: List<String>): List<IndexProducer> = buildList {
        add(com.kotlincodeindex.producer.java.JavaSourceProducer())
        add(com.kotlincodeindex.producer.kotlinpsi.KotlinPsiSymbolProducer())
        add(com.kotlincodeindex.producer.xml.XmlResourceProducer())
        for (id in applicationIds) {
            producers[id]?.takeUnless { it is FileHashProducer }?.let { add(it) }
        }
        add(FileHashProducer())
    }
}
