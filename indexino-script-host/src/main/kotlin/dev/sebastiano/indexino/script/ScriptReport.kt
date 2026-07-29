package dev.sebastiano.indexino.script

import dev.sebastiano.indexino.model.ExperimentalIndexinoApi
import java.util.Collections

/** Immutable result of one script evaluation. */
@ExperimentalIndexinoApi
public class ScriptReport
private constructor(findings: List<ScriptFinding>, public val scriptDigest: String) {
    public val findings: List<ScriptFinding> = Collections.unmodifiableList(findings.toList())

    public companion object {
        private val SHA_256_HEX = Regex("[0-9a-f]{64}")

        @JvmStatic
        public fun of(findings: List<ScriptFinding>, scriptDigest: String): ScriptReport {
            require(scriptDigest.matches(SHA_256_HEX)) {
                "Script digest must be a SHA-256 hex value"
            }
            return ScriptReport(findings, scriptDigest)
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ScriptReport &&
                findings == other.findings &&
                scriptDigest == other.scriptDigest

    override fun hashCode(): Int = 31 * findings.hashCode() + scriptDigest.hashCode()

    override fun toString(): String = "ScriptReport(findings=$findings, scriptDigest=$scriptDigest)"
}
