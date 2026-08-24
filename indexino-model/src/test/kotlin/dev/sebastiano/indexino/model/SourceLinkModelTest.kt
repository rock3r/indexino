package dev.sebastiano.indexino.model

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(IndexinoInternalApi::class)
class SourceLinkModelTest {
    @Test
    fun `source link evidence exposes Java factories and exact-semantics policy`() {
        assertTrue(SourceLinkEvidence.VERIFIED.allowsExactCrossRepositorySemantics())
        assertTrue(SourceLinkEvidence.VERIFIED.allowsQualifiedIndexedQueries())
        assertFalse(SourceLinkEvidence.VERIFIED.isNavigationHintOnly())

        assertFalse(SourceLinkEvidence.RECONSTRUCTED.allowsExactCrossRepositorySemantics())
        assertTrue(SourceLinkEvidence.RECONSTRUCTED.allowsQualifiedIndexedQueries())
        assertFalse(SourceLinkEvidence.RECONSTRUCTED.isNavigationHintOnly())

        assertFalse(SourceLinkEvidence.DECLARED.allowsExactCrossRepositorySemantics())
        assertFalse(SourceLinkEvidence.DECLARED.allowsQualifiedIndexedQueries())
        assertTrue(SourceLinkEvidence.DECLARED.isNavigationHintOnly())

        assertFalse(SourceLinkEvidence.MISMATCH.allowsExactCrossRepositorySemantics())
        assertFalse(SourceLinkEvidence.MISMATCH.allowsQualifiedIndexedQueries())
        assertTrue(SourceLinkEvidence.MISMATCH.isNavigationHintOnly())

        assertEquals(SourceLinkEvidence.VERIFIED, SourceLinkEvidence.VERIFIED)
        assertNotEquals(SourceLinkEvidence.VERIFIED, SourceLinkEvidence.DECLARED)
    }

    @Test
    fun `resolved component identity requires coordinate and digest`() {
        val digest = ArtifactDigest.of("abc123")
        val coordinate = ResolvedComponentCoordinate.of("com.example:lib:1.0.0")
        val identity =
            ResolvedComponentIdentity.of(
                coordinate = coordinate,
                artifactDigest = digest,
                variant = "jvm",
                substitution = null,
            )
        val equal =
            ResolvedComponentIdentity.of(
                coordinate = coordinate,
                artifactDigest = digest,
                variant = "jvm",
                substitution = null,
            )
        assertEquals(identity, equal)
        assertEquals(identity.hashCode(), equal.hashCode())
        assertFailsWith<IllegalArgumentException> { ResolvedComponentCoordinate.of(" ") }
        assertFailsWith<IllegalArgumentException> { ArtifactDigest.of(" ") }
    }

    @Test
    fun `source link registration records checkout provenance and mapping rule`() {
        val origin = SourceOriginId.of("git:provider")
        val checkout =
            SourceLinkCheckout.of(
                repositoryIdentity = "git@github.com:example/provider.git",
                checkoutPath = "provider",
                revision = "abc123def456",
                tag = "v1.0.0",
                dirty = false,
                submoduleRevisions = mapOf("nested" to "subrev"),
                sourceRoots = listOf("src/main/kotlin"),
            )
        val mapping = SourceLinkMappingRule.packagePrefix("com.example.lib", "src/main/kotlin")
        val component =
            ResolvedComponentIdentity.of(
                coordinate = ResolvedComponentCoordinate.of("com.example:lib:1.0.0"),
                artifactDigest = ArtifactDigest.of("sha256:deadbeef"),
                variant = "jvm",
                substitution = null,
            )
        val registration =
            SourceLinkRegistration.of(
                component = component,
                checkout = checkout,
                sourceOriginId = origin,
                linkedGeneration = WorkspaceGenerationId.of("provider-gen-1"),
                mappingRule = mapping,
                evidence = SourceLinkEvidence.VERIFIED,
                diagnostics = emptyList(),
            )
        assertEquals(component, registration.component)
        assertEquals(origin, registration.sourceOriginId)
        assertEquals(SourceLinkEvidence.VERIFIED, registration.evidence)
        assertTrue(
            Modifier.isStatic(
                SourceLinkRegistration::class
                    .java
                    .getMethod(
                        "of",
                        ResolvedComponentIdentity::class.java,
                        SourceLinkCheckout::class.java,
                        SourceOriginId::class.java,
                        WorkspaceGenerationId::class.java,
                        SourceLinkMappingRule::class.java,
                        SourceLinkEvidence::class.java,
                        List::class.java,
                    )
                    .modifiers
            )
        )
    }

    @Test
    fun `dependency to generation edge ties resolved component to linked generation`() {
        val component =
            ResolvedComponentIdentity.of(
                coordinate = ResolvedComponentCoordinate.of("org.jetbrains.skiko:skiko-awt:0.7.0"),
                artifactDigest = ArtifactDigest.of("sha256:skiko"),
                variant = "jvm",
                substitution = null,
            )
        val edge =
            DependencyToGenerationEdge.of(
                component = component,
                linkedGeneration = WorkspaceGenerationId.of("skiko-gen"),
                linkGeneration = LinkGenerationId.of("link-gen-1"),
                evidence = SourceLinkEvidence.RECONSTRUCTED,
            )
        assertEquals(component, edge.component)
        assertEquals(LinkGenerationId.of("link-gen-1"), edge.linkGeneration)
    }

    @Test
    fun `linked source result carries provenance on every linked fact`() {
        val origin = SourceOriginId.of("git:provider")
        val file = SourceFile.of(origin, "src/Lib.kt", "src/Lib.kt")
        val location = SourceLocation.of(file, 10, 4, null)
        val component =
            ResolvedComponentIdentity.of(
                coordinate = ResolvedComponentCoordinate.of("com.example:lib:1.0.0"),
                artifactDigest = ArtifactDigest.of("sha256:abc"),
                variant = "jvm",
                substitution = "local-project",
            )
        val diagnostic =
            SourceLinkDiagnostic.of("digest.mismatch", "artifact digest does not match tag")
        val result =
            LinkedSourceResult.of(
                component = component,
                sourceRevision = SourceOriginRevision(origin, "abc123", "fp", "abc123"),
                evidence = SourceLinkEvidence.MISMATCH,
                diagnostics = listOf(diagnostic),
                location = location,
                symbolName = "com.example.Lib",
            )
        assertEquals(component, result.component)
        assertEquals(SourceLinkEvidence.MISMATCH, result.evidence)
        assertEquals(listOf(diagnostic), result.diagnostics)
        assertEquals("com.example.Lib", result.symbolName)
    }
}
