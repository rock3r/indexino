package dev.sebastiano.indexino.plugin.compose.decoration

import dev.sebastiano.indexino.model.ResolutionConfidence
import dev.sebastiano.indexino.model.SourceRange

public enum class ModifierLinkKind {
    DIRECT,
    THEN,
    HELPER,
    CONDITIONAL,
    COMPOSED,
    UNRESOLVED,
}

public class ModifierLink
private constructor(
    public val kind: ModifierLinkKind,
    public val calleeName: String,
    public val receiver: String?,
    public val argumentNames: List<String>,
    public val range: SourceRange,
    public val confidence: ResolutionConfidence,
    branches: List<ConditionalBranch>,
) {
    public val branches: List<ConditionalBranch> = branches.toList()

    init {
        require(calleeName.isNotBlank()) { "Modifier link callee name must not be blank" }
        require(receiver == null || receiver.isNotBlank()) {
            "Modifier link receiver must not be blank"
        }
    }

    public companion object {
        @JvmStatic
        public fun of(
            kind: ModifierLinkKind,
            calleeName: String,
            receiver: String?,
            argumentNames: List<String>,
            range: SourceRange,
            confidence: ResolutionConfidence,
        ): ModifierLink =
            of(kind, calleeName, receiver, argumentNames, range, confidence, emptyList())

        @JvmStatic
        public fun of(
            kind: ModifierLinkKind,
            calleeName: String,
            receiver: String?,
            argumentNames: List<String>,
            range: SourceRange,
            confidence: ResolutionConfidence,
            branches: List<ConditionalBranch>,
        ): ModifierLink =
            ModifierLink(
                kind = kind,
                calleeName = calleeName,
                receiver = receiver,
                argumentNames = argumentNames.toList(),
                range = range,
                confidence = confidence,
                branches = branches,
            )
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ModifierLink &&
                kind == other.kind &&
                calleeName == other.calleeName &&
                receiver == other.receiver &&
                argumentNames == other.argumentNames &&
                range == other.range &&
                confidence == other.confidence &&
                branches == other.branches

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + calleeName.hashCode()
        result = 31 * result + (receiver?.hashCode() ?: 0)
        result = 31 * result + argumentNames.hashCode()
        result = 31 * result + range.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + branches.hashCode()
        return result
    }

    override fun toString(): String =
        "ModifierLink(kind=$kind, calleeName=$calleeName, receiver=$receiver, " +
            "argumentNames=$argumentNames, range=$range, confidence=$confidence, branches=$branches)"
}

public class ConditionalBranch
private constructor(public val label: String, public val links: List<ModifierLink>) {
    public companion object {
        @JvmStatic
        public fun of(label: String, links: List<ModifierLink>): ConditionalBranch {
            require(label.isNotBlank()) { "Conditional branch label must not be blank" }
            return ConditionalBranch(label, links.toList())
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is ConditionalBranch && label == other.label && links == other.links

    override fun hashCode(): Int = 31 * label.hashCode() + links.hashCode()

    override fun toString(): String = "ConditionalBranch(label=$label, links=$links)"
}

public class ModifierChain private constructor(public val links: List<ModifierLink>) {
    public companion object {
        @JvmStatic
        public fun of(links: List<ModifierLink>): ModifierChain = ModifierChain(links.toList())

        @JvmStatic public fun empty(): ModifierChain = ModifierChain(emptyList())
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is ModifierChain && links == other.links

    override fun hashCode(): Int = links.hashCode()

    override fun toString(): String = "ModifierChain(links=$links)"
}

public class DecorationSite
private constructor(
    public val composableCallId: dev.sebastiano.indexino.model.CallSiteId,
    public val composableCalleeName: String,
    public val composableRange: SourceRange,
    public val composableConfidence: ResolutionConfidence,
    public val hasModifierArgument: Boolean,
    public val modifierArgumentRange: SourceRange?,
    public val chain: ModifierChain,
    public val chainConfidence: ResolutionConfidence,
) {
    public companion object {
        @JvmStatic
        public fun of(
            composableCallId: dev.sebastiano.indexino.model.CallSiteId,
            composableCalleeName: String,
            composableRange: SourceRange,
            composableConfidence: ResolutionConfidence,
            hasModifierArgument: Boolean,
            modifierArgumentRange: SourceRange?,
            chain: ModifierChain,
            chainConfidence: ResolutionConfidence,
        ): DecorationSite =
            DecorationSite(
                composableCallId = composableCallId,
                composableCalleeName = composableCalleeName,
                composableRange = composableRange,
                composableConfidence = composableConfidence,
                hasModifierArgument = hasModifierArgument,
                modifierArgumentRange = modifierArgumentRange,
                chain = chain,
                chainConfidence = chainConfidence,
            )
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is DecorationSite &&
                composableCallId == other.composableCallId &&
                composableCalleeName == other.composableCalleeName &&
                composableRange == other.composableRange &&
                composableConfidence == other.composableConfidence &&
                hasModifierArgument == other.hasModifierArgument &&
                modifierArgumentRange == other.modifierArgumentRange &&
                chain == other.chain &&
                chainConfidence == other.chainConfidence

    override fun hashCode(): Int {
        var result = composableCallId.hashCode()
        result = 31 * result + composableCalleeName.hashCode()
        result = 31 * result + composableRange.hashCode()
        result = 31 * result + composableConfidence.hashCode()
        result = 31 * result + hasModifierArgument.hashCode()
        result = 31 * result + (modifierArgumentRange?.hashCode() ?: 0)
        result = 31 * result + chain.hashCode()
        result = 31 * result + chainConfidence.hashCode()
        return result
    }

    override fun toString(): String =
        "DecorationSite(composableCallId=$composableCallId, composableCalleeName=$composableCalleeName, " +
            "composableRange=$composableRange, composableConfidence=$composableConfidence, " +
            "hasModifierArgument=$hasModifierArgument, modifierArgumentRange=$modifierArgumentRange, " +
            "chain=$chain, chainConfidence=$chainConfidence)"
}
