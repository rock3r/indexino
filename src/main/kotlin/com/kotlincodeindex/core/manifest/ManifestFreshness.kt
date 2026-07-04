package com.kotlincodeindex.core.manifest

import com.kotlincodeindex.core.Version

data class ManifestFreshnessCriteria(
    val commit: String,
    val indexerVersion: String,
    val scope: String,
    val sourcesContentHash: String,
    val applications: List<String>,
)

object ManifestFreshness {
    fun isFresh(manifest: IndexManifest, criteria: ManifestFreshnessCriteria): Boolean =
        manifest.commit == criteria.commit &&
            manifest.indexerVersion == criteria.indexerVersion &&
            manifest.scope == criteria.scope &&
            manifest.sourcesContentHash == criteria.sourcesContentHash &&
            manifest.applications.sorted() == criteria.applications.sorted()

    fun criteriaFrom(
        commit: String,
        scope: String,
        sourcesContentHash: String,
        applications: List<String>,
    ): ManifestFreshnessCriteria =
        ManifestFreshnessCriteria(
            commit = commit,
            indexerVersion = Version.NAME,
            scope = scope,
            sourcesContentHash = sourcesContentHash,
            applications = applications,
        )
}
