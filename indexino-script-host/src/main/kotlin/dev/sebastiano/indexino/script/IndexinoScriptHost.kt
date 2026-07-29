package dev.sebastiano.indexino.script

import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.model.ExperimentalIndexinoApi
import dev.sebastiano.indexino.model.IndexinoInternalApi
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.LinkedHashMap
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.host.StringScriptSource
import kotlin.script.experimental.jvm.BasicJvmScriptEvaluator
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
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
        private const val DSL_VERSION = "1"
        private const val CACHE_CAPACITY = 32

        @Volatile
        internal var connectForTests: (Path) -> Indexino = { workspace ->
            Indexino.connectBlocking(workspace)
        }

        @JvmStatic public fun create(): IndexinoScriptHost = IndexinoScriptHost()
    }

    public fun run(request: ScriptRequest): ScriptReport {
        require(request.script.fileName.toString().endsWith(SCRIPT_SUFFIX)) {
            "Indexino scripts must use the $SCRIPT_SUFFIX suffix"
        }
        val source = Files.readString(request.script)
        val digest = sha256(source)
        connectForTests(request.workspace).use { indexino ->
            val snapshot = runBlocking { indexino.snapshot(request.freshness) }
            snapshot.use {
                val context = IndexinoScriptContext(snapshot)
                evaluate(
                    StringScriptSource(
                        source,
                        request.script.fileName.toString(),
                        request.script.toString(),
                    ),
                    context,
                    cacheKey(digest),
                )
                return ScriptReport.of(context.findings(), digest)
            }
        }
    }

    private fun evaluate(
        source: kotlin.script.experimental.api.SourceCode,
        context: IndexinoScriptContext,
        cacheKey: String,
    ) {
        val compiled =
            synchronized(cacheLock) { compiledScripts[cacheKey] }
                ?: run {
                    val result = runBlocking { compiler(source, compilationConfiguration) }
                    val created = result.requireSuccess().value
                    synchronized(cacheLock) {
                        compiledScripts[cacheKey]
                            ?: created.also {
                                compiledScripts[cacheKey] = it
                                if (compiledScripts.size > CACHE_CAPACITY) {
                                    compiledScripts.entries.iterator().apply {
                                        next()
                                        remove()
                                    }
                                }
                            }
                    }
                }
        val result = runBlocking {
            evaluator(compiled, ScriptEvaluationConfiguration { implicitReceivers(context) })
        }
        val evaluation = result.requireSuccess().value.returnValue
        if (evaluation is ResultValue.Error) throw evaluation.error
    }

    private fun <T> ResultWithDiagnostics<T>.requireSuccess(): ResultWithDiagnostics.Success<T> =
        this as? ResultWithDiagnostics.Success<T>
            ?: error(reports.joinToString(separator = "\n") { report -> report.message })

    private fun cacheKey(scriptDigest: String): String =
        sha256(
            listOf(
                    scriptDigest,
                    DSL_VERSION,
                    KotlinVersion.CURRENT.toString(),
                    System.getProperty("java.runtime.version"),
                    System.getProperty("java.class.path"),
                )
                .joinToString(separator = "\u0000")
        )

    private val compilationConfiguration = ScriptCompilationConfiguration {
        defaultImports("dev.sebastiano.indexino.script.*", "dev.sebastiano.indexino.model.*")
        jvm { dependenciesFromCurrentContext(wholeClasspath = true) }
        implicitReceivers(IndexinoScriptContext::class)
        compilerOptions.append("-opt-in=dev.sebastiano.indexino.model.ExperimentalIndexinoApi")
    }

    private fun sha256(source: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.toByteArray()))
}
