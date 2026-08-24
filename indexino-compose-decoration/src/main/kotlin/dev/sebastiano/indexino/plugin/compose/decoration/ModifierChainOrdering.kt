package dev.sebastiano.indexino.plugin.compose.decoration

import dev.sebastiano.indexino.model.CallArgument
import dev.sebastiano.indexino.model.CallSite
import dev.sebastiano.indexino.model.CallSiteId
import dev.sebastiano.indexino.model.SourceRange

internal object ModifierChainOrdering {
    fun collectModifierCalls(
        modifierArgument: CallArgument,
        callsById: Map<CallSiteId, CallSite>,
    ): List<CallSite> {
        val seedCalls = modifierArgument.nestedCallIds.mapNotNull { callsById[it] }
        val rangedCalls =
            callsById.values.filter { call ->
                rangeWithinArgument(call.range, modifierArgument.range)
            }
        return expandReceiverLinkedCalls(
            seedCalls = (seedCalls + rangedCalls).distinctBy { it.id },
            modifierRange = modifierArgument.range,
            callsById = callsById,
        )
    }

    fun orderModifierCalls(
        calls: List<CallSite>,
        callSet: Map<CallSiteId, CallSite>,
    ): List<CallSite> {
        val roots = calls.filter { call ->
            call.parentCallId == null || call.parentCallId !in callSet
        }
        val outermost =
            when {
                roots.size == 1 -> roots.single()
                else ->
                    findOutermostModifierCall(roots.ifEmpty { calls })
                        ?: return calls.sortedBy { it.range.start.offset ?: 0 }
            }
        return collectInnerFirst(outermost, calls, callSet)
    }

    private fun expandReceiverLinkedCalls(
        seedCalls: List<CallSite>,
        modifierRange: SourceRange,
        callsById: Map<CallSiteId, CallSite>,
    ): List<CallSite> {
        val result = linkedSetOf<CallSiteId>()
        val queue = ArrayDeque(seedCalls)
        while (queue.isNotEmpty()) {
            val call = queue.removeFirst()
            if (result.add(call.id)) {
                val receiver = call.receiver
                if (receiver != null) {
                    callsById.values
                        .filter { candidate ->
                            candidate.id !in result &&
                                receiver.contains(candidate.calleeName) &&
                                rangeWithinArgument(candidate.range, modifierRange)
                        }
                        .forEach(queue::addLast)
                }
            }
        }
        return result.mapNotNull { callsById[it] }
    }

    private fun rangeWithinArgument(callRange: SourceRange, argumentRange: SourceRange): Boolean {
        val callStart = callRange.start.offset ?: return false
        val callEnd = callRange.end.offset ?: callStart
        val argumentStart = argumentRange.start.offset ?: return false
        val argumentEnd = argumentRange.end.offset ?: argumentStart
        return callStart >= argumentStart && callEnd <= argumentEnd
    }

    private fun findOutermostModifierCall(calls: List<CallSite>): CallSite? {
        if (calls.isEmpty()) return null
        val receiverLinked = calls.filter { candidate ->
            calls.any { other -> isReceiverLinkedCall(candidate, other) }
        }
        return calls
            .filterNot { it in receiverLinked.toSet() }
            .maxByOrNull { it.range.end.offset ?: 0 }
            ?: calls.maxByOrNull { it.range.end.offset ?: 0 }
    }

    private fun isReceiverLinkedCall(candidate: CallSite, outer: CallSite): Boolean {
        val receiver = outer.receiver ?: return false
        if (outer.id == candidate.id || !receiver.contains(candidate.calleeName)) return false
        val candidateEnd = candidate.range.end.offset ?: return false
        val outerStart = outer.range.start.offset ?: return false
        return candidateEnd <= outerStart + receiver.length
    }

    private fun collectInnerFirst(
        call: CallSite,
        calls: List<CallSite>,
        callSet: Map<CallSiteId, CallSite>,
    ): List<CallSite> {
        val children = calls.filter { child ->
            child.parentCallId == call.id || isReceiverLinkedCall(child, call)
        }
        val receiverChild =
            children
                .filter { child -> isReceiverLinkedCall(child, call) }
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
}
