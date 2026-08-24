package dev.sebastiano.indexino.script

import dev.sebastiano.indexino.api.FreshnessPolicy
import dev.sebastiano.indexino.model.ExperimentalIndexinoApi
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/** Immutable input for one trusted `.indexino.kts` evaluation. */
@ExperimentalIndexinoApi
public class ScriptRequest
private constructor(
    public val workspace: Path,
    public val script: Path,
    public val freshness: FreshnessPolicy,
    public val timeout: Duration,
    public val cancellation: AtomicBoolean?,
) {
    public companion object {
        private val DEFAULT_TIMEOUT: Duration = Duration.ofMinutes(2)

        @JvmStatic
        public fun forFile(workspace: Path, script: Path): ScriptRequest =
            ScriptRequest(
                workspace = workspace,
                script = script,
                freshness = FreshnessPolicy.PUBLISHED,
                timeout = DEFAULT_TIMEOUT,
                cancellation = null,
            )
    }

    public fun withFreshness(freshness: FreshnessPolicy): ScriptRequest =
        ScriptRequest(workspace, script, freshness, timeout, cancellation)

    public fun withTimeout(timeout: Duration): ScriptRequest {
        require(!timeout.isNegative && !timeout.isZero) { "Script timeout must be positive" }
        return ScriptRequest(workspace, script, freshness, timeout, cancellation)
    }

    public fun withCancellation(cancellation: AtomicBoolean): ScriptRequest =
        ScriptRequest(workspace, script, freshness, timeout, cancellation)

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ScriptRequest &&
                workspace == other.workspace &&
                script == other.script &&
                freshness == other.freshness &&
                timeout == other.timeout &&
                cancellation == other.cancellation

    override fun hashCode(): Int {
        var result = workspace.hashCode()
        result = 31 * result + script.hashCode()
        result = 31 * result + freshness.hashCode()
        result = 31 * result + timeout.hashCode()
        result = 31 * result + (cancellation?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "ScriptRequest(workspace=$workspace, script=$script, freshness=$freshness, " +
            "timeout=$timeout, cancellation=${cancellation != null})"
}
