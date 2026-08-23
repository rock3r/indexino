package dev.sebastiano.indexino.detekt

import dev.detekt.api.Config
import dev.detekt.api.Rule
import dev.detekt.test.TestConfig
import dev.detekt.test.lintWithContext
import dev.detekt.test.utils.KotlinEnvironmentContainer
import dev.detekt.test.utils.createEnvironment
import dev.sebastiano.indexino.detekt.rules.EqualityMembersRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EqualityMembersRuleTest {
    private val environment: KotlinEnvironmentContainer = createEnvironment()

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

        assertTrue(
            findings.any {
                it.message == "Function equals must compare property value through other.value."
            },
            findings.joinToString { it.message },
        )
    }

    @Test
    fun `extension property on peer does not count as class property`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    val Any?.value: String get() = ""

                    class ExtensionPeer(val value: String) {
                        override fun equals(other: Any?): Boolean = value == other.value

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "ExtensionPeer(value=${'$'}value)"
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
    fun `catch parameters do not count as receiver properties`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class CatchShadow(val value: Throwable) {
                        override fun equals(other: Any?): Boolean {
                            if (other !is CatchShadow) return false
                            return try {
                                false
                            } catch (value: Throwable) {
                                value == other.value
                            }
                        }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "CatchShadow(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("value"))
    }

    @Test
    fun `destructured local names do not count as receiver properties`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class DestructuredShadow(val value: String) {
                        override fun equals(other: Any?): Boolean {
                            if (other !is DestructuredShadow) return false
                            val (value) = listOf(other.value)
                            return value == other.value
                        }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "DestructuredShadow(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("value"))
    }

    @Test
    fun `when subject local does not count as receiver property`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class WhenSubjectShadow(val value: String) {
                        override fun equals(other: Any?): Boolean {
                            if (other !is WhenSubjectShadow) return false
                            return when (val value = other.value) {
                                else -> value == other.value
                            }
                        }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "WhenSubjectShadow(value=${'$'}value)"
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
    fun `destructured lambda parameter does not count as receiver property`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class LambdaDestructuring(val value: String) {
                        override fun equals(other: Any?): Boolean =
                            other is LambdaDestructuring &&
                                Pair(other.value, 0).let { (value, _) ->
                                    value == other.value
                                }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String =
                            "LambdaDestructuring(value=${'$'}value)"
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
    fun `package qualified with uses its argument as lambda receiver`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class QualifiedWith(val value: String) {
                        override fun equals(other: Any?): Boolean =
                            other is QualifiedWith &&
                                kotlin.with(other) { value == other.value }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "QualifiedWith(value=${'$'}value)"
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
    fun `custom plain lambda preserves the class receiver`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    fun <T> T.check(block: () -> Boolean): Boolean = block()

                    class CustomPlainLambda(val value: String) {
                        override fun equals(other: Any?): Boolean =
                            other is CustomPlainLambda && other.check { value == other.value }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "CustomPlainLambda(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertTrue(findings.isEmpty(), findings.joinToString { it.message })
    }

    @Test
    fun `imported plain lambda helper preserves the class receiver`() {
        val findings =
            rule()
                .lintWithDependencies(
                    """
                    package dev.sebastiano.indexino.model

                    import dev.sebastiano.indexino.helpers.check

                    class ImportedPlainLambda(val value: String) {
                        override fun equals(other: Any?): Boolean =
                            other is ImportedPlainLambda &&
                                other.check { value == other.value }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String =
                            "ImportedPlainLambda(value=${'$'}value)"
                    }
                    """
                        .trimIndent(),
                    """
                    package dev.sebastiano.indexino.helpers

                    fun <T> T.check(block: () -> Boolean): Boolean = block()
                    """
                        .trimIndent(),
                )

        assertTrue(findings.isEmpty(), findings.joinToString { it.message })
    }

    @Test
    fun `implicit lambda parameter shadows a property named it`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class ImplicitItShadow(val it: String) {
                        override fun equals(other: Any?): Boolean =
                            other is ImplicitItShadow && other.it.let { it == other.it }

                        override fun hashCode(): Int = it.hashCode()

                        override fun toString(): String = "ImplicitItShadow(it=${'$'}it)"
                    }
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("it"))
    }

    @Test
    fun `property named it remains visible in a zero parameter lambda`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class ZeroParameterIt(val it: String) {
                        override fun equals(other: Any?): Boolean =
                            other is ZeroParameterIt && run { it == other.it }

                        override fun hashCode(): Int = it.hashCode()

                        override fun toString(): String = "ZeroParameterIt(it=${'$'}it)"
                    }
                    """
                        .trimIndent()
                )

        assertTrue(findings.isEmpty(), findings.joinToString { it.message })
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
    fun `custom plain run preserves the class receiver`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class CustomRun(val value: String) {
                        fun run(block: () -> Boolean): Boolean = block()

                        override fun equals(other: Any?): Boolean =
                            other is CustomRun && other.run { value == other.value }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "CustomRun(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertTrue(findings.isEmpty(), findings.joinToString { it.message })
    }

    @Test
    fun `custom let receiver lambda does not count as class receiver`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class CustomLet(val value: String) {
                        fun let(block: CustomLet.() -> Boolean): Boolean = block()

                        override fun equals(other: Any?): Boolean =
                            other is CustomLet && other.let { value == other.value }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "CustomLet(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("value"))
    }

    @Test
    fun `stored lambda preserves the class receiver`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class StoredLambda(val value: String) {
                        override fun equals(other: Any?): Boolean {
                            if (other !is StoredLambda) return false
                            val matches = { value == other.value }
                            return matches()
                        }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "StoredLambda(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertTrue(findings.isEmpty(), findings.joinToString { it.message })
    }

    @Test
    fun `stored extension lambda uses its extension receiver`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class StoredExtensionLambda(val value: String) {
                        override fun equals(other: Any?): Boolean {
                            if (other !is StoredExtensionLambda) return false
                            val matches: StoredExtensionLambda.() -> Boolean = {
                                value == other.value
                            }
                            return matches(other)
                        }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String =
                            "StoredExtensionLambda(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("value"))
    }

    @Test
    fun `local does not shadow the class property in its own initializer`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class LocalInitializer(val value: String) {
                        override fun equals(other: Any?): Boolean {
                            if (other !is LocalInitializer) return false
                            val value = value == other.value
                            return value
                        }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "LocalInitializer(value=${'$'}value)"
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
    fun `object expression without a property preserves the outer class property`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class ObjectWrapper(val value: String) {
                        override fun equals(other: Any?): Boolean =
                            other is ObjectWrapper &&
                                object {
                                    fun matches(): Boolean = value == other.value
                                }.matches()

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "ObjectWrapper(value=${'$'}value)"
                    }
                    """
                        .trimIndent()
                )

        assertTrue(findings.isEmpty(), findings.joinToString { it.message })
    }

    @Test
    fun `same named local class this label does not count as outer receiver`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class LabeledSameName(val value: String) {
                        override fun equals(other: Any?): Boolean {
                            if (other !is LabeledSameName) return false
                            class LabeledSameName(val value: String) {
                                fun matches(): Boolean =
                                    this@LabeledSameName.value == other.value
                            }
                            return LabeledSameName(other.value).matches()
                        }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "LabeledSameName(value=${'$'}value)"
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
    fun `nested receiver member does not count as equals peer`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class NestedPeerShadow(val value: String) {
                        override fun equals(other: Any?): Boolean {
                            if (other !is NestedPeerShadow) return false
                            return object {
                                val other = this@NestedPeerShadow

                                fun matches(): Boolean =
                                    this@NestedPeerShadow.value == other.value
                            }.matches()
                        }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "NestedPeerShadow(value=${'$'}value)"
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
    fun `destructured loop variable does not count as receiver property`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class DestructuredLoop(val value: String) {
                        override fun equals(other: Any?): Boolean {
                            if (other !is DestructuredLoop) return false
                            for ((value, _) in listOf(other.value to 0)) {
                                return value == other.value
                            }
                            return false
                        }

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "DestructuredLoop(value=${'$'}value)"
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
    fun `member extension overrides do not satisfy equality members`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    interface ExtensionEquality {
                        fun String.equals(other: Any?): Boolean
                        fun String.hashCode(): Int
                        fun String.toString(): String
                    }

                    class MemberExtensions(val value: String) : ExtensionEquality {
                        override fun String.equals(other: Any?): Boolean =
                            other is MemberExtensions &&
                                this@MemberExtensions.value == other.value

                        override fun String.hashCode(): Int = this@MemberExtensions.value.hashCode()

                        override fun String.toString(): String =
                            "MemberExtensions(value=${'$'}{this@MemberExtensions.value})"
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
    fun `same package Any class overload from another file does not satisfy kotlin Any equals`() {
        val findings =
            rule()
                .lintWithDependencies(
                    """
                    package dev.sebastiano.indexino.model

                    interface ForeignAnyEquality {
                        fun equals(other: Any?): Boolean
                    }

                    class ForeignAnyOverload(val value: String) : ForeignAnyEquality {
                        override fun equals(other: Any?): Boolean = value == other?.value

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "ForeignAnyOverload(value=${'$'}value)"
                    }
                    """
                        .trimIndent(),
                    """
                    package dev.sebastiano.indexino.model

                    class Any(val value: String)
                    """
                        .trimIndent(),
                )

        assertTrue(
            findings.any {
                it.message.contains("ForeignAnyOverload is missing required functions: equals")
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
    fun `aliased standard comparison preserves valid equality`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    import kotlin.collections.contentEquals as arraysEqual

                    class AliasedComparison(val values: IntArray) {
                        override fun equals(other: Any?): Boolean =
                            other is AliasedComparison && values.arraysEqual(other.values)

                        override fun hashCode(): Int = values.contentHashCode()

                        override fun toString(): String = "AliasedComparison(values=${'$'}values)"
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
    fun `unrelated equals overload does not count as structural equality`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class AlwaysEqual {
                        fun equals(other: AlwaysEqual): Boolean = true
                    }

                    class CustomEquals(val value: AlwaysEqual) {
                        override fun equals(other: Any?): Boolean =
                            other is CustomEquals && value.equals(other.value)

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "CustomEquals(value=${'$'}value)"
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
    fun `class member shadowing imported Objects does not count as structural equality`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    import java.util.Objects

                    class MemberObjects(val value: String) {
                        private val Objects = Helper

                        override fun equals(other: Any?): Boolean =
                            other is MemberObjects && Objects.equals(value, other.value)

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "MemberObjects(value=${'$'}value)"

                        private object Helper {
                            fun equals(first: Any?, second: Any?): Boolean = true
                        }
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
    fun `value chain named java util Objects does not count as JDK Objects`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class QualifiedObjectsShadow(val value: String) {
                        private val java = JavaNamespace

                        override fun equals(other: Any?): Boolean =
                            other is QualifiedObjectsShadow &&
                                java.util.Objects.equals(value, other.value)

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String =
                            "QualifiedObjectsShadow(value=${'$'}value)"

                        private object JavaNamespace {
                            val util = UtilNamespace
                        }

                        private object UtilNamespace {
                            val Objects = Helper
                        }

                        private object Helper {
                            fun equals(first: Any?, second: Any?): Boolean = true
                        }
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
    fun `parenthesized this receiver preserves valid structural comparison`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    class ParenthesizedThis(val value: String) {
                        override fun equals(other: Any?): Boolean =
                            other is ParenthesizedThis && (this).value == other.value

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String = "ParenthesizedThis(value=${'$'}value)"
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
    fun `imported nullable Any typealias satisfies equals override`() {
        val findings =
            rule()
                .lintWithDependencies(
                    """
                    package dev.sebastiano.indexino.model

                    import dev.sebastiano.indexino.aliases.NullableAny

                    class ImportedTypealias(val value: String) {
                        override fun equals(other: NullableAny): Boolean =
                            other is ImportedTypealias && value == other.value

                        override fun hashCode(): Int = value.hashCode()

                        override fun toString(): String =
                            "ImportedTypealias(value=${'$'}value)"
                    }
                    """
                        .trimIndent(),
                    """
                    package dev.sebastiano.indexino.aliases

                    typealias NullableAny = Any?
                    """
                        .trimIndent(),
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

    private fun Rule.lint(content: String) =
        (this as EqualityMembersRule).lintWithContext(environment, content)

    private fun Rule.lintWithDependencies(content: String, vararg dependencies: String) =
        (this as EqualityMembersRule).lintWithContext(environment, content, *dependencies)
}
