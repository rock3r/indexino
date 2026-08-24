@file:OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)

package dev.sebastiano.indexino.engine.extension

import dev.sebastiano.indexino.api.IndexSnapshot
import dev.sebastiano.indexino.api.indexinoFailure
import dev.sebastiano.indexino.core.plugin.StorePluginFactView
import dev.sebastiano.indexino.model.CheckRequest
import dev.sebastiano.indexino.model.Finding
import dev.sebastiano.indexino.model.IndexFailureCategory
import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.QueryPage
import dev.sebastiano.indexino.plugin.api.PluginDescriptor
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class ExtensionHost
private constructor(
    private val cacheRoot: Path,
    private val workspaceId: String,
    private val capabilities: ExtensionHostCapabilities,
) {
    private val activeWorkers = Semaphore(ExtensionProtocolConstants.MAX_CONCURRENT_WORKERS)
    private val shutdown = AtomicBoolean()

    fun runCheck(
        snapshot: IndexSnapshot,
        request: CheckRequest,
        descriptor: PluginDescriptor,
        pluginJar: Path,
        options: QueryOptions,
        deadline: Duration =
            Duration.ofMillis(ExtensionProtocolConstants.DEFAULT_CHECK_DEADLINE_MILLIS),
    ): QueryPage<Finding> {
        val deadlineAtNanos = System.nanoTime() + deadline.toNanos()
        fun remainingMillis(): Long =
            ((deadlineAtNanos - System.nanoTime()) / ExtensionProtocolConstants.NANOS_PER_MILLIS)
                .coerceAtLeast(0L)
        if (shutdown.get()) {
            throw indexinoFailure(
                category = IndexFailureCategory.CLOSED,
                code = "extension_host_closed",
                message = "Extension host is shut down",
                retryable = false,
            )
        }
        if (!activeWorkers.tryAcquire(remainingMillis(), TimeUnit.MILLISECONDS)) {
            throw indexinoFailure(
                category = IndexFailureCategory.STORAGE_BUSY,
                code = "extension_capacity",
                message =
                    "Too many concurrent extension workers; retry after an in-flight check finishes",
                retryable = true,
            )
        }
        val sessionToken = UUID.randomUUID().toString()
        val sessionSuffix =
            sessionToken.replace("-", "").take(ExtensionProtocolConstants.SESSION_SUFFIX_LENGTH)
        val socketPath = cacheRoot.resolve("runtime/e-$sessionSuffix.sock")
        val expectation =
            ExtensionHostExpectation(
                sessionToken = sessionToken,
                pluginId = request.pluginId.value,
                checkId = request.checkId,
                pluginVersion = descriptor.version,
                pluginFactSchemaVersion = descriptor.factSchemaVersion.value,
                pluginAbiTarget = readPluginAbiTarget(pluginJar),
                requiredBasicFactSchema = descriptor.requiredBasicFactSchema.value,
            )
        val queryBudget = AtomicInteger(ExtensionProtocolConstants.MAX_QUERIES_PER_CHECK)
        val cancelled = AtomicBoolean()
        val server =
            ExtensionSessionServer(
                endpoint = socketPath,
                sessionToken = sessionToken,
                expectation = expectation,
                capabilities = capabilities,
                snapshot = snapshot,
                pluginId = request.pluginId,
                queryBudget = queryBudget,
                cancelled = cancelled,
            )
        try {
            Files.createDirectories(socketPath.parent)
            server.start()
            val process =
                ExtensionJvmLauncher.launchWorker(
                    socketPath = socketPath,
                    sessionToken = sessionToken,
                    pluginJar = pluginJar,
                    pluginId = request.pluginId.value,
                    checkId = request.checkId,
                )
            val completed =
                server.awaitCompletion(remainingMillis()) { findings ->
                    ExtensionFindingValidator.validate(request, findings)
                }
            val findings =
                finalizeWorker(process, completed, Duration.ofMillis(remainingMillis()), cancelled)
            return paginateFindings(findings, options)
        } finally {
            cancelled.set(true)
            server.close()
            Files.deleteIfExists(socketPath)
            activeWorkers.release()
        }
    }

    fun close() {
        shutdown.set(true)
    }

    private fun finalizeWorker(
        process: Process,
        completed: List<Finding>?,
        deadline: Duration,
        cancelled: AtomicBoolean,
    ): List<Finding> {
        if (!process.waitFor(deadline.toMillis(), TimeUnit.MILLISECONDS)) {
            ExtensionProcessSupport.destroyProcessTree(process)
            cancelled.set(true)
            throw indexinoFailure(
                category = IndexFailureCategory.PLUGIN,
                code = "extension_timeout",
                message = "Extension check exceeded the ${deadline.toSeconds()}s deadline",
                retryable = true,
            )
        }
        if (process.exitValue() != 0 && completed == null) {
            throw indexinoFailure(
                category = IndexFailureCategory.PLUGIN,
                code = "extension_crash",
                message = "Extension worker exited with status ${process.exitValue()}",
                retryable = true,
            )
        }
        return completed
            ?: throw indexinoFailure(
                category = IndexFailureCategory.PLUGIN,
                code = "extension_incomplete",
                message = "Extension worker ended without findings",
                retryable = true,
            )
    }

    private fun paginateFindings(
        findings: List<Finding>,
        options: QueryOptions,
    ): QueryPage<Finding> {
        val start = options.offset.coerceAtMost(findings.size)
        val end = (start + options.limit).coerceAtMost(findings.size)
        return QueryPage(
            items = findings.subList(start, end),
            offset = options.offset,
            limit = options.limit,
            hasMore = end < findings.size,
            nextCursor = null,
            totalCount = findings.size,
        )
    }

    internal companion object {
        fun create(cacheRoot: Path, workspaceId: String, parent: ClassLoader): ExtensionHost =
            ExtensionHost(
                cacheRoot = cacheRoot,
                workspaceId = workspaceId,
                capabilities = ExtensionHostCapabilities.load(parent),
            )

        private fun readPluginAbiTarget(pluginJar: Path): String {
            java.util.jar.JarFile(pluginJar.toFile()).use { jar ->
                return jar.manifest?.mainAttributes?.getValue("Indexino-Plugin-ABI-Target")
                    ?: throw indexinoFailure(
                        category = IndexFailureCategory.INVALID_REQUEST,
                        code = "plugin_abi_missing",
                        message =
                            "Plugin JAR is missing Indexino-Plugin-ABI-Target; rebuild the plugin " +
                                "against indexino-plugin-api",
                        retryable = false,
                    )
            }
        }
    }
}

private class ExtensionSessionServer(
    private val endpoint: Path,
    private val sessionToken: String,
    private val expectation: ExtensionHostExpectation,
    private val capabilities: ExtensionHostCapabilities,
    private val snapshot: IndexSnapshot,
    private val pluginId: PluginId,
    private val queryBudget: AtomicInteger,
    private val cancelled: AtomicBoolean,
) : AutoCloseable {
    @Volatile private var findings: List<Finding>? = null

    @Volatile private var failure: ExtensionResponse.Error? = null

    private val done = java.util.concurrent.CountDownLatch(1)
    private var acceptThread: Thread? = null
    private var running = false
    private var serverChannel: java.nio.channels.ServerSocketChannel? = null

    @OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)
    private val factView by lazy {
        StorePluginFactView(snapshot.localStoreForExtension(), pluginId.value)
    }

    fun start() {
        running = true
        Files.createDirectories(endpoint.parent)
        Files.deleteIfExists(endpoint)
        serverChannel =
            java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX).also {
                channel ->
                channel.bind(java.net.UnixDomainSocketAddress.of(endpoint))
            }
        acceptThread =
            Thread(
                    {
                        try {
                            serverChannel?.accept()?.use { channel -> handle(channel) }
                        } catch (_: java.io.IOException) {
                            if (running) {
                                failure =
                                    ExtensionResponse.Error("PLUGIN", "Extension session failed")
                                done.countDown()
                            }
                        }
                    },
                    "indexino-extension-session",
                )
                .also { it.isDaemon = true }
                .also(Thread::start)
    }

    private fun handle(channel: java.nio.channels.SocketChannel) {
        DataInputStream(Channels.newInputStream(channel)).use { input ->
            DataOutputStream(Channels.newOutputStream(channel)).use { output ->
                val handshake = ExtensionHandshake.decode(ExtensionFrameCodec.read(input))
                val response = handshake.validateAgainst(expectation, capabilities)
                ExtensionFrameCodec.write(output, ExtensionHandshakeResponseCodec.encode(response))
                if (response != ExtensionHandshakeResponse.Accepted) {
                    failure = ExtensionResponse.Error("INVALID_REQUEST", "Handshake rejected")
                    done.countDown()
                    return
                }
                while (!cancelled.get()) {
                    val frame =
                        try {
                            ExtensionFrameCodec.read(input)
                        } catch (_: java.io.EOFException) {
                            break
                        }
                    val command = ExtensionMessageCodec.decodeCommand(frame)
                    if (dispatchCommand(command, output)) return
                }
            }
        }
        markDisconnectedIfNeeded()
        done.countDown()
    }

    private fun dispatchCommand(command: ExtensionCommand, output: DataOutputStream): Boolean =
        when (command) {
            ExtensionCommand.RunCheck -> false
            is ExtensionCommand.Ping -> {
                respondQuery(output, command.sessionToken) {
                    ExtensionMessageCodec.encodeSuccess(byteArrayOf())
                }
                false
            }
            is ExtensionCommand.PluginFactGet -> {
                respondQuery(output, command.sessionToken) {
                    val value = kotlinx.coroutines.runBlocking { factView.get(command.key) }
                    ExtensionMessageCodec.encodeSuccess(
                        ExtensionPayloadCodec.encodeFactValue(value)
                    )
                }
                false
            }
            is ExtensionCommand.PluginFactEntries -> {
                respondQuery(output, command.sessionToken) {
                    val page =
                        kotlinx.coroutines.runBlocking {
                            factView.entries(command.prefix, command.options)
                        }
                    ExtensionMessageCodec.encodeSuccess(
                        ExtensionPayloadCodec.encodeFactEntries(page)
                    )
                }
                false
            }
            is ExtensionCommand.CompleteFindings -> {
                findings = command.findings
                done.countDown()
                true
            }
            is ExtensionCommand.Error -> {
                failure = ExtensionResponse.Error(command.code, command.message)
                done.countDown()
                true
            }
            ExtensionCommand.Cancelled -> {
                failure = ExtensionResponse.Error("CANCELLED", "Extension check cancelled")
                done.countDown()
                true
            }
        }

    private fun markDisconnectedIfNeeded() {
        if (findings == null && failure == null) {
            failure = ExtensionResponse.Error("PLUGIN", "Extension worker disconnected")
        }
    }

    private inline fun respondQuery(
        output: DataOutputStream,
        token: String,
        block: () -> ByteArray,
    ) {
        ensureSession(token)
        if (queryBudget.decrementAndGet() < 0) {
            ExtensionFrameCodec.write(
                output,
                ExtensionMessageCodec.encodeResponseError(
                    "INVALID_REQUEST",
                    "Extension query budget exhausted",
                ),
            )
            return
        }
        ExtensionFrameCodec.write(output, block())
    }

    private fun ensureSession(token: String) {
        if (token != sessionToken) {
            throw ExtensionProtocolException("Stale extension session token")
        }
    }

    fun awaitCompletion(
        timeoutMillis: Long,
        validate: (List<Finding>) -> List<Finding>,
    ): List<Finding>? {
        if (!done.await(timeoutMillis, TimeUnit.MILLISECONDS)) return null
        failure?.let { error ->
            throw indexinoFailure(
                category = IndexFailureCategory.PLUGIN,
                code = error.code.lowercase(),
                message = error.message,
                retryable = error.code != "INVALID_REQUEST",
            )
        }
        return findings?.let(validate)
    }

    override fun close() {
        cancelled.set(true)
        running = false
        runCatching { serverChannel?.close() }
        acceptThread?.join(ExtensionProtocolConstants.THREAD_JOIN_TIMEOUT_MILLIS)
        Files.deleteIfExists(endpoint)
    }
}

internal object ExtensionFindingValidator {
    fun validate(request: CheckRequest, findings: List<Finding>): List<Finding> {
        if (findings.size > ExtensionProtocolConstants.MAX_FINDINGS_PER_CHECK) {
            throw indexinoFailure(
                category = IndexFailureCategory.INVALID_REQUEST,
                code = "extension_findings_limit",
                message =
                    "Extension returned ${findings.size} findings; limit is " +
                        ExtensionProtocolConstants.MAX_FINDINGS_PER_CHECK,
                retryable = false,
            )
        }
        findings.forEach { finding ->
            require(finding.plugin == request.pluginId) {
                "Finding plugin ${finding.plugin.value} does not match ${request.pluginId.value}"
            }
            require(finding.checkId == request.checkId) {
                "Finding check '${finding.checkId}' does not match '${request.checkId}'"
            }
            require(finding.message.isNotBlank()) { "Finding message must not be blank" }
        }
        return findings
    }
}
