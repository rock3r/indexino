package dev.sebastiano.indexino.engine

import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

internal class PluginAbiCompatibilityException(
    val pluginId: String,
    val hostAbi: String,
    val targetAbi: String,
    val supportedRange: String,
    val remediation: String,
) :
    IllegalArgumentException(
        "Plugin $pluginId target ABI $targetAbi is incompatible; host ABI $hostAbi, " +
            "supported range $supportedRange. $remediation"
    )

internal class PluginAbiSupport
private constructor(val minimum: PluginAbiVersion, val current: PluginAbiVersion) {
    fun requireCompatible(pluginId: String, target: String) {
        val targetVersion =
            try {
                PluginAbiVersion.parse(target)
            } catch (_: IllegalArgumentException) {
                throw incompatible(
                    pluginId,
                    target,
                    "Rebuild the plugin with a valid SemVer ABI target generated from its " +
                        "indexino-plugin-api dependency.",
                )
            }
        if (
            targetVersion.major != current.major ||
                targetVersion < minimum ||
                targetVersion > current
        ) {
            throw incompatible(
                pluginId,
                targetVersion.toString(),
                "Rebuild the plugin against a supported indexino-plugin-api version or " +
                    "upgrade the Indexino host.",
            )
        }
    }

    private fun incompatible(
        pluginId: String,
        target: String,
        remediation: String,
    ): PluginAbiCompatibilityException =
        PluginAbiCompatibilityException(
            pluginId = pluginId,
            hostAbi = current.toString(),
            targetAbi = target,
            supportedRange = "[$minimum, $current]",
            remediation = remediation,
        )

    override fun equals(other: Any?): Boolean =
        other is PluginAbiSupport && minimum == other.minimum && current == other.current

    override fun hashCode(): Int = listOf(minimum, current).hashCode()

    override fun toString(): String = "PluginAbiSupport(minimum=$minimum, current=$current)"

    companion object {
        fun of(minimum: String, current: String): PluginAbiSupport {
            val minimumVersion = PluginAbiVersion.parse(minimum)
            val currentVersion = PluginAbiVersion.parse(current)
            require(minimumVersion.major == currentVersion.major) {
                "A single supported ABI range cannot cross major versions"
            }
            require(minimumVersion <= currentVersion) { "ABI range minimum exceeds current ABI" }
            return PluginAbiSupport(minimumVersion, currentVersion)
        }

        fun load(classLoader: ClassLoader): PluginAbiSupport {
            val properties = Properties()
            val resource =
                requireNotNull(classLoader.getResourceAsStream(METADATA_RESOURCE)) {
                    "Host plugin ABI metadata is missing: $METADATA_RESOURCE"
                }
            resource.use(properties::load)
            return of(
                requireNotNull(properties.getProperty("minimum")) {
                    "Host plugin ABI metadata has no minimum"
                },
                requireNotNull(properties.getProperty("current")) {
                    "Host plugin ABI metadata has no current"
                },
            )
        }

        private const val METADATA_RESOURCE = "META-INF/indexino/host-plugin-abi.properties"
    }
}

internal class PluginAbiVersion
private constructor(val major: Int, val minor: Int, val patch: Int) : Comparable<PluginAbiVersion> {
    override fun compareTo(other: PluginAbiVersion): Int =
        compareValuesBy(
            this,
            other,
            PluginAbiVersion::major,
            PluginAbiVersion::minor,
            PluginAbiVersion::patch,
        )

    override fun toString(): String = "$major.$minor.$patch"

    override fun equals(other: Any?): Boolean =
        other is PluginAbiVersion &&
            major == other.major &&
            minor == other.minor &&
            patch == other.patch

    override fun hashCode(): Int = listOf(major, minor, patch).hashCode()

    companion object {
        private val PATTERN = Regex("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)")

        fun parse(value: String): PluginAbiVersion {
            val match =
                requireNotNull(PATTERN.matchEntire(value)) {
                    "Plugin ABI must be SemVer major.minor.patch: $value"
                }
            val (major, minor, patch) = match.destructured
            return PluginAbiVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}

internal enum class ApiEvolution {
    UNCHANGED,
    ADDITIVE,
    BREAKING,
}

internal data class DerivedPluginAbiLineage(val support: PluginAbiSupport)

internal object PluginAbiLineage {
    fun derive(
        historyDirectory: Path,
        currentVersion: String,
        requiredVersions: List<String>? = null,
        compare: (Path, Path) -> ApiEvolution,
    ): DerivedPluginAbiLineage {
        require(historyDirectory.isDirectory()) {
            "Plugin ABI history directory is missing: $historyDirectory"
        }
        val current = PluginAbiVersion.parse(currentVersion)
        val dumps =
            historyDirectory.listDirectoryEntries("*.txt").associateBy {
                PluginAbiVersion.parse(it.fileName.toString().removeSuffix(".txt"))
            }
        requiredVersions?.map(PluginAbiVersion::parse)?.let { required ->
            check(required == required.sorted().distinct()) {
                "Plugin ABI lineage must be unique and strictly ordered"
            }
            val missing = required.filterNot(dumps::containsKey)
            check(missing.isEmpty()) { "Plugin ABI history is missing required dumps: $missing" }
        }
        check(current in dumps) { "Plugin ABI history is missing the reviewed $current.txt dump" }
        val ordered = dumps.keys.filter { it <= current }.sorted()
        check(ordered.isNotEmpty()) { "Plugin ABI history is empty" }
        ordered.zipWithNext().forEach { (previous, next) ->
            val evolution = compare(dumps.getValue(previous), dumps.getValue(next))
            validateEvolution(previous, next, evolution)
        }
        val currentMajor = ordered.filter { it.major == current.major }
        check(currentMajor.isNotEmpty()) {
            "Plugin ABI history has no lineage for major ${current.major}"
        }
        return DerivedPluginAbiLineage(
            PluginAbiSupport.of(currentMajor.first().toString(), current.toString())
        )
    }

    private fun validateEvolution(
        previous: PluginAbiVersion,
        next: PluginAbiVersion,
        evolution: ApiEvolution,
    ) {
        check(next > previous) { "Plugin ABI history is not strictly increasing" }
        if (next.major == previous.major) {
            check(evolution != ApiEvolution.BREAKING) {
                "Breaking Metalava change from $previous to $next requires an ABI major increment"
            }
            if (next.minor == previous.minor) {
                check(evolution == ApiEvolution.UNCHANGED) {
                    "Additive Metalava change from $previous to $next requires an ABI minor increment"
                }
            } else {
                check(evolution == ApiEvolution.ADDITIVE) {
                    "ABI minor increment from $previous to $next must contain an additive Metalava change"
                }
            }
        } else {
            check(next.major == previous.major + 1 && next.minor == 0 && next.patch == 0) {
                "Breaking ABI lineage must advance to the next major at x.0.0: $previous to $next"
            }
            check(evolution == ApiEvolution.BREAKING) {
                "ABI major increment from $previous to $next requires a breaking Metalava change"
            }
        }
    }
}
