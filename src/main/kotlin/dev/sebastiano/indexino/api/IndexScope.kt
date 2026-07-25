package dev.sebastiano.indexino.api

public class IndexScope
private constructor(
    public val buildSystem: BuildSystem,
    public val value: String,
    public val includesDependencies: Boolean,
) {
    public companion object {
        /**
         * Bazel target scope without the dependency closure.
         *
         * In S1 the in-process host always resolves Bazel scopes through the dependency closure, so
         * a target-only scope is rejected with `INVALID_REQUEST`; call [includingDependencies].
         * This host restriction lifts when Bazel topology honours the flag (see issue #26).
         */
        @JvmStatic
        public fun bazel(target: String): IndexScope {
            require(target.isNotBlank()) { "Bazel target must not be blank" }
            return IndexScope(BuildSystem.BAZEL, target, false)
        }

        @JvmStatic
        public fun gradle(module: String): IndexScope {
            require(module.isNotBlank()) { "Gradle module must not be blank" }
            return IndexScope(BuildSystem.GRADLE, module, false)
        }
    }

    /** Include the build-system dependency closure of [value] when resolving sources. */
    public fun includingDependencies(): IndexScope =
        IndexScope(buildSystem, value, includesDependencies = true)

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is IndexScope &&
                buildSystem == other.buildSystem &&
                value == other.value &&
                includesDependencies == other.includesDependencies

    override fun hashCode(): Int {
        var result = buildSystem.hashCode()
        result = 31 * result + value.hashCode()
        result = 31 * result + includesDependencies.hashCode()
        return result
    }

    override fun toString(): String =
        "IndexScope(buildSystem=$buildSystem, value=$value, " +
            "includesDependencies=$includesDependencies)"
}
