package dev.sebastiano.indexino.detekt

import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import dev.sebastiano.indexino.detekt.rules.EqualityMembersRule
import dev.sebastiano.indexino.detekt.rules.NoDataClassesInPublicApiRule

public class IndexinoRuleSetProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("indexino")

    override fun instance(): RuleSet =
        RuleSet(
            id = ruleSetId,
            rules =
                mapOf(
                    RuleName("EqualityMembers") to ::EqualityMembersRule,
                    RuleName("NoDataClassesInPublicApi") to ::NoDataClassesInPublicApiRule,
                ),
        )
}
