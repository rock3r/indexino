package dev.sebastiano.indexino.model

import java.util.Collections

public class CallSite
@IndexinoInternalApi
public constructor(
    public val id: CallSiteId,
    public val calleeName: String,
    candidateSymbolIds: List<SymbolId>,
    public val receiver: String?,
    public val enclosingSymbolId: SymbolId?,
    public val parentCallId: CallSiteId?,
    public val range: SourceRange,
    arguments: List<CallArgument>,
    public val confidence: ResolutionConfidence,
) {
    public val candidateSymbolIds: List<SymbolId> =
        Collections.unmodifiableList(ArrayList(candidateSymbolIds))
    public val arguments: List<CallArgument> = Collections.unmodifiableList(ArrayList(arguments))

    init {
        require(calleeName.isNotBlank()) { "Call callee name must not be blank" }
        require(receiver == null || receiver.isNotBlank()) { "Call receiver must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is CallSite &&
                id == other.id &&
                calleeName == other.calleeName &&
                candidateSymbolIds == other.candidateSymbolIds &&
                receiver == other.receiver &&
                enclosingSymbolId == other.enclosingSymbolId &&
                parentCallId == other.parentCallId &&
                range == other.range &&
                arguments == other.arguments &&
                confidence == other.confidence

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + calleeName.hashCode()
        result = 31 * result + candidateSymbolIds.hashCode()
        result = 31 * result + (receiver?.hashCode() ?: 0)
        result = 31 * result + (enclosingSymbolId?.hashCode() ?: 0)
        result = 31 * result + (parentCallId?.hashCode() ?: 0)
        result = 31 * result + range.hashCode()
        result = 31 * result + arguments.hashCode()
        result = 31 * result + confidence.hashCode()
        return result
    }

    override fun toString(): String =
        "CallSite(id=$id, calleeName=$calleeName, candidateSymbolIds=$candidateSymbolIds, " +
            "receiver=$receiver, enclosingSymbolId=$enclosingSymbolId, parentCallId=$parentCallId, " +
            "range=$range, arguments=$arguments, confidence=$confidence)"
}
