package dev.sebastiano.indexino.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeHandshakeTest {
    @Test
    fun `messages larger than one frame are transparently reassembled`() {
        val payload = ByteArray(RuntimeFrameCodec.MAX_FRAME_BYTES + 1) { index -> index.toByte() }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output -> RuntimeFrameCodec.write(output, payload) }

        assertEquals(
            payload.toList(),
            RuntimeFrameCodec.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
                .toList(),
        )
    }

    @Test
    fun `matching major handshake accepts additive minor versions`() {
        val response = roundTrip(RuntimeHandshake(RuntimeLeaseStore.PROTOCOL_MAJOR, minor = 99))

        assertEquals(RuntimeHandshakeResponse.Accepted, response)
    }

    @Test
    fun `incompatible major handshake returns invalid request remediation`() {
        val response = roundTrip(RuntimeHandshake(RuntimeLeaseStore.PROTOCOL_MAJOR + 1, minor = 0))

        val rejected = kotlin.test.assertIs<RuntimeHandshakeResponse.Rejected>(response)
        assertEquals("INVALID_REQUEST", rejected.code)
        assertTrue(rejected.message.contains("protocol"))
        assertTrue(rejected.message.contains("restart"))
    }

    @Test
    fun `oversized frames are rejected before allocation`() {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { it.writeInt(RuntimeFrameCodec.MAX_FRAME_BYTES + 1) }

        assertFailsWith<RuntimeProtocolException> {
            RuntimeFrameCodec.read(DataInputStream(ByteArrayInputStream(output.toByteArray())))
        }
    }

    private fun roundTrip(handshake: RuntimeHandshake): RuntimeHandshakeResponse {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            RuntimeFrameCodec.write(output, handshake.encode())
        }
        val frame =
            RuntimeFrameCodec.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        return RuntimeHandshake.decode(frame).respond()
    }
}
