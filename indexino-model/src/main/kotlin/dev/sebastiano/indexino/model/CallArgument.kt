package dev.sebastiano.indexino.model

import java.util.Collections

public class CallArgument
@IndexinoInternalApi
public constructor(
    public val position: Int,
    public val resolvedName: String?,
    public val kind: ArgumentKind,
    public val range: SourceRange,
    nestedCallIds: List<CallSiteId>,
) {
    public val nestedCallIds: List<CallSiteId> =
        Collections.unmodifiableList(ArrayList(nestedCallIds))

    init {
        require(position >= 0) { "Call argument position must not be negative" }
        require(resolvedName == null || resolvedName.isNotBlank()) {
            "Resolved argument name must not be blank"
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is CallArgument &&
                position == other.position &&
                resolvedName == other.resolvedName &&
                kind == other.kind &&
                range == other.range &&
                nestedCallIds == other.nestedCallIds

    override fun hashCode(): Int {
        var result = position
        result = 31 * result + (resolvedName?.hashCode() ?: 0)
        result = 31 * result + kind.hashCode()
        result = 31 * result + range.hashCode()
        result = 31 * result + nestedCallIds.hashCode()
        return result
    }

    override fun toString(): String =
        "CallArgument(position=$position, resolvedName=$resolvedName, kind=$kind, range=$range, " +
            "nestedCallIds=$nestedCallIds)"
}
