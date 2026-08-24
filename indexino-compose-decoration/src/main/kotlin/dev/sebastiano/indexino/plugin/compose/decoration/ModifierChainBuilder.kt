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
        val calls = modifierArgument.nestedCallIds.mapNotNull { callsById[it] }
        if (calls.isEmpty()) return ModifierChain.empty()
        val callSet = calls.associateBy { it.id }
        val ordered = orderModifierCalls(calls, callSet)
        val links = ordered.flatMap { classifyCall(it, callSet) }
        return ModifierChain.of(links)
    }

    private fun orderModifierCalls(
        calls: List<CallSite>,
        callSet: Map<CallSiteId, CallSite>,
    ): List<CallSite> {
        val roots = calls.filter { call ->
            call.parentCallId == null || call.parentCallId !in callSet
        }
        val outermost = roots.maxByOrNull { it.range.end.offset ?: it.range.start.offset ?: 0 }
        if (outermost == null) return calls.sortedBy { it.range.start.offset ?: 0 }
        return collectInnerFirst(outermost, calls, callSet)
    }

    private fun collectInnerFirst(
        call: CallSite,
        calls: List<CallSite>,
        callSet: Map<CallSiteId, CallSite>,
    ): List<CallSite> {
        val children = calls.filter { it.parentCallId == call.id }
        val receiverChild =
            children
                .filter { child -> isReceiverChild(call, child) }
                .minByOrNull { it.range.start.offset ?: 0 }
        val result = mutableListOf<CallSite>()
        if (receiverChild != null) {
            result += collectInnerFirst(receiverChild, calls, callSet)
        }
        result += call
        if (call.calleeName == "then") {
            val argumentChild =
                children
                    .filter { it.id != receiverChild?.id }
                    .minByOrNull { it.range.start.offset ?: 0 }
            if (argumentChild != null) {
                result += collectInnerFirst(argumentChild, calls, callSet)
            }
        }
        return result
    }

    private fun isReceiverChild(parent: CallSite, child: CallSite): Boolean {
        val receiver = parent.receiver ?: return false
        return receiver.contains(child.calleeName) &&
            (child.range.end.offset ?: 0) <=
                (parent.range.start.offset ?: Int.MAX_VALUE) + receiver.length
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
