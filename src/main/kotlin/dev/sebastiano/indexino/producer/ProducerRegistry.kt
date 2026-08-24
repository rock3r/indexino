package dev.sebastiano.indexino.producer

internal object ProducerRegistry {
    private val producers = linkedMapOf<String, IndexProducer>()

    init {
        register(FileHashProducer())
        register(dev.sebastiano.indexino.producer.java.JavaSourceProducer())
        register(dev.sebastiano.indexino.producer.kotlinpsi.KotlinPsiSymbolProducer())
        register(dev.sebastiano.indexino.producer.xml.XmlResourceProducer())
    }

    fun register(producer: IndexProducer) {
        producers[producer.id] = producer
    }

    fun get(id: String): IndexProducer? = producers[id]

    fun all(): Collection<IndexProducer> = producers.values

    fun forApplications(applicationIds: List<String>): List<IndexProducer> = buildList {
        add(dev.sebastiano.indexino.producer.xml.XmlResourceProducer())
        add(dev.sebastiano.indexino.producer.java.JavaSourceProducer())
        add(dev.sebastiano.indexino.producer.kotlinpsi.KotlinPsiSymbolProducer())
        for (id in applicationIds) {
            producers[id]?.takeUnless { it is FileHashProducer }?.let { add(it) }
        }
        add(FileHashProducer())
    }
}
