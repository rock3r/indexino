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
    fun `equals must compare receiver and other for each property`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class WrongOperands(val value: String) {
                        override fun equals(other: Any?): Boolean =
                            other is WrongOperands && other.value == other.value

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "WrongOperands(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("value"))
    }

    @Test
    fun `shadowed local names do not count as receiver properties`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class ShadowedReceiver(val value: String) {
                        override fun equals(other: Any?): Boolean {
                            if (other !is ShadowedReceiver) return false
                            val value = other.value
                            return value == other.value
                        }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "ShadowedReceiver(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("value"))
    }

    @Test
    fun `labeled outer receiver does not count as class receiver`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class Outer(val value: String) {
                        inner class Inner(val value: String) {
                            override fun equals(other: Any?): Boolean =
                                other is Inner && this@Outer.value == other.value

                            override fun hashCode(): Int = value.hashCode()

                            override fun toString(): String = "Inner(value=${'$'}value)"
                        }

                        override fun equals(other: Any?): Boolean =
                            other is Outer && value == other.value

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "Outer(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("value"))
    }

    @Test
    fun `shadowed other parameter does not count as equals peer`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class ShadowedOther(val value: String) {
                        override fun equals(other: Any?): Boolean =
                            other is ShadowedOther &&
                                listOf(other).any { other -> value == other.value }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "ShadowedOther(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("value"))
    }

    @Test
    fun `nested implicit receiver does not count as class receiver`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class NestedReceiver(val value: String) {
                        override fun equals(other: Any?): Boolean =
                            other is NestedReceiver && with(other) { this.value == other.value }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "NestedReceiver(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("value"))
    }

    @Test
    fun `this inside local function preserves class receiver`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class LocalFunction(val value: String) {
                        override fun equals(other: Any?): Boolean {
                            if (other !is LocalFunction) return false
                            fun hasSameValue(): Boolean = this.value == other.value
                            return hasSameValue()
                        }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "LocalFunction(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertTrue(findings.isEmpty(), findings.joinToString { it.message })
    }

    @Test
    fun `this inside non receiver lambda preserves class receiver`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class LexicalLambda(val value: String) {
                        override fun equals(other: Any?): Boolean =
                            other is LexicalLambda && run { this.value == other.value }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "LexicalLambda(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertTrue(findings.isEmpty(), findings.joinToString { it.message })
    }

    @Test
    fun `this inside nested object does not count as class receiver`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class NestedObject(val value: String) {
                        override fun equals(other: Any?): Boolean =
                            other is NestedObject &&
                                object {
                                    val value: String = other.value
                                    fun matches(): Boolean = this.value == other.value
                                }.matches()

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "NestedObject(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("value"))
    }

    @Test
    fun `loop locals do not count as receiver properties`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class LoopShadow(val value: String) {
                        override fun equals(other: Any?): Boolean {
                            if (other !is LoopShadow) return false
                            for (value in listOf(other.value)) {
                                if (value == other.value) return true
                            }
                            return false
                        }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "LoopShadow(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("value"))
    }

    @Test
    fun `name-only overloads do not satisfy required overrides`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class OverloadsOnly(val value: String) {
                        fun equals(other: OverloadsOnly): Boolean = value == other.value

                        fun hashCode(seed: Int): Int = seed + value.hashCode()

                        fun toString(prefix: String): String = prefix + value
                    }
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("equals, hashCode, toString"))
    }

    @Test
    fun `valid required overrides compare every property structurally`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class Complete(val value: String, val count: Int) {
                        override fun equals(other: Any?): Boolean =
                            other is Complete && value == other.value && count == other.count

                        override fun hashCode(): Int = 31 * value.hashCode() + count

                        override fun toString(): String =
                            "Complete(value=${'$'}value, count=${'$'}count)"
                    }
                    """
                        .trimIndent()
                )

        assertTrue(findings.isEmpty(), findings.joinToString { it.message })
    }

    @Test
    fun `parenthesized operands preserve valid structural comparison`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class Parenthesized(val value: String) {
                        override fun equals(other: Any?): Boolean =
                            other is Parenthesized && (value) == (other.value)

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "Parenthesized(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertTrue(findings.isEmpty(), findings.joinToString { it.message })
    }

    @Test
    fun `aliased Any parameter satisfies equals override`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    import kotlin.Any as RootAny

                    class AliasedAny(val value: String) {
                        override fun equals(other: RootAny?): Boolean =
                            other is AliasedAny && value == other.value

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "AliasedAny(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertTrue(findings.isEmpty(), findings.joinToString { it.message })
    }

    @Test
    fun `typealiased Any parameter satisfies equals override`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    private typealias RootAny = Any

                    class TypealiasedAny(val value: String) {
                        override fun equals(other: RootAny?): Boolean =
                            other is TypealiasedAny && value == other.value

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "TypealiasedAny(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertTrue(findings.isEmpty(), findings.joinToString { it.message })
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
