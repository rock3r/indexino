package dev.sebastiano.indexino.engine.extension

import dev.sebastiano.indexino.core.BASIC_FACT_SCHEMA_VERSION
import dev.sebastiano.indexino.engine.PluginAbiSupport

internal data class ExtensionHostExpectation(
    val sessionToken: String,
    val pluginId: String,
    val checkId: String,
    val pluginVersion: String,
    val pluginFactSchemaVersion: Int,
    val pluginAbiTarget: String,
    val requiredBasicFactSchema: Int,
)

internal data class ExtensionHostCapabilities(
    val basicFactSchema: Int,
    val pluginAbiMinimum: String,
    val pluginAbiCurrent: String,
) {
    internal companion object {
        fun forTests(): ExtensionHostCapabilities =
            ExtensionHostCapabilities(
                basicFactSchema = BASIC_FACT_SCHEMA_VERSION,
                pluginAbiMinimum = "1.0.0",
                pluginAbiCurrent = "1.0.0",
            )

        fun load(parent: ClassLoader): ExtensionHostCapabilities {
            val abi = PluginAbiSupport.load(parent)
            return ExtensionHostCapabilities(
                basicFactSchema = BASIC_FACT_SCHEMA_VERSION,
                pluginAbiMinimum = abi.minimum.toString(),
                pluginAbiCurrent = abi.current.toString(),
            )
        }
    }
}

internal sealed interface ExtensionHandshakeResponse {
    data object Accepted : ExtensionHandshakeResponse

    class Rejected(val code: String, val message: String) : ExtensionHandshakeResponse
}

internal data class ExtensionHandshake(
    val protocolMajor: Int,
    val protocolMinor: Int,
    val sessionToken: String,
    val pluginId: String,
    val checkId: String,
    val pluginVersion: String,
    val pluginFactSchemaVersion: Int,
    val pluginAbiTarget: String,
    val requiredBasicFactSchema: Int,
) {
    fun validateAgainst(
        expectation: ExtensionHostExpectation,
        host: ExtensionHostCapabilities,
    ): ExtensionHandshakeResponse {
        if (protocolMajor != ExtensionProtocolConstants.PROTOCOL_MAJOR) {
            return ExtensionHandshakeResponse.Rejected(
                code = "INVALID_REQUEST",
                message =
                    "Extension protocol major $protocolMajor is incompatible with host major " +
                        "${ExtensionProtocolConstants.PROTOCOL_MAJOR}; upgrade Indexino or the " +
                        "extension worker",
            )
        }
        if (sessionToken != expectation.sessionToken) {
            return ExtensionHandshakeResponse.Rejected(
                code = "INVALID_REQUEST",
                message = "Extension session token is stale or unknown; restart the check",
            )
        }
        if (pluginId != expectation.pluginId || checkId != expectation.checkId) {
            return ExtensionHandshakeResponse.Rejected(
                code = "INVALID_REQUEST",
                message =
                    "Extension identity mismatch for plugin '$pluginId' check '$checkId'; " +
                        "expected '${expectation.pluginId}'/'${expectation.checkId}'",
            )
        }
        if (pluginVersion != expectation.pluginVersion) {
            return ExtensionHandshakeResponse.Rejected(
                code = "INVALID_REQUEST",
                message =
                    "Plugin version '$pluginVersion' does not match requested " +
                        "'${expectation.pluginVersion}'",
            )
        }
        if (pluginFactSchemaVersion != expectation.pluginFactSchemaVersion) {
            return ExtensionHandshakeResponse.Rejected(
                code = "INVALID_REQUEST",
                message =
                    "Plugin fact schema $pluginFactSchemaVersion does not match requested " +
                        "${expectation.pluginFactSchemaVersion}",
            )
        }
        if (requiredBasicFactSchema > host.basicFactSchema) {
            return ExtensionHandshakeResponse.Rejected(
                code = "INVALID_REQUEST",
                message =
                    "Plugin requires basic fact schema $requiredBasicFactSchema but host " +
                        "provides ${host.basicFactSchema}",
            )
        }
        return try {
            PluginAbiSupport.load(ExtensionHandshake::class.java.classLoader)
                .requireCompatible(pluginId, pluginAbiTarget)
            ExtensionHandshakeResponse.Accepted
        } catch (failure: dev.sebastiano.indexino.engine.PluginAbiCompatibilityException) {
            ExtensionHandshakeResponse.Rejected(
                code = "INVALID_REQUEST",
                message =
                    "Plugin ABI target $pluginAbiTarget is outside host range " +
                        "[${host.pluginAbiMinimum}, ${host.pluginAbiCurrent}]. ${failure.remediation}",
            )
        }
    }

    fun encode(): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { output ->
            output.writeInt(protocolMajor)
            output.writeInt(protocolMinor)
            output.writeUTF(sessionToken)
            output.writeUTF(pluginId)
            output.writeUTF(checkId)
            output.writeUTF(pluginVersion)
            output.writeInt(pluginFactSchemaVersion)
            output.writeUTF(pluginAbiTarget)
            output.writeInt(requiredBasicFactSchema)
        }
        return bytes.toByteArray()
    }

    companion object {
        fun decode(payload: ByteArray): ExtensionHandshake {
            val input = java.io.DataInputStream(payload.inputStream())
            return ExtensionHandshake(
                protocolMajor = input.readInt(),
                protocolMinor = input.readInt(),
                sessionToken = input.readUTF(),
                pluginId = input.readUTF(),
                checkId = input.readUTF(),
                pluginVersion = input.readUTF(),
                pluginFactSchemaVersion = input.readInt(),
                pluginAbiTarget = input.readUTF(),
                requiredBasicFactSchema = input.readInt(),
            )
        }
    }
}

internal object ExtensionHandshakeResponseCodec {
    private const val ACCEPTED = 1
    private const val REJECTED = 0

    fun encode(response: ExtensionHandshakeResponse): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { output ->
            when (response) {
                ExtensionHandshakeResponse.Accepted -> output.writeByte(ACCEPTED)
                is ExtensionHandshakeResponse.Rejected -> {
                    output.writeByte(REJECTED)
                    output.writeUTF(response.code)
                    output.writeUTF(response.message)
                }
            }
        }
        return bytes.toByteArray()
    }

    fun decode(payload: ByteArray): ExtensionHandshakeResponse {
        val input = java.io.DataInputStream(payload.inputStream())
        return when (input.readUnsignedByte()) {
            ACCEPTED -> ExtensionHandshakeResponse.Accepted
            REJECTED -> ExtensionHandshakeResponse.Rejected(input.readUTF(), input.readUTF())
            else -> throw ExtensionProtocolException("Unknown extension handshake response")
        }
    }
}
