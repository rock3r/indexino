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

    @Test
    fun `local data classes are allowed at multiple depths`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    fun buildModel() {
                        data class LocalModel(val value: String)

                        run {
                            data class NestedLocalModel(val value: String)
                        }
                    }
                    """
                        .trimIndent()
                )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun `data classes behind private or internal declaration boundaries are allowed`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    private class PrivateOuter {
                        data class PrivateNested(val value: String)

                        class Middle {
                            data class DeepPrivateNested(val value: String)
                        }
                    }

                    internal object InternalOuter {
                        data class InternalNested(val value: String)

                        class Middle {
                            data class DeepInternalNested(val value: String)
                        }
                    }

                    public class PublicOuter {
                        private data class PrivateBoundary(val value: String)

                        internal class InternalBoundary {
                            data class HiddenByInternalBoundary(val value: String)
                        }
                    }
                    """
                        .trimIndent()
                )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun `effectively public data classes are rejected at every depth`() {
        val findings =
            rule()
                .lint(
                    """
                    package dev.sebastiano.indexino.model

                    data class TopLevel(val value: String)

                    public class PublicOuter {
                        data class ImplicitlyPublicNested(val value: String)
                        public data class ExplicitlyPublicNested(val value: String)
                        protected data class ProtectedNested(val value: String)

                        public class PublicMiddle {
                            data class DeepPublicNested(val value: String)
                        }
                    }
                    """
                        .trimIndent()
                )

        assertEquals(5, findings.size)
        val messages = findings.map { it.message }
        assertTrue(messages.any { it.contains("TopLevel") })
        assertTrue(messages.any { it.contains("ImplicitlyPublicNested") })
        assertTrue(messages.any { it.contains("ExplicitlyPublicNested") })
        assertTrue(messages.any { it.contains("ProtectedNested") })
        assertTrue(messages.any { it.contains("DeepPublicNested") })
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
