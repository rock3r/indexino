package dev.sebastiano.indexino.script

import dev.sebastiano.indexino.api.IndexSnapshot
import dev.sebastiano.indexino.model.CallQuery
import dev.sebastiano.indexino.model.ExperimentalIndexinoApi
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.toPath

/**
 * Locked dependency and import policy for `.indexino.kts` compilation.
 *
 * Scripts may depend on the public script DSL, `indexino-model`, the thin `indexino` API surface
 * (same artifact as the engine bytecode today), Kotlin stdlib, and kotlinx-coroutines. They must
 * not import engine/CLI/producer/PSI packages; those imports fail before compilation.
 *
 * Allowed classpath entries are derived from the code sources of already-loaded public types so
 * renamed or shaded host JARs still compile scripts.
 */
@OptIn(ExperimentalIndexinoApi::class)
internal object ScriptDependencyPolicy {
    const val HOST_API_VERSION: String = "1"

    private val REQUIRED_TYPES =
        listOf(
            IndexinoScriptHost::class.java,
            IndexinoScriptContext::class.java,
            ScriptFinding::class.java,
            CallQuery::class.java,
            IndexSnapshot::class.java,
            Unit::class.java,
        )

    private val OPTIONAL_TYPE_NAMES =
        listOf(
            "kotlinx.coroutines.BuildersKt",
            "kotlin.script.experimental.jvm.BasicJvmScriptEvaluator",
            "kotlin.script.experimental.host.StringScriptSource",
        )

    private val FORBIDDEN_IMPORT_PREFIXES =
        listOf(
            "dev.sebastiano.indexino.engine",
            "dev.sebastiano.indexino.cli",
            "dev.sebastiano.indexino.producer",
            "dev.sebastiano.indexino.core",
            "dev.sebastiano.indexino.parse",
            "dev.sebastiano.indexino.topology",
            "dev.sebastiano.indexino.plugin.selection.parse",
            "com.intellij",
            "org.jetbrains.kotlin.psi",
            "org.jetbrains.kotlin.com.intellij",
        )

    fun allowedClasspathEntries(): List<File> {
        val entries = linkedMapOf<String, File>()
        REQUIRED_TYPES.forEach { type ->
            codeSourceFile(type)?.let { entries[it.absolutePath] = it }
        }
        OPTIONAL_TYPE_NAMES.forEach { name ->
            runCatching { Class.forName(name) }
                .getOrNull()
                ?.let { type -> codeSourceFile(type)?.let { entries[it.absolutePath] = it } }
        }
        return entries.values.sortedBy { it.absolutePath }
    }

    fun allowedDependencyDigest(): String =
        sha256(
            allowedClasspathEntries().joinToString(separator = "\u0000") { file ->
                file.name + ":" + file.length()
            }
        )

    fun forbiddenImportDiagnostics(source: String): List<String> {
        val diagnostics = mutableListOf<String>()
        source.lineSequence().forEachIndexed { index, line ->
            val code = stripLineComment(line).trim()
            if (code.isEmpty()) return@forEachIndexed
            if (code.startsWith("import ")) {
                val imported = code.removePrefix("import ").removeSuffix(".*").trim()
                val forbidden = FORBIDDEN_IMPORT_PREFIXES.firstOrNull { prefix ->
                    imported == prefix || imported.startsWith("$prefix.")
                }
                if (forbidden != null) {
                    diagnostics +=
                        "line ${index + 1}: import '$imported' is outside the allowed script " +
                            "dependency set (forbidden package '$forbidden')"
                }
                return@forEachIndexed
            }
            FORBIDDEN_IMPORT_PREFIXES.forEach { prefix ->
                val pattern = Regex("""(^|[^\w.])${Regex.escape(prefix)}(\.|$)""")
                if (pattern.containsMatchIn(code)) {
                    diagnostics +=
                        "line ${index + 1}: reference to '$prefix' is outside the allowed " +
                            "script dependency set"
                }
            }
        }
        return diagnostics.distinct()
    }

    private fun stripLineComment(line: String): String {
        val commentIndex = findUnquotedLineComment(line) ?: return line
        return line.substring(0, commentIndex)
    }

    private fun findUnquotedLineComment(line: String): Int? {
        var quoted = false
        var index = 0
        while (index < line.length) {
            val ch = line[index]
            when {
                ch == '"' && (index == 0 || line[index - 1] != '\\') -> quoted = !quoted
                !quoted && ch == '/' && index + 1 < line.length && line[index + 1] == '/' ->
                    return index
            }
            index++
        }
        return null
    }

    private fun codeSourceFile(type: Class<*>): File? {
        val location = type.protectionDomain?.codeSource?.location ?: return null
        return runCatching {
                when (location.protocol) {
                    "file" -> location.toURI().toPath().toFile()
                    "jar" -> jarFileFromUrl(location.toURI())
                    else -> null
                }
            }
            .getOrNull()
            ?.takeIf { it.exists() }
    }

    private fun jarFileFromUrl(uri: URI): File? {
        val raw = uri.toString()
        val jarPart = raw.removePrefix("jar:").substringBefore("!/")
        return URI(jarPart).toPath().toFile()
    }

    private fun sha256(source: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.toByteArray()))
}
