package dev.sebastiano.indexino.producer

import kotlin.test.Test
import kotlin.test.assertEquals

class ProducerRegistryTest {
    @Test
    fun `file hashes are committed after all source producers`() {
        assertEquals("file-hash", ProducerRegistry.forApplications(emptyList()).last().id)
    }
}
