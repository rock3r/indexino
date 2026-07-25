package dev.sebastiano.indexino.model

import java.util.Collections

public class WorkspaceRevision
@IndexinoInternalApi
public constructor(public val fingerprint: String, origins: List<SourceOriginRevision>) {
    public val origins: List<SourceOriginRevision> =
        Collections.unmodifiableList(origins.sortedBy { it.originId.value })

    init {
        require(fingerprint.isNotBlank()) { "Workspace revision fingerprint must not be blank" }
        require(origins.isNotEmpty()) { "Workspace revision must contain at least one origin" }
        require(origins.map { it.originId }.toSet().size == origins.size) {
            "Workspace revision must not contain duplicate origins"
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkspaceRevision &&
                fingerprint == other.fingerprint &&
                origins == other.origins

    override fun hashCode(): Int = 31 * fingerprint.hashCode() + origins.hashCode()

    override fun toString(): String =
        "WorkspaceRevision(fingerprint=$fingerprint, origins=$origins)"
}
