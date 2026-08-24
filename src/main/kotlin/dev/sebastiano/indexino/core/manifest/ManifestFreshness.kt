package dev.sebastiano.indexino.core.manifest

import dev.sebastiano.indexino.core.BASIC_FACT_SCHEMA_VERSION
import dev.sebastiano.indexino.core.Version

internal data class ManifestFreshnessCriteria(
    val commit: String,
    val indexerVersion: String,
    val basicFactSchemaVersion: Int = BASIC_FACT_SCHEMA_VERSION,
    val scope: String,
    val includeDeps: Boolean,
    val sourcesContentHash: String,
    val applications: List<String>,
    val pluginCoordinates: Map<String, String> = emptyMap(),
    val origins: List<IndexManifestOrigin> = emptyList(),
    val resolvedTopologyDigest: String? = null,
)

internal object ManifestFreshness {
    fun isFresh(manifest: IndexManifest, criteria: ManifestFreshnessCriteria): Boolean =
        manifest.commit == criteria.commit &&
            manifest.indexerVersion == criteria.indexerVersion &&
            manifest.basicFactSchemaVersion == criteria.basicFactSchemaVersion &&
            manifest.scope == criteria.scope &&
            manifest.includeDeps == criteria.includeDeps &&
            manifest.sourcesContentHash == criteria.sourcesContentHash &&
            manifest.applications.sorted() == criteria.applications.sorted() &&
            manifest.pluginCoordinates == criteria.pluginCoordinates &&
            (criteria.origins.isEmpty() || manifest.origins == criteria.origins) &&
            manifest.resolvedTopologyDigest == criteria.resolvedTopologyDigest

    fun criteriaFrom(
        commit: String,
        scope: String,
        includeDeps: Boolean,
        sourcesContentHash: String,
        applications: List<String>,
        pluginCoordinates: Map<String, String> = emptyMap(),
        origins: List<IndexManifestOrigin> = emptyList(),
        resolvedTopologyDigest: String? = null,
    ): ManifestFreshnessCriteria =
        ManifestFreshnessCriteria(
            commit = commit,
            indexerVersion = Version.NAME,
            basicFactSchemaVersion = BASIC_FACT_SCHEMA_VERSION,
            scope = scope,
            includeDeps = includeDeps,
            sourcesContentHash = sourcesContentHash,
            applications = applications,
            pluginCoordinates = pluginCoordinates,
            origins = origins,
            resolvedTopologyDigest = resolvedTopologyDigest,
        )
}
