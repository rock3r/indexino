package dev.sebastiano.indexino.model

public class WorkspaceGenerationId private constructor(public val value: String) {
    public companion object {
        @JvmStatic
        public fun of(value: String): WorkspaceGenerationId {
            require(value.isNotBlank()) { "Workspace generation ID must not be blank" }
            return WorkspaceGenerationId(value)
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkspaceGenerationId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "WorkspaceGenerationId(value=$value)"
}
