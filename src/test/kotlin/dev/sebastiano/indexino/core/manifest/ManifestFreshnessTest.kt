package dev.sebastiano.indexino.core.manifest

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManifestFreshnessTest {
    @Test
    fun `fresh when all fields match`() {
        val manifest =
            IndexManifest(
                commit = "abc",
                indexerVersion = "0.1.0",
                basicFactSchemaVersion = 3,
                scope = "//ui:ui",
                topology = "bazel-query",
                includeDeps = true,
                sourceFileCount = 2,
                sourcesContentHash = "sha256:dead",
                builtAt = "2026-01-01T00:00:00Z",
                applications = listOf("selection-context"),
            )
        val criteria =
            ManifestFreshnessCriteria(
                commit = "abc",
                indexerVersion = "0.1.0",
                scope = "//ui:ui",
                includeDeps = true,
                sourcesContentHash = "sha256:dead",
                applications = listOf("selection-context"),
            )
        assertTrue(ManifestFreshness.isFresh(manifest, criteria))
    }

    @Test
    fun `stale when criteria basic fact schema differs`() {
        val manifest = sampleManifest()
        val criteria = sampleCriteria().copy(basicFactSchemaVersion = 2)

        assertFalse(ManifestFreshness.isFresh(manifest, criteria))
    }

    @Test
    fun `stale when origin graph differs`() {
        val manifest =
            sampleManifest()
                .copy(origins = listOf(IndexManifestOrigin("git:first", "first", "sha256:first")))
        val criteria =
            sampleCriteria()
                .copy(
                    origins = listOf(IndexManifestOrigin("git:second", "second", "sha256:second"))
                )

        assertFalse(ManifestFreshness.isFresh(manifest, criteria))
    }

    @Test
    fun `stale when sources hash differs`() {
        val manifest = sampleManifest(sourcesContentHash = "sha256:old")
        val criteria = sampleCriteria(sourcesContentHash = "sha256:new")
        assertFalse(ManifestFreshness.isFresh(manifest, criteria))
    }

    @Test
    fun `stale when manifest basic fact schema differs`() {
        val manifest = sampleManifest().copy(basicFactSchemaVersion = 2)

        assertFalse(ManifestFreshness.isFresh(manifest, sampleCriteria()))
    }

    @Test
    fun `stale when scope differs`() {
        val manifest = sampleManifest(scope = "//a:ui")
        val criteria = sampleCriteria(scope = "//b:ui")
        assertFalse(ManifestFreshness.isFresh(manifest, criteria))
    }

    @Test
    fun `stale when includeDeps differs`() {
        val manifest = sampleManifest(includeDeps = false)
        val criteria = sampleCriteria(includeDeps = true)
        assertFalse(ManifestFreshness.isFresh(manifest, criteria))
    }

    @Test
    fun `stale when applications differ`() {
        val manifest = sampleManifest(applications = listOf("selection-context"))
        val criteria = sampleCriteria(applications = emptyList())
        assertFalse(ManifestFreshness.isFresh(manifest, criteria))
    }

    private fun sampleManifest(
        scope: String = "//ui:ui",
        includeDeps: Boolean = true,
        sourcesContentHash: String = "sha256:dead",
        applications: List<String> = listOf("selection-context"),
    ) =
        IndexManifest(
            commit = "abc",
            indexerVersion = "0.1.0",
            basicFactSchemaVersion = 3,
            scope = scope,
            topology = "bazel-query",
            includeDeps = includeDeps,
            sourceFileCount = 2,
            sourcesContentHash = sourcesContentHash,
            builtAt = "2026-01-01T00:00:00Z",
            applications = applications,
        )

    private fun sampleCriteria(
        scope: String = "//ui:ui",
        includeDeps: Boolean = true,
        sourcesContentHash: String = "sha256:dead",
        applications: List<String> = listOf("selection-context"),
    ) =
        ManifestFreshnessCriteria(
            commit = "abc",
            indexerVersion = "0.1.0",
            scope = scope,
            includeDeps = includeDeps,
            sourcesContentHash = sourcesContentHash,
            applications = applications,
        )
}
