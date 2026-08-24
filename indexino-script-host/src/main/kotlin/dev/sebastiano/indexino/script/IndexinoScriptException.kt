package dev.sebastiano.indexino.script

import dev.sebastiano.indexino.model.ExperimentalIndexinoApi
import java.util.Collections

/** Structured failure from compiling or evaluating a trusted `.indexino.kts` script. */
@ExperimentalIndexinoApi
public class IndexinoScriptException
private constructor(
    public val kind: Kind,
    message: String,
    diagnostics: List<String>,
    cause: Throwable?,
) : RuntimeException(message, cause) {
    public val diagnostics: List<String> = Collections.unmodifiableList(diagnostics.toList())

    public class Kind private constructor(public val value: String) {
        public companion object {
            @JvmField public val COMPILATION: Kind = Kind("COMPILATION")
            @JvmField public val RUNTIME: Kind = Kind("RUNTIME")
            @JvmField public val TIMEOUT: Kind = Kind("TIMEOUT")
            @JvmField public val CANCELLED: Kind = Kind("CANCELLED")
            @JvmField public val INVALID_REQUEST: Kind = Kind("INVALID_REQUEST")
        }

        override fun equals(other: Any?): Boolean =
            this === other || other is Kind && value == other.value

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = "Kind(value=$value)"
    }

    public companion object {
        @JvmStatic
        public fun compilation(
            message: String,
            diagnostics: List<String>,
        ): IndexinoScriptException =
            IndexinoScriptException(Kind.COMPILATION, message, diagnostics, cause = null)

        @JvmStatic
        public fun runtime(message: String, cause: Throwable?): IndexinoScriptException =
            IndexinoScriptException(
                Kind.RUNTIME,
                message,
                diagnostics = listOf(message),
                cause = cause,
            )

        @JvmStatic
        public fun timeout(message: String): IndexinoScriptException =
            IndexinoScriptException(
                Kind.TIMEOUT,
                message,
                diagnostics = listOf(message),
                cause = null,
            )

        @JvmStatic
        public fun cancelled(message: String): IndexinoScriptException =
            IndexinoScriptException(
                Kind.CANCELLED,
                message,
                diagnostics = listOf(message),
                cause = null,
            )

        @JvmStatic
        public fun invalidRequest(message: String): IndexinoScriptException =
            IndexinoScriptException(
                Kind.INVALID_REQUEST,
                message,
                diagnostics = listOf(message),
                cause = null,
            )
    }
}
