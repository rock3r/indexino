package dev.sebastiano.indexino.model

public class SourceOriginRevision
@IndexinoInternalApi
public constructor(
    public val originId: SourceOriginId,
    public val revision: String?,
    public val stateFingerprint: String,
    public val expectedRevision: String?,
) {
    init {
        require(revision == null || revision.isNotBlank()) {
            "Source origin revision must not be blank"
        }
        require(stateFingerprint.isNotBlank()) {
            "Source origin state fingerprint must not be blank"
        }
        require(expectedRevision == null || expectedRevision.isNotBlank()) {
            "Expected source origin revision must not be blank"
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SourceOriginRevision &&
                originId == other.originId &&
                revision == other.revision &&
                stateFingerprint == other.stateFingerprint &&
                expectedRevision == other.expectedRevision

    override fun hashCode(): Int {
        var result = originId.hashCode()
        result = 31 * result + (revision?.hashCode() ?: 0)
        result = 31 * result + stateFingerprint.hashCode()
        result = 31 * result + (expectedRevision?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "SourceOriginRevision(originId=$originId, revision=$revision, " +
            "stateFingerprint=$stateFingerprint, expectedRevision=$expectedRevision)"
}
