package dev.sebastiano.indexino.core.sourcelink

import dev.sebastiano.indexino.model.ArtifactDigest
import dev.sebastiano.indexino.model.ResolvedComponentCoordinate
import dev.sebastiano.indexino.model.SourceLinkEvidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceLinkConfigParserTest {
    @Test
    fun `parses illustrative source link blocks`() {
        val text =
            """
            [[sourceLink]]
            component = "com.example:lib:1.0.0"
            binarySha256 = "sha256:abc"
            checkout = "../provider"
            linkedWorkspace = "../provider"
            ref = "v1.0.0"
            sourceRoots = ["src/main/kotlin"]
            publishedSourceCompanion = "sha256:abc"
            """
                .trimIndent()

        val entries = SourceLinkConfigParser.parse(text)
        assertEquals(1, entries.size)
        assertEquals(
            ResolvedComponentCoordinate.of("com.example:lib:1.0.0"),
            entries.single().component,
        )
        assertEquals(ArtifactDigest.of("sha256:abc"), entries.single().binarySha256)
        assertEquals("../provider", entries.single().linkedWorkspace)
        assertEquals("v1.0.0", entries.single().ref)
    }

    @Test
    fun `link generation changes when linked generation changes`() {
        val component =
            dev.sebastiano.indexino.model.ResolvedComponentIdentity.of(
                coordinate = ResolvedComponentCoordinate.of("com.example:lib:1.0.0"),
                artifactDigest = ArtifactDigest.of("sha256:abc"),
                variant = "jvm",
                substitution = null,
            )
        val registration =
            dev.sebastiano.indexino.model.SourceLinkRegistration.of(
                component = component,
                checkout =
                    dev.sebastiano.indexino.model.SourceLinkCheckout.of(
                        repositoryIdentity = "git@github.com:example/provider.git",
                        checkoutPath = "/tmp/provider",
                        revision = "abc",
                        tag = "v1.0.0",
                        dirty = false,
                        submoduleRevisions = emptyMap(),
                        sourceRoots = listOf("src/main/kotlin"),
                    ),
                sourceOriginId = dev.sebastiano.indexino.model.SourceOriginId.of("git:provider"),
                linkedGeneration = dev.sebastiano.indexino.model.WorkspaceGenerationId.of("gen-a"),
                mappingRule =
                    dev.sebastiano.indexino.model.SourceLinkMappingRule.packagePrefix(
                        "com.example.lib",
                        "src/main/kotlin",
                    ),
                evidence = SourceLinkEvidence.VERIFIED,
                diagnostics = emptyList(),
            )
        val (firstGeneration, _) = LinkGenerationComputer.compute(listOf(registration))
        val updated =
            dev.sebastiano.indexino.model.SourceLinkRegistration.of(
                component = registration.component,
                checkout = registration.checkout,
                sourceOriginId = registration.sourceOriginId,
                linkedGeneration = dev.sebastiano.indexino.model.WorkspaceGenerationId.of("gen-b"),
                mappingRule = registration.mappingRule,
                evidence = registration.evidence,
                diagnostics = registration.diagnostics,
            )
        val (secondGeneration, _) = LinkGenerationComputer.compute(listOf(updated))
        assertTrue(firstGeneration != secondGeneration)
    }
}
