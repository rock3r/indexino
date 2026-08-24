package dev.sebastiano.indexino.script

import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Locked dependency and import policy for `.indexino.kts` compilation.
 *
 * Scripts may depend on the public script DSL, `indexino-model`, the thin `indexino` API surface
 * (same artifact as the engine bytecode today), Kotlin stdlib, and kotlinx-coroutines. They must
 * not import engine/CLI/producer/PSI packages; those imports fail before compilation.
 */
internal object ScriptDependencyPolicy {
    const val HOST_API_VERSION: String = "1"

    private val ALLOWED_CLASSPATH_MARKERS =
        listOf(
            "indexino-model",
            "indexino-script-host",
            "/indexino-",
            "indexino.jar",
            "classes/kotlin/main",
            "classes/java/main",
            "kotlin-stdlib",
            "kotlin-reflect",
            "kotlin-script-runtime",
            "kotlin-scripting",
            "annotations-",
            "kotlinx-coroutines",
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
        val path = System.getProperty("java.class.path").orEmpty()
        if (path.isBlank()) return emptyList()
        return path
            .split(File.pathSeparatorChar)
            .map(::File)
            .filter { it.exists() }
            .filter { entry ->
                val normalized = entry.absolutePath.replace('\\', '/')
                ALLOWED_CLASSPATH_MARKERS.any { marker -> normalized.contains(marker) }
            }
            .distinctBy { it.absolutePath }
            .sortedBy { it.absolutePath }
    }

    fun allowedDependencyDigest(): String =
        sha256(allowedClasspathEntries().joinToString(separator = "\u0000") { it.name })

    fun forbiddenImportDiagnostics(source: String): List<String> {
        val diagnostics = mutableListOf<String>()
        source.lineSequence().forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("import ")) return@forEachIndexed
            val imported = trimmed.removePrefix("import ").removeSuffix(".*").trim()
            val forbidden = FORBIDDEN_IMPORT_PREFIXES.firstOrNull { prefix ->
                imported == prefix || imported.startsWith("$prefix.")
            }
            if (forbidden != null) {
                diagnostics +=
                    "line ${index + 1}: import '$imported' is outside the allowed script " +
                        "dependency set (forbidden package '$forbidden')"
            }
        }
        return diagnostics
    }

    private fun sha256(source: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.toByteArray()))
}
