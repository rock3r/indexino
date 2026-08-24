package dev.sebastiano.indexino.model

import java.util.Collections

public class SourceLinkCheckout
private constructor(
    public val repositoryIdentity: String,
    public val checkoutPath: String,
    public val revision: String?,
    public val tag: String?,
    public val dirty: Boolean,
    submoduleRevisions: Map<String, String>,
    sourceRoots: List<String>,
) {
    public val submoduleRevisions: Map<String, String> =
        Collections.unmodifiableMap(
            linkedMapOf<String, String>().apply { putAll(submoduleRevisions) }
        )

    public val sourceRoots: List<String> = Collections.unmodifiableList(ArrayList(sourceRoots))

    init {
        require(repositoryIdentity.isNotBlank()) { "Repository identity must not be blank" }
        require(checkoutPath.isNotBlank()) { "Checkout path must not be blank" }
        require(revision == null || revision.isNotBlank()) { "Checkout revision must not be blank" }
        require(tag == null || tag.isNotBlank()) { "Checkout tag must not be blank" }
        require(submoduleRevisions.keys.none(String::isBlank)) {
            "Submodule names must not be blank"
        }
        require(submoduleRevisions.values.none(String::isBlank)) {
            "Submodule revisions must not be blank"
        }
        require(sourceRoots.isNotEmpty()) {
            "Source link checkout requires at least one source root"
        }
        require(sourceRoots.none(String::isBlank)) { "Source roots must not be blank" }
    }

    public companion object {
        @JvmStatic
        public fun of(
            repositoryIdentity: String,
            checkoutPath: String,
            revision: String?,
            tag: String?,
            dirty: Boolean,
            submoduleRevisions: Map<String, String>,
            sourceRoots: List<String>,
        ): SourceLinkCheckout =
            SourceLinkCheckout(
                repositoryIdentity = repositoryIdentity,
                checkoutPath = checkoutPath,
                revision = revision,
                tag = tag,
                dirty = dirty,
                submoduleRevisions = submoduleRevisions,
                sourceRoots = sourceRoots,
            )
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SourceLinkCheckout &&
                repositoryIdentity == other.repositoryIdentity &&
                checkoutPath == other.checkoutPath &&
                revision == other.revision &&
                tag == other.tag &&
                dirty == other.dirty &&
                submoduleRevisions == other.submoduleRevisions &&
                sourceRoots == other.sourceRoots

    override fun hashCode(): Int {
        var result = repositoryIdentity.hashCode()
        result = 31 * result + checkoutPath.hashCode()
        result = 31 * result + (revision?.hashCode() ?: 0)
        result = 31 * result + (tag?.hashCode() ?: 0)
        result = 31 * result + dirty.hashCode()
        result = 31 * result + submoduleRevisions.hashCode()
        result = 31 * result + sourceRoots.hashCode()
        return result
    }

    override fun toString(): String =
        "SourceLinkCheckout(repositoryIdentity=$repositoryIdentity, checkoutPath=$checkoutPath, " +
            "revision=$revision, tag=$tag, dirty=$dirty, submoduleRevisions=$submoduleRevisions, " +
            "sourceRoots=$sourceRoots)"
}
