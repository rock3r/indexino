package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.RefreshId
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import dev.sebastiano.indexino.model.WorkspaceRevision

public class RefreshResult
@IndexinoInternalApi
public constructor(
    public val refreshId: RefreshId,
    public val outcome: RefreshOutcome,
    public val generation: WorkspaceGenerationId,
    public val revision: WorkspaceRevision,
    public val scope: IndexScope,
    public val changes: IndexChanges,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is RefreshResult &&
                refreshId == other.refreshId &&
                outcome == other.outcome &&
                generation == other.generation &&
                revision == other.revision &&
                scope == other.scope &&
                changes == other.changes

    override fun hashCode(): Int {
        var result = refreshId.hashCode()
        result = 31 * result + outcome.hashCode()
        result = 31 * result + generation.hashCode()
        result = 31 * result + revision.hashCode()
        result = 31 * result + scope.hashCode()
        result = 31 * result + changes.hashCode()
        return result
    }

    override fun toString(): String =
        "RefreshResult(refreshId=$refreshId, outcome=$outcome, generation=$generation, " +
            "revision=$revision, scope=$scope, changes=$changes)"
}
