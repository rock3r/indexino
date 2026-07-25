package dev.sebastiano.indexino.detekt

import dev.detekt.api.Config
import dev.detekt.api.Rule
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NoDataClassesInPublicApiRuleTest {
    @Test
    fun `public API data classes are rejected`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    data class PublicModel(val value: String)
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("PublicModel"))
    }

    @Test
    fun `internal and implementation data classes are allowed`() {
        val internalFindings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    internal data class InternalModel(val value: String)
                    """
                        .trimIndent()
                )
        val implementationFindings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.engine

                    data class EngineModel(val value: String)
                    """
                        .trimIndent()
                )

        assertTrue(internalFindings.isEmpty())
        assertTrue(implementationFindings.isEmpty())
    }

    private fun rule(): Rule {
        val type =
            assertNotNull(
                runCatching {
                        Class.forName(
                            "dev.sebastiano.indexino.detekt.rules.NoDataClassesInPublicApiRule"
                        )
                    }
                    .getOrNull(),
                "Expected NoDataClassesInPublicApiRule",
            )
        return type.getConstructor(Config::class.java).newInstance(TestConfig()) as Rule
    }
}
