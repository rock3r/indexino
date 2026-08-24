package dev.sebastiano.indexino.engine.extension

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExtensionProtocolTest {
    @Test
    fun `incompatible protocol major rejects before accepting session`() {
        val response =
            matchingHandshake(sessionToken = "expected-token")
                .copy(protocolMajor = ExtensionProtocolConstants.PROTOCOL_MAJOR + 1)
                .validateAgainst(
                    ExtensionHostExpectation(
                        sessionToken = "session-1",
                        pluginId = "dev.example.plugin",
                        checkId = "sample-check",
                        pluginVersion = "1.0.0",
                        pluginFactSchemaVersion = 1,
                        pluginAbiTarget = "1.0.0",
                        requiredBasicFactSchema = 3,
                    ),
                    host = ExtensionHostCapabilities.forTests(),
                )

        val rejected = kotlin.test.assertIs<ExtensionHandshakeResponse.Rejected>(response)
        assertEquals("INVALID_REQUEST", rejected.code)
        assertTrue(rejected.message.contains("protocol"))
    }

    @Test
    fun `stale session token rejects before work starts`() {
        val response =
            matchingHandshake(sessionToken = "wrong-token")
                .validateAgainst(
                    ExtensionHostExpectation(
                        sessionToken = "expected-token",
                        pluginId = "dev.example.plugin",
                        checkId = "sample-check",
                        pluginVersion = "1.0.0",
                        pluginFactSchemaVersion = 1,
                        pluginAbiTarget = "1.0.0",
                        requiredBasicFactSchema = 3,
                    ),
                    host = ExtensionHostCapabilities.forTests(),
                )

        val rejected = kotlin.test.assertIs<ExtensionHandshakeResponse.Rejected>(response)
        assertEquals("INVALID_REQUEST", rejected.code)
        assertTrue(rejected.message.contains("session"))
    }

    @Test
    fun `oversized extension frames are rejected before allocation`() {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { it.writeInt(ExtensionFrameCodec.MAX_FRAME_BYTES + 1) }

        assertFailsWith<ExtensionProtocolException> {
            ExtensionFrameCodec.read(DataInputStream(ByteArrayInputStream(output.toByteArray())))
        }
    }

    @Test
    fun `matching handshake accepts additive minor versions`() {
        val response =
            matchingHandshake(sessionToken = "expected-token", minor = 99)
                .validateAgainst(
                    ExtensionHostExpectation(
                        sessionToken = "expected-token",
                        pluginId = "dev.example.plugin",
                        checkId = "sample-check",
                        pluginVersion = "1.0.0",
                        pluginFactSchemaVersion = 1,
                        pluginAbiTarget = "1.0.0",
                        requiredBasicFactSchema = 3,
                    ),
                    host = ExtensionHostCapabilities.forTests(),
                )

        assertEquals(ExtensionHandshakeResponse.Accepted, response)
    }

    private fun matchingHandshake(sessionToken: String, minor: Int = 0): ExtensionHandshake =
        ExtensionHandshake(
            protocolMajor = ExtensionProtocolConstants.PROTOCOL_MAJOR,
            protocolMinor = minor,
            sessionToken = sessionToken,
            pluginId = "dev.example.plugin",
            checkId = "sample-check",
            pluginVersion = "1.0.0",
            pluginFactSchemaVersion = 1,
            pluginAbiTarget = "1.0.0",
            requiredBasicFactSchema = 3,
        )
}
