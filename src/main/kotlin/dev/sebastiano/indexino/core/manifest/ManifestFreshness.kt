package dev.sebastiano.indexino.core.manifest

import dev.sebastiano.indexino.core.Version

internal data class ManifestFreshnessCriteria(
    val commit: String,
    val indexerVersion: String,
    val scope: String,
    val includeDeps: Boolean,
    val sourcesContentHash: String,
    val applications: List<String>,
    val pluginCoordinates: Map<String, String> = emptyMap(),
)

internal object ManifestFreshness {
    fun isFresh(manifest: IndexManifest, criteria: ManifestFreshnessCriteria): Boolean =
        manifest.commit == criteria.commit &&
            manifest.indexerVersion == criteria.indexerVersion &&
            manifest.scope == criteria.scope &&
            manifest.includeDeps == criteria.includeDeps &&
            manifest.sourcesContentHash == criteria.sourcesContentHash &&
            manifest.applications.sorted() == criteria.applications.sorted() &&
            manifest.pluginCoordinates == criteria.pluginCoordinates

    fun criteriaFrom(
        commit: String,
        scope: String,
        includeDeps: Boolean,
        sourcesContentHash: String,
        applications: List<String>,
        pluginCoordinates: Map<String, String> = emptyMap(),
    ): ManifestFreshnessCriteria =
        ManifestFreshnessCriteria(
            commit = commit,
            indexerVersion = Version.NAME,
            scope = scope,
            includeDeps = includeDeps,
            sourcesContentHash = sourcesContentHash,
            applications = applications,
            pluginCoordinates = pluginCoordinates,
        )
}
