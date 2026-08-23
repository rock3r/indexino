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
    fun `unqualified receiver lambda property does not count as class receiver`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class UnqualifiedNestedReceiver(val value: String) {
                        override fun equals(other: Any?): Boolean =
                            other is UnqualifiedNestedReceiver &&
                                with(other) { value == other.value }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String =
                            "UnqualifiedNestedReceiver(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("value"))
    }

    @Test
    fun `safe qualified receiver lambda property does not count as class receiver`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class SafeReceiver(val value: String?) {
                        override fun equals(other: Any?): Boolean =
                            other is SafeReceiver &&
                                (other.value?.run { value == other.value } ?: false)

                        override fun hashCode(): Int = value?.hashCode() ?: 0

                        override fun toString(): String = "SafeReceiver(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("value"))
    }

    @Test
    fun `custom receiver lambda property does not count as class receiver`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    fun <T> T.check(block: T.() -> Boolean): Boolean = block()

                    class CustomReceiver(val value: String) {
                        override fun equals(other: Any?): Boolean =
                            other is CustomReceiver && other.check { value == other.value }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "CustomReceiver(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("value"))
    }

    @Test
    fun `qualified let preserves the class receiver`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class QualifiedLet(val value: String) {
                        override fun equals(other: Any?): Boolean =
                            other is QualifiedLet && other.let { value == other.value }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "QualifiedLet(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertTrue(findings.isEmpty(), findings.joinToString { it.message })
    }

    @Test
    fun `receiver lambda bound to this preserves the class receiver`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class WithThis(val value: String) {
                        override fun equals(other: Any?): Boolean =
                            other is WithThis && with(this) { value == other.value }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "WithThis(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertTrue(findings.isEmpty(), findings.joinToString { it.message })
    }

    @Test
    fun `lambda label matching class name does not count as class receiver`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class ShadowedLabel(val value: String) {
                        override fun equals(other: Any?): Boolean =
                            other is ShadowedLabel &&
                                other.run ShadowedLabel@ {
                                    this@ShadowedLabel.value == other.value
                                }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "ShadowedLabel(value=${'$'}value)"
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
    fun `bare member inside local extension function does not count as class receiver`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class LocalExtension(val length: Int) {
                        override fun equals(other: Any?): Boolean {
                            if (other !is LocalExtension) return false
                            fun String.matches(): Boolean = length == other.length
                            return "x".matches()
                        }

                        override fun hashCode(): Int = length

                        override fun toString(): String = "LocalExtension(length=${'$'}length)"
                    }
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("length"))
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
    fun `this inside package qualified run preserves class receiver`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class PackageRun(val value: String) {
                        override fun equals(other: Any?): Boolean =
                            other is PackageRun && kotlin.run { this.value == other.value }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "PackageRun(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertTrue(findings.isEmpty(), findings.joinToString { it.message })
    }

    @Test
    fun `run imported as with does not introduce a receiver`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    import kotlin.run as with

                    class AliasedRun(val value: String) {
                        override fun equals(other: Any?): Boolean =
                            other is AliasedRun && with { value == other.value }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "AliasedRun(value=${'$'}value)"
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
    fun `bare property inside nested object does not count as class receiver`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class BareNestedObject(val value: String) {
                        override fun equals(other: Any?): Boolean =
                            other is BareNestedObject &&
                                object {
                                    val value: String = other.value
                                    fun matches(): Boolean = value == other.value
                                }.matches()

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "BareNestedObject(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("value"))
    }

    @Test
    fun `bare property in same named local class does not count as outer receiver`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class SameName(val value: String) {
                        override fun equals(other: Any?): Boolean {
                            if (other !is SameName) return false
                            class SameName(val value: String) {
                                fun matches(): Boolean = value == other.value
                            }
                            return SameName(other.value).matches()
                        }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "SameName(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertTrue(
            findings.any {
                it.message == "Function equals must compare property value through other.value."
            },
            findings.joinToString { it.message },
        )
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
    fun `loop variable does not shadow property inside range expression`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class LoopRange(val value: String) {
                        override fun equals(other: Any?): Boolean {
                            if (other !is LoopRange) return false
                            for (value in listOf(value == other.value)) return value
                            return false
                        }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "LoopRange(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertTrue(findings.isEmpty(), findings.joinToString { it.message })
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
    fun `non nullable equals overload override does not satisfy Any equals`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    interface StringEquality {
                        fun equals(other: String): Boolean
                    }

                    class InterfaceOverload(val length: Int) : StringEquality {
                        override fun equals(other: String): Boolean = length == other.length

                        override fun hashCode(): Int = length

                        override fun toString(): String = "InterfaceOverload(length=${'$'}length)"
                    }
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("equals"))
    }

    @Test
    fun `nullable equals overload override does not satisfy Any equals`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    interface NullableStringEquality {
                        fun equals(other: String?): Boolean
                    }

                    class NullableInterfaceOverload(val length: Int) : NullableStringEquality {
                        override fun equals(other: String?): Boolean = length == other?.length

                        override fun hashCode(): Int = length

                        override fun toString(): String =
                            "NullableInterfaceOverload(length=${'$'}length)"
                    }
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("equals"))
    }

    @Test
    fun `shadowed Any overload does not satisfy kotlin Any equals`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    typealias Any = String

                    interface ShadowEquality {
                        fun equals(other: Any?): Boolean
                    }

                    class ShadowedAny(val length: Int) : ShadowEquality {
                        override fun equals(other: Any?): Boolean = length == other?.length

                        override fun hashCode(): Int = length

                        override fun toString(): String = "ShadowedAny(length=${'$'}length)"
                    }
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("equals"))
    }

    @Test
    fun `same file Any class overload does not satisfy kotlin Any equals`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class Any(val length: Int)

                    interface ClassEquality {
                        fun equals(other: Any?): Boolean
                    }

                    class ClassNamedAny(val length: Int) : ClassEquality {
                        override fun equals(other: Any?): Boolean = length == other?.length

                        override fun hashCode(): Int = length

                        override fun toString(): String = "ClassNamedAny(length=${'$'}length)"
                    }
                    """
                        .trimIndent()
                )

        assertTrue(
            findings.any {
                it.message.contains("ClassNamedAny is missing required functions: equals")
            },
            findings.joinToString { it.message },
        )
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
    fun `method based structural comparisons preserve valid equality`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    import java.util.*

                    class MethodComparisons(
                        val value: String,
                        val values: IntArray,
                        val deepValues: Array<IntArray>,
                        val label: String,
                    ) {
                        override fun equals(other: Any?): Boolean =
                            other is MethodComparisons &&
                                value.equals(other.value) &&
                                values.contentEquals(other.values) &&
                                deepValues.contentDeepEquals(other.deepValues) &&
                                Objects.equals(label, other.label)

                        override fun hashCode(): Int =
                            31 *
                                (31 *
                                    (31 * value.hashCode() + values.contentHashCode()) +
                                    deepValues.contentDeepHashCode()) +
                                label.hashCode()

                        override fun toString(): String =
                            "MethodComparisons(value=${'$'}value, values=${'$'}values, " +
                                "deepValues=${'$'}deepValues, label=${'$'}label)"
                    }
                    """
                        .trimIndent()
                )

        assertTrue(findings.isEmpty(), findings.joinToString { it.message })
    }

    @Test
    fun `safe call equals preserves valid nullable property comparison`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class NullableValue(val value: String?) {
                        override fun equals(other: Any?): Boolean =
                            other is NullableValue &&
                                (value?.equals(other.value) ?: (other.value == null))

                        override fun hashCode(): Int = value?.hashCode() ?: 0

                        override fun toString(): String = "NullableValue(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertTrue(findings.isEmpty(), findings.joinToString { it.message })
    }

    @Test
    fun `lookalike Objects helper does not count as structural equality`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    object Objects {
                        fun equals(left: String, right: String): Boolean = false
                    }

                    class LookalikeObjects(val value: String) {
                        override fun equals(other: Any?): Boolean =
                            other is LookalikeObjects && Objects.equals(value, other.value)

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "LookalikeObjects(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertTrue(
            findings.any {
                it.message == "Function equals must compare property value through other.value."
            },
            findings.joinToString { it.message },
        )
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
    fun `parenthesized other receiver preserves valid structural comparison`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class ParenthesizedOther(val value: String) {
                        override fun equals(other: Any?): Boolean =
                            other is ParenthesizedOther && value == (other).value

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String =
                            "ParenthesizedOther(value=${'$'}value)"
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
    fun `annotated Any parameter satisfies equals override`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    @Target(AnnotationTarget.TYPE)
                    annotation class Marker

                    class AnnotatedAny(val value: String) {
                        override fun equals(other: @Marker Any?): Boolean =
                            other is AnnotatedAny && value == other.value

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "AnnotatedAny(value=${'$'}value)"
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
    fun `same named kotlin Any typealias satisfies equals override`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    private typealias Any = kotlin.Any

                    class SameNamedAnyAlias(val value: String) {
                        override fun equals(other: Any?): Boolean =
                            other is SameNamedAnyAlias && value == other.value

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String =
                            "SameNamedAnyAlias(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertTrue(findings.isEmpty(), findings.joinToString { it.message })
    }

    @Test
    fun `chained Any typealias satisfies equals override`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    private typealias RootAny = Any
                    private typealias ChainedAny = RootAny

                    class ChainedTypealias(val value: String) {
                        override fun equals(other: ChainedAny?): Boolean =
                            other is ChainedTypealias && value == other.value

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "ChainedTypealias(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertTrue(findings.isEmpty(), findings.joinToString { it.message })
    }

    @Test
    fun `nullable Any typealias satisfies equals override`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    private typealias NullableAny = Any?

                    class NullableTypealias(val value: String) {
                        override fun equals(other: NullableAny): Boolean =
                            other is NullableTypealias && value == other.value

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String =
                            "NullableTypealias(value=${'$'}value)"
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
