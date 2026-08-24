package dev.sebastiano.indexino.model

import java.util.Collections

public class LinkedSourceResult
private constructor(
    public val component: ResolvedComponentIdentity,
    public val sourceRevision: SourceOriginRevision,
    public val evidence: SourceLinkEvidence,
    diagnostics: List<SourceLinkDiagnostic>,
    public val location: SourceLocation,
    public val symbolName: String,
) {
    public val diagnostics: List<SourceLinkDiagnostic> =
        Collections.unmodifiableList(ArrayList(diagnostics))

    init {
        require(symbolName.isNotBlank()) { "Linked source symbol name must not be blank" }
    }

    public companion object {
        @JvmStatic
        public fun of(
            component: ResolvedComponentIdentity,
            sourceRevision: SourceOriginRevision,
            evidence: SourceLinkEvidence,
            diagnostics: List<SourceLinkDiagnostic>,
            location: SourceLocation,
            symbolName: String,
        ): LinkedSourceResult =
            LinkedSourceResult(
                component = component,
                sourceRevision = sourceRevision,
                evidence = evidence,
                diagnostics = diagnostics,
                location = location,
                symbolName = symbolName,
            )
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is LinkedSourceResult &&
                component == other.component &&
                sourceRevision == other.sourceRevision &&
                evidence == other.evidence &&
                diagnostics == other.diagnostics &&
                location == other.location &&
                symbolName == other.symbolName

    override fun hashCode(): Int {
        var result = component.hashCode()
        result = 31 * result + sourceRevision.hashCode()
        result = 31 * result + evidence.hashCode()
        result = 31 * result + diagnostics.hashCode()
        result = 31 * result + location.hashCode()
        result = 31 * result + symbolName.hashCode()
        return result
    }

    override fun toString(): String =
        "LinkedSourceResult(component=$component, sourceRevision=$sourceRevision, evidence=$evidence, " +
            "diagnostics=$diagnostics, location=$location, symbolName=$symbolName)"
}
