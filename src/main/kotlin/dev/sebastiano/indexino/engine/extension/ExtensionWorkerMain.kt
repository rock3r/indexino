@file:OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)

package dev.sebastiano.indexino.engine.extension

import dev.sebastiano.indexino.engine.PluginRegistry
import dev.sebastiano.indexino.model.BasicFactQueries
import dev.sebastiano.indexino.model.BasicFactSchemaVersion
import dev.sebastiano.indexino.model.CallQuery
import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.QueryPage
import dev.sebastiano.indexino.model.ReferenceQuery
import dev.sebastiano.indexino.model.ResourceQuery
import dev.sebastiano.indexino.model.SymbolQuery
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import dev.sebastiano.indexino.plugin.api.CheckContextV1
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

/** Worker entry point; executes dynamic plugin checks out-of-process. */
internal object ExtensionWorkerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val parsed = parseArgs(args)
        runWorker(
            socketPath = parsed.socket,
            sessionToken = parsed.sessionToken,
            pluginJar = parsed.pluginJar,
            pluginId = parsed.pluginId,
            checkId = parsed.checkId,
        )
    }

    internal fun runWorker(
        socketPath: Path,
        sessionToken: String,
        pluginJar: Path,
        pluginId: String,
        checkId: String,
    ) {
        val registry =
            PluginRegistry.loadFromPluginJarsOnly(
                listOf(pluginJar),
                ExtensionWorkerMain::class.java.classLoader,
            )
        val descriptor =
            registry.descriptor(PluginId.of(pluginId))
                ?: error("Plugin $pluginId is not declared in $pluginJar")
        val check =
            registry.checks.firstOrNull { it.pluginId.value == pluginId && it.check.id == checkId }
                ?: error("Check $checkId is not registered for $pluginId")
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(socketPath))
            DataInputStream(Channels.newInputStream(channel)).use { input ->
                DataOutputStream(Channels.newOutputStream(channel)).use { output ->
                    val handshake =
                        ExtensionHandshake(
                            protocolMajor = ExtensionProtocolConstants.PROTOCOL_MAJOR,
                            protocolMinor = 0,
                            sessionToken = sessionToken,
                            pluginId = pluginId,
                            checkId = checkId,
                            pluginVersion = descriptor.version,
                            pluginFactSchemaVersion = descriptor.factSchemaVersion.value,
                            pluginAbiTarget = readAbiTarget(pluginJar),
                            requiredBasicFactSchema = descriptor.requiredBasicFactSchema.value,
                        )
                    ExtensionFrameCodec.write(output, handshake.encode())
                    val handshakeResponse =
                        ExtensionHandshakeResponseCodec.decode(ExtensionFrameCodec.read(input))
                    when (handshakeResponse) {
                        ExtensionHandshakeResponse.Accepted -> Unit
                        is ExtensionHandshakeResponse.Rejected ->
                            error("${handshakeResponse.code}: ${handshakeResponse.message}")
                    }
                    ExtensionFrameCodec.write(output, ExtensionMessageCodec.encodeRunCheck())
                    val findings = runBlocking {
                        check.check.run(
                            CheckContextV1(
                                queries = ExtensionWorkerQueries,
                                facts =
                                    RemotePluginFactView(
                                        sessionToken = sessionToken,
                                        input = input,
                                        output = output,
                                    ),
                                active = { !Thread.currentThread().isInterrupted },
                            )
                        )
                    }
                    ExtensionFrameCodec.write(
                        output,
                        ExtensionMessageCodec.encodeComplete(findings),
                    )
                }
            }
        }
    }

    private fun readAbiTarget(pluginJar: Path): String =
        java.util.jar.JarFile(pluginJar.toFile()).use { jar ->
            jar.manifest?.mainAttributes?.getValue("Indexino-Plugin-ABI-Target")
                ?: error("Plugin JAR missing Indexino-Plugin-ABI-Target")
        }

    private data class ParsedArgs(
        val socket: Path,
        val sessionToken: String,
        val pluginJar: Path,
        val pluginId: String,
        val checkId: String,
    )

    private fun parseArgs(args: Array<String>): ParsedArgs {
        fun value(flag: String): String {
            val index = args.indexOf(flag)
            require(index >= 0 && index + 1 < args.size) { "Missing value for $flag" }
            return args[index + 1]
        }
        return ParsedArgs(
            socket = Path.of(value("--socket")),
            sessionToken = value("--session-token"),
            pluginJar = Path.of(value("--plugin-jar")),
            pluginId = value("--plugin-id"),
            checkId = value("--check-id"),
        )
    }
}

@OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)
private object ExtensionWorkerQueries : BasicFactQueries {
    override val generation: WorkspaceGenerationId = WorkspaceGenerationId.of("extension-worker")

    override val basicFactSchemaVersion: BasicFactSchemaVersion = BasicFactSchemaVersion.of(1)

    override suspend fun findSymbols(
        query: SymbolQuery,
        options: QueryOptions,
    ): QueryPage<dev.sebastiano.indexino.model.Symbol> = unavailable()

    override suspend fun findReferences(
        query: ReferenceQuery,
        options: QueryOptions,
    ): QueryPage<dev.sebastiano.indexino.model.Reference> = unavailable()

    override suspend fun findCalls(
        query: CallQuery,
        options: QueryOptions,
    ): QueryPage<dev.sebastiano.indexino.model.CallSite> = unavailable()

    override suspend fun findResources(
        query: ResourceQuery,
        options: QueryOptions,
    ): QueryPage<dev.sebastiano.indexino.model.ResourceDefinition> = unavailable()

    override suspend fun findResourceUsages(
        query: ResourceQuery,
        options: QueryOptions,
    ): QueryPage<dev.sebastiano.indexino.model.ResourceUsage> = unavailable()

    private fun unavailable(): Nothing =
        error("Basic fact queries are not available to dynamic extension workers in protocol v1")
}

private class RemotePluginFactView(
    private val sessionToken: String,
    private val input: DataInputStream,
    private val output: DataOutputStream,
) : dev.sebastiano.indexino.plugin.api.PluginFactViewV1 {
    override suspend fun get(key: String): dev.sebastiano.indexino.model.PluginFactValue? {
        ExtensionFrameCodec.write(
            output,
            ExtensionMessageCodec.encodePluginFactGet(sessionToken, key),
        )
        val response = ExtensionMessageCodec.decodeResponse(ExtensionFrameCodec.read(input))
        return when (response) {
            is ExtensionResponse.Success -> ExtensionPayloadCodec.decodeFactValue(response.payload)
            is ExtensionResponse.Error ->
                throw ExtensionProtocolException("${response.code}: ${response.message}")
        }
    }

    override suspend fun entries(
        prefix: String,
        options: dev.sebastiano.indexino.model.QueryOptions,
    ): dev.sebastiano.indexino.model.QueryPage<dev.sebastiano.indexino.model.PluginFactEntry> {
        ExtensionFrameCodec.write(
            output,
            ExtensionMessageCodec.encodePluginFactEntries(sessionToken, prefix, options),
        )
        val response = ExtensionMessageCodec.decodeResponse(ExtensionFrameCodec.read(input))
        return when (response) {
            is ExtensionResponse.Success ->
                ExtensionPayloadCodec.decodeFactEntries(response.payload)
            is ExtensionResponse.Error ->
                throw ExtensionProtocolException("${response.code}: ${response.message}")
        }
    }
}
