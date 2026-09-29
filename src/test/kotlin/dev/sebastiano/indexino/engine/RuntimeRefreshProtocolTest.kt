package dev.sebastiano.indexino.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeRefreshProtocolTest {
    @Test
    fun `progress frames stay consistent while the journal grows`() {
        // A running refresh appends between the encoder reading the size and iterating.
        val growing =
            object : AbstractList<String>() {
                private val lines = mutableListOf("index phase=topology state=started")

                override val size: Int
                    get() = lines.size

                override fun get(index: Int): String = lines[index]

                override fun iterator(): Iterator<String> {
                    lines += "index phase=topology state=completed durationMillis=1"
                    return lines.toList().iterator()
                }
            }
        val decoded =
            RuntimeRefreshProtocol.decodeProgressResponse(
                RuntimeRefreshProtocol.progressResponse(
                    RuntimeRefreshProgress(growing, listOf("{\"event\":\"started\"}"))
                )
            )
        assertEquals(decoded.text.size, decoded.text.distinct().size)
        assertEquals(listOf("{\"event\":\"started\"}"), decoded.machine)
    }
}
