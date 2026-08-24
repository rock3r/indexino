package dev.sebastiano.indexino.script

import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.model.ExperimentalIndexinoApi
import dev.sebastiano.indexino.model.IndexinoInternalApi
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.LinkedHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.host.StringScriptSource
import kotlin.script.experimental.jvm.BasicJvmScriptEvaluator
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvmhost.JvmScriptCompiler
import kotlinx.coroutines.runBlocking

/** Evaluates trusted `.indexino.kts` files against one immutable Indexino snapshot. */
@ExperimentalIndexinoApi
@OptIn(IndexinoInternalApi::class)
public class IndexinoScriptHost private constructor() {
    private val compiler = JvmScriptCompiler()
    private val evaluator = BasicJvmScriptEvaluator()
    private val cacheLock = Any()
    private val compiledScripts = LinkedHashMap<String, CompiledScript>(CACHE_CAPACITY, 0.75F, true)

    public companion object {
        private const val SCRIPT_SUFFIX = ".indexino.kts"
        private const val CACHE_CAPACITY = 32

        @Volatile
        internal var connectForTests: (Path) -> Indexino = { workspace ->
            Indexino.connectBlocking(workspace)
        }

        @Volatile internal var overrideHostApiVersionForTests: String? = null

        @JvmStatic public fun create(): IndexinoScriptHost = IndexinoScriptHost()
    }

    public fun run(request: ScriptRequest): ScriptReport {
        require(request.script.fileName.toString().endsWith(SCRIPT_SUFFIX)) {
            "Indexino scripts must use the $SCRIPT_SUFFIX suffix"
        }
        val source = Files.readString(request.script)
        val digest = sha256(source)
        val forbidden = ScriptDependencyPolicy.forbiddenImportDiagnostics(source)
        if (forbidden.isNotEmpty()) {
            throw IndexinoScriptException.compilation(
                message =
                    "Script imports packages outside the allowed dependency set: " +
                        request.script.fileName,
                diagnostics = forbidden,
            )
        }
        connectForTests(request.workspace).use { indexino ->
            val snapshot = runBlocking { indexino.snapshot(request.freshness) }
            snapshot.use {
                val context = IndexinoScriptContext(snapshot)
                evaluateBounded(
                    source =
                        StringScriptSource(
                            source,
                            request.script.fileName.toString(),
                            request.script.toString(),
                        ),
                    context = context,
                    cacheKey = cacheKey(digest),
                    timeoutMillis = request.timeout.toMillis(),
                    cancellation = request.cancellation,
                )
                return ScriptReport.of(context.findings(), digest)
            }
        }
    }

    private fun evaluateBounded(
        source: SourceCode,
        context: IndexinoScriptContext,
        cacheKey: String,
        timeoutMillis: Long,
        cancellation: AtomicBoolean?,
    ) {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "indexino-script-eval").apply { isDaemon = true }
        }
        try {
            val future =
                executor.submit<Unit> {
                    throwIfCancelled(cancellation)
                    evaluate(source, context, cacheKey)
                    throwIfCancelled(cancellation)
                }
            val pollMillis = 50L
            var remaining = timeoutMillis
            while (true) {
                throwIfCancelled(cancellation)
                val wait = minOf(pollMillis, remaining)
                try {
                    future.get(wait, TimeUnit.MILLISECONDS)
                    return
                } catch (_: TimeoutException) {
                    remaining -= wait
                    if (remaining <= 0L) {
                        future.cancel(true)
                        throw IndexinoScriptException.timeout(
                            "Script exceeded time limit of ${timeoutMillis}ms"
                        )
                    }
                } catch (failure: ExecutionException) {
                    throw mapEvaluationFailure(failure.cause ?: failure)
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun evaluate(source: SourceCode, context: IndexinoScriptContext, cacheKey: String) {
        val compiled =
            synchronized(cacheLock) { compiledScripts[cacheKey] }
                ?: run {
                    val result = runBlocking { compiler(source, compilationConfiguration) }
                    val created =
                        when (result) {
                            is ResultWithDiagnostics.Success -> result.value
                            is ResultWithDiagnostics.Failure -> {
                                throw IndexinoScriptException.compilation(
                                    message = "Script compilation failed: ${source.name}",
                                    diagnostics = formatDiagnostics(result.reports),
                                )
                            }
                        }
                    synchronized(cacheLock) {
                        compiledScripts[cacheKey]
                            ?: created.also {
                                compiledScripts[cacheKey] = it
                                while (compiledScripts.size > CACHE_CAPACITY) {
                                    val iterator = compiledScripts.entries.iterator()
                                    iterator.next()
                                    iterator.remove()
                                }
                            }
                    }
                }
        val result = runBlocking {
            evaluator(compiled, ScriptEvaluationConfiguration { implicitReceivers(context) })
        }
        when (result) {
            is ResultWithDiagnostics.Failure ->
                throw IndexinoScriptException.runtime(
                    message =
                        formatDiagnostics(result.reports).joinToString(separator = "\n").ifBlank {
                            "Script evaluation failed"
                        },
                    cause = null,
                )
            is ResultWithDiagnostics.Success -> {
                when (val evaluation = result.value.returnValue) {
                    is ResultValue.Error ->
                        throw IndexinoScriptException.runtime(
                            message = evaluation.error.message ?: evaluation.error::class.java.name,
                            cause = evaluation.error,
                        )
                    else -> Unit
                }
            }
        }
    }

    private fun mapEvaluationFailure(failure: Throwable): Throwable =
        when (failure) {
            is IndexinoScriptException -> failure
            is InterruptedException ->
                IndexinoScriptException.cancelled("Script evaluation was cancelled")
            else ->
                IndexinoScriptException.runtime(
                    message = failure.message ?: failure::class.java.name,
                    cause = failure,
                )
        }

    private fun throwIfCancelled(cancellation: AtomicBoolean?) {
        if (cancellation?.get() == true) {
            throw IndexinoScriptException.cancelled("Script evaluation was cancelled")
        }
        if (Thread.currentThread().isInterrupted) {
            throw IndexinoScriptException.cancelled("Script evaluation was cancelled")
        }
    }

    private fun formatDiagnostics(reports: List<ScriptDiagnostic>): List<String> =
        reports.map { report ->
            buildString {
                report.location?.let { location ->
                    append(location.start.line)
                    append(':')
                    append(location.start.col)
                    append(": ")
                }
                append(report.severity.name)
                append(": ")
                append(report.message)
                report.sourcePath?.let { path ->
                    append(" [")
                    append(path)
                    append(']')
                }
            }
        }

    private fun cacheKey(scriptDigest: String): String {
        val hostApiVersion =
            overrideHostApiVersionForTests ?: ScriptDependencyPolicy.HOST_API_VERSION
        return sha256(
            listOf(
                    scriptDigest,
                    hostApiVersion,
                    KotlinVersion.CURRENT.toString(),
                    ScriptDependencyPolicy.allowedDependencyDigest(),
                )
                .joinToString(separator = "\u0000")
        )
    }

    private val compilationConfiguration = ScriptCompilationConfiguration {
        defaultImports("dev.sebastiano.indexino.script.*", "dev.sebastiano.indexino.model.*")
        jvm { updateClasspath(ScriptDependencyPolicy.allowedClasspathEntries()) }
        implicitReceivers(IndexinoScriptContext::class)
        compilerOptions.append("-opt-in=dev.sebastiano.indexino.model.ExperimentalIndexinoApi")
    }

    private fun sha256(source: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.toByteArray()))
}
