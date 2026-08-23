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
         * Resolves source files referenced by the target's source and resource attributes,
         * including files reached through filegroups. Call [includingDependencies] to expand the
         * target's build dependency closure.
         */
        @JvmStatic
        public fun bazel(target: String): IndexScope {
            require(target.isNotBlank()) { "Bazel target must not be blank" }
            return IndexScope(BuildSystem.BAZEL, target, false)
        }

        /**
         * Gradle module scope without the dependency closure.
         *
         * The root module `":"` always resolves the whole build in topology; call
         * [includingDependencies] for that scope so the published provenance matches. A dedicated
         * whole-workspace scope is a later topology product decision.
         */
        @JvmStatic
        public fun gradle(module: String): IndexScope {
            require(module.isNotBlank()) { "Gradle module must not be blank" }
            requireValidGradleModulePath(module)
            return IndexScope(BuildSystem.GRADLE, module, false)
        }

        private fun requireValidGradleModulePath(module: String) {
            // Colon-separated Gradle module identifiers never use filesystem path segments.
            // Reject them here as a structural factory invariant (same shape as SourceFile paths).
            val body = module.removePrefix(":")
            if (body.isEmpty()) {
                return
            }
            require(body.split(':').all(::isValidGradleModuleSegment)) {
                "Gradle module path must not contain empty, '.', '..', or path-separator " +
                    "segments: $module"
            }
        }

        private fun isValidGradleModuleSegment(segment: String): Boolean =
            segment.isNotEmpty() &&
                segment != "." &&
                segment != ".." &&
                '/' !in segment &&
                '\\' !in segment
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
