package dev.sebastiano.indexino.model

import java.util.Collections

public class SourceLinkDiagnostic
private constructor(public val code: String, public val message: String) {
    public companion object {
        @JvmStatic
        public fun of(code: String, message: String): SourceLinkDiagnostic {
            require(code.isNotBlank()) { "Source link diagnostic code must not be blank" }
            require(message.isNotBlank()) { "Source link diagnostic message must not be blank" }
            return SourceLinkDiagnostic(code, message)
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SourceLinkDiagnostic && code == other.code && message == other.message

    override fun hashCode(): Int = 31 * code.hashCode() + message.hashCode()

    override fun toString(): String = "SourceLinkDiagnostic(code=$code, message=$message)"
}

public class SourceLinkRegistration
private constructor(
    public val component: ResolvedComponentIdentity,
    public val checkout: SourceLinkCheckout,
    public val sourceOriginId: SourceOriginId,
    public val linkedGeneration: WorkspaceGenerationId,
    public val mappingRule: SourceLinkMappingRule,
    public val evidence: SourceLinkEvidence,
    diagnostics: List<SourceLinkDiagnostic>,
) {
    public val diagnostics: List<SourceLinkDiagnostic> =
        Collections.unmodifiableList(ArrayList(diagnostics))

    public companion object {
        @JvmStatic
        public fun of(
            component: ResolvedComponentIdentity,
            checkout: SourceLinkCheckout,
            sourceOriginId: SourceOriginId,
            linkedGeneration: WorkspaceGenerationId,
            mappingRule: SourceLinkMappingRule,
            evidence: SourceLinkEvidence,
            diagnostics: List<SourceLinkDiagnostic>,
        ): SourceLinkRegistration =
            SourceLinkRegistration(
                component = component,
                checkout = checkout,
                sourceOriginId = sourceOriginId,
                linkedGeneration = linkedGeneration,
                mappingRule = mappingRule,
                evidence = evidence,
                diagnostics = diagnostics,
            )
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SourceLinkRegistration &&
                component == other.component &&
                checkout == other.checkout &&
                sourceOriginId == other.sourceOriginId &&
                linkedGeneration == other.linkedGeneration &&
                mappingRule == other.mappingRule &&
                evidence == other.evidence &&
                diagnostics == other.diagnostics

    override fun hashCode(): Int {
        var result = component.hashCode()
        result = 31 * result + checkout.hashCode()
        result = 31 * result + sourceOriginId.hashCode()
        result = 31 * result + linkedGeneration.hashCode()
        result = 31 * result + mappingRule.hashCode()
        result = 31 * result + evidence.hashCode()
        result = 31 * result + diagnostics.hashCode()
        return result
    }

    override fun toString(): String =
        "SourceLinkRegistration(component=$component, checkout=$checkout, sourceOriginId=$sourceOriginId, " +
            "linkedGeneration=$linkedGeneration, mappingRule=$mappingRule, evidence=$evidence, " +
            "diagnostics=$diagnostics)"
}
