package dev.sebastiano.indexino.script

import dev.sebastiano.indexino.api.FreshnessPolicy
import dev.sebastiano.indexino.model.ExperimentalIndexinoApi
import java.nio.file.Path

/** Immutable input for one trusted `.indexino.kts` evaluation. */
@ExperimentalIndexinoApi
public class ScriptRequest
private constructor(
    public val workspace: Path,
    public val script: Path,
    public val freshness: FreshnessPolicy,
) {
    public companion object {
        @JvmStatic
        public fun forFile(workspace: Path, script: Path): ScriptRequest =
            ScriptRequest(workspace, script, FreshnessPolicy.PUBLISHED)
    }

    public fun withFreshness(freshness: FreshnessPolicy): ScriptRequest =
        ScriptRequest(workspace, script, freshness)

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ScriptRequest &&
                workspace == other.workspace &&
                script == other.script &&
                freshness == other.freshness

    override fun hashCode(): Int {
        var result = workspace.hashCode()
        result = 31 * result + script.hashCode()
        result = 31 * result + freshness.hashCode()
        return result
    }

    override fun toString(): String =
        "ScriptRequest(workspace=$workspace, script=$script, freshness=$freshness)"
}
