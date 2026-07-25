package dev.sebastiano.indexino.detekt

import dev.detekt.api.Config
import dev.detekt.api.Rule
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EqualityMembersRuleTest {
    @Test
    fun `public model classes require equality members`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class Missing(val value: String)
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("equals, hashCode, toString"))
    }

    @Test
    fun `equals must compare each property through its other parameter`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class Incomplete(val value: String) {
                        override fun equals(candidate: Any?): Boolean =
                            candidate is Incomplete && value == value

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "Incomplete(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("candidate.value"))
    }

    @Test
    fun `body properties participate unless explicitly excluded`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    annotation class ExcludeFromEquality

                    class BodyProperties(val value: String) {
                        val label: String = value

                        @ExcludeFromEquality
                        val cache: String = value

                        override fun equals(other: Any?): Boolean =
                            other is BodyProperties &&
                                value == other.value &&
                                label == other.label

                        override fun hashCode(): Int = 31 * value.hashCode() + label.hashCode()

                        override fun toString(): String =
                            "BodyProperties(value=${'$'}value, label=${'$'}label)"
                    }
                    """
                        .trimIndent()
                )

        assertTrue(findings.isEmpty(), findings.joinToString { it.message })
    }

    @Test
    fun `reference types annotations and non API packages are ignored`() {
        val referenceFindings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.api

                    class Indexino(val state: String)
                    class IndexSnapshot(val state: String)
                    class RefreshHandle(val state: String)
                    class IndexinoException(val failure: String) : RuntimeException()
                    annotation class ExperimentalIndexinoApi(val message: String)
                    """
                        .trimIndent()
                )
        val internalFindings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.engine

                    class InternalEngine(val state: String)
                    """
                        .trimIndent()
                )

        assertTrue(referenceFindings.isEmpty())
        assertTrue(internalFindings.isEmpty())
    }

    private fun rule(): Rule {
        val type =
            assertNotNull(
                runCatching {
                        Class.forName("dev.sebastiano.indexino.detekt.rules.EqualityMembersRule")
                    }
                    .getOrNull(),
                "Expected EqualityMembersRule",
            )
        return type.getConstructor(Config::class.java).newInstance(TestConfig()) as Rule
    }
}
