@file:OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)

package dev.sebastiano.indexino.engine.extension

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExtensionHostResilienceTest {
    @Test
    fun `invalid findings fail validation before returning to callers`() {
        assertFailsWith<IllegalArgumentException> {
            ExtensionFindingValidator.validate(
                dev.sebastiano.indexino.model.CheckRequest.of(
                    dev.sebastiano.indexino.model.PluginId.of("dev.example.plugin"),
                    "expected-check",
                ),
                listOf(
                    dev.sebastiano.indexino.model.Finding(
                        plugin = dev.sebastiano.indexino.model.PluginId.of("dev.other.plugin"),
                        checkId = "wrong-check",
                        message = "finding",
                        range = null,
                        properties = emptyMap(),
                    )
                ),
            )
        }
    }

    @Test
    fun `malformed extension command tag is rejected`() {
        assertFailsWith<ExtensionProtocolException> {
            ExtensionMessageCodec.decodeCommand(byteArrayOf(99))
        }
    }

    @Test
    fun `oversized finding batches are rejected at decode time`() {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { output ->
            output.writeByte(ExtensionMessageCodec.CMD_COMPLETE_FINDINGS)
            output.writeInt(ExtensionProtocolConstants.MAX_FINDINGS_PER_CHECK + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            ExtensionMessageCodec.decodeCommand(payload.toByteArray())
        }
    }

    @Test
    fun `stale session token fails handshake validation`() {
        val response =
            ExtensionHandshake(
                    protocolMajor = ExtensionProtocolConstants.PROTOCOL_MAJOR,
                    protocolMinor = 0,
                    sessionToken = "stale",
                    pluginId = "dev.example",
                    checkId = "check",
                    pluginVersion = "1.0.0",
                    pluginFactSchemaVersion = 1,
                    pluginAbiTarget = "1.0.0",
                    requiredBasicFactSchema = 3,
                )
                .validateAgainst(
                    ExtensionHostExpectation(
                        sessionToken = "live",
                        pluginId = "dev.example",
                        checkId = "check",
                        pluginVersion = "1.0.0",
                        pluginFactSchemaVersion = 1,
                        pluginAbiTarget = "1.0.0",
                        requiredBasicFactSchema = 3,
                    ),
                    ExtensionHostCapabilities.forTests(),
                )
        assertTrue(response is ExtensionHandshakeResponse.Rejected)
    }
}
