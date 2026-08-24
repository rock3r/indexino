package dev.sebastiano.indexino.plugin.compose.decoration

import dev.sebastiano.indexino.model.CallArgument
import dev.sebastiano.indexino.model.CallSite
import dev.sebastiano.indexino.model.CallSiteId
import dev.sebastiano.indexino.model.ResolutionConfidence

internal object ModifierChainBuilder {
    private val modifierReceiverSuffixes = setOf("Modifier", "M")

    fun buildDecorationSite(
        composableCall: CallSite,
        callsById: Map<CallSiteId, CallSite>,
    ): DecorationSite {
        val modifierArgument = findModifierArgument(composableCall)
        val chain =
            if (modifierArgument == null) {
                ModifierChain.empty()
            } else {
                buildChain(modifierArgument, callsById)
            }
        return DecorationSite.of(
            composableCallId = composableCall.id,
            composableCalleeName = composableCall.calleeName,
            composableRange = composableCall.range,
            composableConfidence = composableCall.confidence,
            hasModifierArgument = modifierArgument != null,
            modifierArgumentRange = modifierArgument?.range,
            chain = chain,
            chainConfidence = aggregateConfidence(chain, composableCall.confidence),
        )
    }

    fun findModifierArgument(call: CallSite): CallArgument? =
        call.arguments.firstOrNull { it.resolvedName == "modifier" }

    private fun buildChain(
        modifierArgument: CallArgument,
        callsById: Map<CallSiteId, CallSite>,
    ): ModifierChain {
        val calls = ModifierChainOrdering.collectModifierCalls(modifierArgument, callsById)
        if (calls.isEmpty()) return ModifierChain.empty()
        val callSet = calls.associateBy { it.id }
        val ordered = ModifierChainOrdering.orderModifierCalls(calls, callSet)
        val links = ordered.flatMap { classifyCall(it, callSet) }
        return ModifierChain.of(links)
    }

    private fun classifyCall(
        call: CallSite,
        callSet: Map<CallSiteId, CallSite>,
    ): List<ModifierLink> {
        if (call.calleeName == "if" || isConditionalBranchRoot(call, callSet)) {
            return listOf(buildConditionalLink(call, callSet))
        }
        val kind =
            when {
                call.calleeName == "then" -> ModifierLinkKind.THEN
                call.calleeName == "composed" -> ModifierLinkKind.COMPOSED
                isModifierReceiver(call.receiver) -> ModifierLinkKind.DIRECT
                call.confidence == ResolutionConfidence.UNRESOLVED -> ModifierLinkKind.UNRESOLVED
                else -> ModifierLinkKind.HELPER
            }
        return listOf(
            ModifierLink.of(
                kind = kind,
                calleeName = call.calleeName,
                receiver = call.receiver,
                argumentNames = call.arguments.mapNotNull { it.resolvedName },
                range = call.range,
                confidence = call.confidence,
            )
        )
    }

    private fun isConditionalBranchRoot(
        call: CallSite,
        callSet: Map<CallSiteId, CallSite>,
    ): Boolean {
        val siblings =
            callSet.values.filter { other ->
                other.id != call.id &&
                    other.parentCallId == call.parentCallId &&
                    !rangesOverlap(other.range, call.range)
            }
        return siblings.isNotEmpty() && isModifierReceiver(call.receiver)
    }

    private fun buildConditionalLink(
        call: CallSite,
        callSet: Map<CallSiteId, CallSite>,
    ): ModifierLink {
        val branches =
            callSet.values
                .filter { it.parentCallId == call.parentCallId && isModifierReceiver(it.receiver) }
                .sortedBy { it.range.start.offset ?: 0 }
                .mapIndexed { index, branchCall ->
                    ConditionalBranch.of(
                        label = if (index == 0) "then" else "else",
                        links =
                            listOf(
                                ModifierLink.of(
                                    kind = ModifierLinkKind.DIRECT,
                                    calleeName = branchCall.calleeName,
                                    receiver = branchCall.receiver,
                                    argumentNames =
                                        branchCall.arguments.mapNotNull { it.resolvedName },
                                    range = branchCall.range,
                                    confidence = branchCall.confidence,
                                )
                            ),
                    )
                }
        return ModifierLink.of(
            kind = ModifierLinkKind.CONDITIONAL,
            calleeName = call.calleeName,
            receiver = call.receiver,
            argumentNames = call.arguments.mapNotNull { it.resolvedName },
            range = call.range,
            confidence = call.confidence,
            branches = branches,
        )
    }

    private fun rangesOverlap(
        first: dev.sebastiano.indexino.model.SourceRange,
        second: dev.sebastiano.indexino.model.SourceRange,
    ): Boolean {
        val firstStart = first.start.offset ?: return false
        val firstEnd = first.end.offset ?: firstStart
        val secondStart = second.start.offset ?: return false
        val secondEnd = second.end.offset ?: secondStart
        return firstStart <= secondEnd && secondStart <= firstEnd
    }

    private fun isModifierReceiver(receiver: String?): Boolean {
        if (receiver == null) return false
        if (receiver == "Modifier") return true
        if (modifierReceiverSuffixes.contains(receiver)) return true
        return receiver.endsWith(".Modifier") || receiver.endsWith(".M")
    }

    private fun aggregateConfidence(
        chain: ModifierChain,
        composableConfidence: ResolutionConfidence,
    ): ResolutionConfidence {
        val confidences = chain.links.map { it.confidence }.toMutableSet()
        confidences += composableConfidence
        return when {
            confidences.contains(ResolutionConfidence.UNRESOLVED) -> ResolutionConfidence.UNRESOLVED
            confidences.contains(ResolutionConfidence.HEURISTIC) -> ResolutionConfidence.HEURISTIC
            else -> ResolutionConfidence.RESOLVED
        }
    }
}
