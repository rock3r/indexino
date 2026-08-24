package dev.sebastiano.indexino.plugin.compose.decoration

import dev.sebastiano.indexino.model.CallSiteId
import dev.sebastiano.indexino.model.PluginFactValue
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.QueryPage
import dev.sebastiano.indexino.model.ResolutionConfidence
import dev.sebastiano.indexino.model.SourceRange
import dev.sebastiano.indexino.plugin.api.PluginFactViewV1

@OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)
public class ComposeDecorationQueries private constructor() {
    public companion object {
        public const val FACT_PREFIX: String = "decoration-site:"

        @JvmStatic
        public suspend fun findSites(
            facts: PluginFactViewV1,
            options: QueryOptions,
        ): QueryPage<DecorationSite> {
            val page = facts.entries(FACT_PREFIX, options)
            val sites =
                page.items.mapNotNull { entry ->
                    ComposeDecorationFacts.decode(entry.key.removePrefix(FACT_PREFIX), entry.value)
                }
            return QueryPage(
                items = sites,
                offset = page.offset,
                limit = page.limit,
                hasMore = page.hasMore,
                nextCursor = page.nextCursor,
                totalCount = page.totalCount,
            )
        }

        @JvmStatic
        public suspend fun findSiteByComposableCallId(
            facts: PluginFactViewV1,
            composableCallId: CallSiteId,
        ): DecorationSite? =
            facts.get("$FACT_PREFIX${composableCallId.value}")?.let { value ->
                ComposeDecorationFacts.decode(composableCallId.value, value)
            }

        @JvmStatic
        public suspend fun findSitesForCalls(
            facts: PluginFactViewV1,
            options: QueryOptions,
        ): QueryPage<DecorationSite> = findSites(facts, options)
    }
}

internal object ComposeDecorationFacts {
    fun encode(site: DecorationSite): PluginFactValue =
        PluginFactValue.Struct.of(
            mapOf(
                "composableCallId" to PluginFactValue.Text.of(site.composableCallId.value),
                "composableCalleeName" to PluginFactValue.Text.of(site.composableCalleeName),
                "composableConfidence" to confidenceValue(site.composableConfidence),
                "hasModifierArgument" to PluginFactValue.Bool.of(site.hasModifierArgument),
                "chainConfidence" to confidenceValue(site.chainConfidence),
                "chain" to encodeChain(site.chain),
            ) +
                site.modifierArgumentRange
                    ?.let { range -> mapOf("modifierArgumentRange" to encodeRange(range)) }
                    .orEmpty()
        )

    fun decode(keySuffix: String, value: PluginFactValue): DecorationSite? {
        val fields = (value as? PluginFactValue.Struct)?.fields ?: return null
        val composableCallId =
            (fields["composableCallId"] as? PluginFactValue.Text)?.value?.let(CallSiteId::of)
                ?: CallSiteId.of(keySuffix)
        val composableCalleeName =
            (fields["composableCalleeName"] as? PluginFactValue.Text)?.value ?: return null
        val composableConfidence =
            decodeConfidence(fields["composableConfidence"]) ?: ResolutionConfidence.HEURISTIC
        val hasModifier = (fields["hasModifierArgument"] as? PluginFactValue.Bool)?.value ?: false
        val chainConfidence =
            decodeConfidence(fields["chainConfidence"]) ?: ResolutionConfidence.HEURISTIC
        val chain = decodeChain(fields["chain"]) ?: ModifierChain.empty()
        val modifierRange = decodeRange(fields["modifierArgumentRange"])
        return DecorationSite.of(
            composableCallId = composableCallId,
            composableCalleeName = composableCalleeName,
            composableRange =
                modifierRange ?: chain.links.firstOrNull()?.range ?: placeholderRange(),
            composableConfidence = composableConfidence,
            hasModifierArgument = hasModifier,
            modifierArgumentRange = modifierRange,
            chain = chain,
            chainConfidence = chainConfidence,
        )
    }

    private fun encodeChain(chain: ModifierChain): PluginFactValue =
        PluginFactValue.Struct.of(
            mapOf(
                "links" to
                    PluginFactValue.TextList.of(
                        chain.links.map { link ->
                            listOf(
                                    link.kind.name,
                                    link.calleeName,
                                    link.receiver.orEmpty(),
                                    link.confidence.value,
                                    link.argumentNames.joinToString(","),
                                )
                                .joinToString("|")
                        }
                    )
            )
        )

    private fun decodeChain(value: PluginFactValue?): ModifierChain? {
        val names =
            (value as? PluginFactValue.Struct)?.fields?.get("links") as? PluginFactValue.TextList
                ?: return ModifierChain.empty()
        val links =
            names.values.mapNotNull { encoded ->
                val parts = encoded.split("|")
                if (parts.size < 4) return@mapNotNull null
                val kind =
                    runCatching { ModifierLinkKind.valueOf(parts[0]) }.getOrNull()
                        ?: return@mapNotNull null
                val confidence = confidenceFromString(parts[3]) ?: ResolutionConfidence.HEURISTIC
                val argumentNames =
                    parts.getOrNull(4)?.split(",")?.filter { it.isNotEmpty() }.orEmpty()
                ModifierLink.of(
                    kind = kind,
                    calleeName = parts[1],
                    receiver = parts[2].ifBlank { null },
                    argumentNames = argumentNames,
                    range = placeholderRange(),
                    confidence = confidence,
                )
            }
        return ModifierChain.of(links)
    }

    private fun encodeRange(range: SourceRange): PluginFactValue =
        PluginFactValue.Struct.of(
            mapOf(
                "startOffset" to PluginFactValue.Integer.of(range.start.offset?.toLong() ?: 0L),
                "endOffset" to PluginFactValue.Integer.of(range.end.offset?.toLong() ?: 0L),
            )
        )

    private fun decodeRange(value: PluginFactValue?): SourceRange? {
        val fields = (value as? PluginFactValue.Struct)?.fields ?: return null
        val start = (fields["startOffset"] as? PluginFactValue.Integer)?.value ?: return null
        val end = (fields["endOffset"] as? PluginFactValue.Integer)?.value ?: return null
        val file = placeholderRange().start.file
        return SourceRange.of(
            dev.sebastiano.indexino.model.SourceLocation.of(file, 1, 1, start.toInt()),
            dev.sebastiano.indexino.model.SourceLocation.of(file, 1, 1, end.toInt()),
        )
    }

    private fun confidenceValue(confidence: ResolutionConfidence): PluginFactValue =
        PluginFactValue.Text.of(confidence.value)

    private fun decodeConfidence(value: PluginFactValue?): ResolutionConfidence? =
        (value as? PluginFactValue.Text)?.value?.let(::confidenceFromString)

    private fun confidenceFromString(text: String): ResolutionConfidence? =
        when (text) {
            ResolutionConfidence.RESOLVED.value -> ResolutionConfidence.RESOLVED
            ResolutionConfidence.HEURISTIC.value -> ResolutionConfidence.HEURISTIC
            ResolutionConfidence.UNRESOLVED.value -> ResolutionConfidence.UNRESOLVED
            else -> null
        }

    private fun placeholderRange(): SourceRange {
        val file =
            dev.sebastiano.indexino.model.SourceFile.of(
                dev.sebastiano.indexino.model.SourceOriginId.of("workspace"),
                "__postprocess__",
                "__postprocess__",
            )
        return SourceRange.of(
            dev.sebastiano.indexino.model.SourceLocation.of(file, 1, 1, 0),
            dev.sebastiano.indexino.model.SourceLocation.of(file, 1, 1, 0),
        )
    }
}
