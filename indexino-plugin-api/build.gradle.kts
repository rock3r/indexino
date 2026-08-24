import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar
import java.io.File
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    `java-library`
    alias(libs.plugins.maven.publish)
}

group = providers.gradleProperty("GROUP").get()

version = providers.gradleProperty("VERSION_NAME").get()

kotlin {
    jvmToolchain(25)
    explicitApi()
}

repositories {
    mavenCentral()
    google()
}

ktfmt { kotlinLangStyle() }

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("detekt.yml"))
}

dependencies {
    detektPlugins(project(":detekt-plugin"))
    api(project(":indexino-model"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
}

mavenPublishing {
    coordinates(group.toString(), "indexino-plugin-api", version.toString())
    publishToMavenCentral(automaticRelease = false)
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))

    if (providers.gradleProperty("signingInMemoryKey").orNull?.isNotBlank() == true) {
        signAllPublications()
    }
}

publishing {
    repositories {
        maven {
            name = "Test"
            url = uri(rootProject.layout.buildDirectory.dir("test-maven-repository"))
        }
    }
}

tasks.test { useJUnitPlatform() }

val metalava by configurations.creating

dependencies { metalava(libs.metalava) }

val metalavaOutput = layout.buildDirectory.file("metalava/indexino-plugin-api-current.txt")
val reviewedMetalavaSignature =
    rootProject.layout.projectDirectory.file("api/indexino-plugin-api/current.txt")
val pluginAbiVersionFile =
    rootProject.layout.projectDirectory.file("api/indexino-plugin-api/abi-version.txt")
val pluginAbiLineageFile =
    rootProject.layout.projectDirectory.file("api/indexino-plugin-api/abi-lineage.txt")
val pluginAbiHistoryDirectory =
    rootProject.layout.projectDirectory.dir("api/indexino-plugin-api/history")
val generatedPluginAbiMetadata =
    layout.buildDirectory.file("generated/plugin-abi/plugin-abi.properties")
val jdk25Launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }

val metalavaGenerateSignature by
    tasks.registering(JavaExec::class) {
        dependsOn(tasks.named("classes"))
        classpath = metalava
        mainClass.set("com.android.tools.metalava.Driver")
        javaLauncher.set(jdk25Launcher)
        outputs.file(metalavaOutput)
        doFirst {
            args =
                listOf(
                    "--source-path",
                    layout.projectDirectory.dir("src/main/kotlin").asFile.absolutePath,
                    "--classpath",
                    sourceSets.main
                        .get()
                        .compileClasspath
                        .files
                        .filter { it.exists() }
                        .joinToString(File.pathSeparator),
                    "--jdk-home",
                    jdk25Launcher.get().metadata.installationPath.asFile.absolutePath,
                    "--kotlin-source",
                    "2.4",
                    "--format=5.0",
                    "--api",
                    metalavaOutput.get().asFile.absolutePath,
                    "--api-lint",
                    "--error",
                    "ValueClassDefinition",
                    "--error",
                    "MissingJvmstatic",
                    "--hide",
                    "GetterSetterNames",
                    "--hide",
                    "AutoBoxing",
                    "--hide",
                    "UserHandleName",
                    "--hide",
                    "Enum",
                )
        }
    }

val metalavaUpdateSignature by tasks.registering {
    dependsOn(metalavaGenerateSignature)
    doLast {
        reviewedMetalavaSignature.asFile.apply {
            parentFile.mkdirs()
            writeText(metalavaOutput.get().asFile.readText())
        }
    }
}

val metalavaCheckSignature by tasks.registering {
    dependsOn(metalavaGenerateSignature)
    doLast {
        check(
            metalavaOutput.get().asFile.readText() == reviewedMetalavaSignature.asFile.readText()
        ) {
            "Generated signature differs from api/indexino-plugin-api/current.txt"
        }
    }
}

data class AbiVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AbiVersion> {
    override fun compareTo(other: AbiVersion): Int =
        compareValuesBy(this, other, AbiVersion::major, AbiVersion::minor, AbiVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"
}

abstract class MetalavaCompatibilityRunner
@Inject
constructor(private val execOperations: ExecOperations) {
    fun run(configure: org.gradle.process.JavaExecSpec.() -> Unit): Int =
        execOperations
            .javaexec {
                isIgnoreExitValue = true
                configure()
            }
            .exitValue
}

val metalavaCompatibilityRunner = objects.newInstance<MetalavaCompatibilityRunner>()

fun parseAbiVersion(value: String): AbiVersion {
    val match =
        requireNotNull(Regex("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)").matchEntire(value)) {
            "Plugin ABI must be SemVer major.minor.patch: $value"
        }
    return AbiVersion(
        match.groupValues[1].toInt(),
        match.groupValues[2].toInt(),
        match.groupValues[3].toInt(),
    )
}

fun metalavaCompatibilityExit(previous: File): Int {
    val comparisonOutput =
        layout.buildDirectory.file("metalava/compatibility-${previous.name}").get().asFile
    return metalavaCompatibilityRunner.run {
        classpath = metalava
        mainClass.set("com.android.tools.metalava.Driver")
        executable = jdk25Launcher.get().executablePath.asFile.absolutePath
        args(
            "--source-path",
            layout.projectDirectory.dir("src/main/kotlin").asFile.absolutePath,
            "--classpath",
            sourceSets.main
                .get()
                .compileClasspath
                .files
                .filter { it.exists() }
                .joinToString(File.pathSeparator),
            "--jdk-home",
            jdk25Launcher.get().metadata.installationPath.asFile.absolutePath,
            "--kotlin-source",
            "2.4",
            "--format=5.0",
            "--api",
            comparisonOutput.absolutePath,
            "--check-compatibility:api:released",
            previous.absolutePath,
        )
    }
}

val verifyPluginAbiLineage by tasks.registering {
    group = "verification"
    description = "Derive and verify plugin ABI support from reviewed Metalava history"
    dependsOn(metalavaGenerateSignature)
    inputs.file(pluginAbiVersionFile)
    inputs.file(pluginAbiLineageFile)
    inputs.dir(pluginAbiHistoryDirectory)
    inputs.file(reviewedMetalavaSignature)
    inputs.file(metalavaOutput)
    outputs.file(generatedPluginAbiMetadata)
    doLast {
        val current = parseAbiVersion(pluginAbiVersionFile.asFile.readText().trim())
        check(current.major >= 1) { "Plugin ABI starts at 1.0.0, not the SemVer 0.x line" }
        val history =
            pluginAbiHistoryDirectory.asFile
                .listFiles { file -> file.isFile && file.extension == "txt" }
                .orEmpty()
                .associateBy { parseAbiVersion(it.nameWithoutExtension) }
        val declaredLineage =
            pluginAbiLineageFile.asFile
                .readLines()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map(::parseAbiVersion)
        check(declaredLineage == declaredLineage.sorted().distinct()) {
            "Plugin ABI lineage must be unique and strictly ordered"
        }
        check(declaredLineage.lastOrNull() == current) {
            "Plugin ABI lineage must end at declared current ABI $current"
        }
        val missingHistory = declaredLineage.filterNot(history::containsKey)
        check(missingHistory.isEmpty()) {
            "Plugin ABI history is missing required dumps: $missingHistory"
        }
        check(history.keys == declaredLineage.toSet()) {
            "Plugin ABI history contains dumps not declared in abi-lineage.txt"
        }
        val currentDump = history[current]
        check(currentDump != null) {
            "Plugin ABI history is missing the reviewed ${current}.txt dump"
        }
        fun canonicalSignature(file: File): String = file.readText().replace("\r\n", "\n").trimEnd()
        check(
            canonicalSignature(currentDump) == canonicalSignature(reviewedMetalavaSignature.asFile)
        ) {
            "api/indexino-plugin-api/current.txt must equal immutable history/${current}.txt"
        }
        check(canonicalSignature(metalavaOutput.get().asFile) == canonicalSignature(currentDump)) {
            "Generated plugin API differs from reviewed history/${current}.txt; review the " +
                "Metalava diff and declare the required ABI evolution before publishing"
        }
        val ordered = declaredLineage
        val previous = ordered.dropLast(1).lastOrNull()
        if (previous == null) {
            check(current == AbiVersion(1, 0, 0)) {
                "The first plugin ABI lineage entry must be 1.0.0"
            }
        } else {
            val textChanged =
                canonicalSignature(history.getValue(previous)) != canonicalSignature(currentDump)
            val breaking = textChanged && metalavaCompatibilityExit(history.getValue(previous)) != 0
            if (current.major == previous.major) {
                check(!breaking) {
                    "Breaking Metalava change from $previous to $current requires an ABI major increment"
                }
                if (current.minor == previous.minor) {
                    check(!textChanged) {
                        "Additive Metalava change from $previous to $current requires an ABI minor increment"
                    }
                } else {
                    check(textChanged) {
                        "ABI minor increment from $previous to $current has no Metalava API change"
                    }
                }
            } else {
                check(
                    current.major == previous.major + 1 && current.minor == 0 && current.patch == 0
                ) {
                    "Breaking ABI lineage must advance to the next major at x.0.0"
                }
                check(breaking) {
                    "ABI major increment from $previous to $current requires a breaking Metalava change"
                }
            }
        }
        val currentMajor = ordered.filter { it.major == current.major }
        currentMajor.dropLast(1).forEach { version ->
            check(metalavaCompatibilityExit(history.getValue(version)) == 0) {
                "Host ABI $current is not compatible with advertised lower bound $version"
            }
        }
        generatedPluginAbiMetadata.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                "current=$current\n" +
                    "minimum=${currentMajor.first()}\n" +
                    "supported=${currentMajor.first()}..$current\n"
            )
        }
    }
}

tasks.named<Jar>("jar") {
    dependsOn(verifyPluginAbiLineage)
    from(generatedPluginAbiMetadata) { into("META-INF/indexino") }
    manifest {
        attributes["Indexino-Plugin-ABI-Version"] = pluginAbiVersionFile.asFile.readText().trim()
    }
}

tasks.named("check") { dependsOn(metalavaCheckSignature, verifyPluginAbiLineage) }
