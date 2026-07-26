package dev.sebastiano.indexino.detekt

import dev.detekt.api.RuleSetProvider
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class IndexinoRuleSetProviderTest {
    @Test
    fun `service loader exposes both public API rules`() {
        val provider =
            assertNotNull(
                ServiceLoader.load(RuleSetProvider::class.java).firstOrNull {
                    it.javaClass.name == "dev.sebastiano.indexino.detekt.IndexinoRuleSetProvider"
                }
            )

        assertEquals("indexino", provider.ruleSetId.value)
        val ruleNames = provider.instance().rules.keys.map { ruleName -> ruleName.value }
        assertEquals(setOf("EqualityMembers", "NoDataClassesInPublicApi"), ruleNames.toSet())
    }
}
