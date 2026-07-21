// SPDX-License-Identifier: UEL-1.0

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.jengelman.gradle.plugins.shadow.transformers.PreserveFirstFoundResourceTransformer
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.DetektCreateBaselineTask
import dev.sebastiano.indexino.buildlogic.NormalizedJar
import io.github.fourlastor.construo.ConstruoPluginExtension
import io.github.fourlastor.construo.Target
import io.github.fourlastor.construo.task.PackageTask
import io.github.fourlastor.construo.task.jvm.RoastTask
import java.util.Properties
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.shadow)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.construo)
}

group = providers.gradleProperty("GROUP").get()

version = providers.gradleProperty("VERSION_NAME").get()

kotlin {
    jvmToolchain(21)
    explicitApi()
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation {}
}

ktfmt { kotlinLangStyle() }

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("detekt.yml"))
}

val generatedSourceExcludes = arrayOf("**/build/**", "**/generated/**")

tasks.withType<Detekt>().configureEach { exclude(*generatedSourceExcludes) }

tasks.withType<DetektCreateBaselineTask>().configureEach { exclude(*generatedSourceExcludes) }

tasks.named<Detekt>("detektMain") { setSource(files("src/main/kotlin")) }

tasks.named<Detekt>("detektTest") { setSource(files("src/test/kotlin")) }

tasks
    .matching { it.name.startsWith("ktfmtCheck") || it.name.startsWith("ktfmtFormat") }
    .configureEach { (this as? org.gradle.api.tasks.SourceTask)?.exclude(*generatedSourceExcludes) }

sourceSets.main { resources.srcDir("config") }

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(libs.kotlin.compiler.embeddable)
    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xodus.environment)
    implementation(libs.slf4j.nop)

    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

val cliMainClass = "dev.sebastiano.indexino.cli.MainCommandKt"

application { mainClass.set(cliMainClass) }

val mainSourceSet = sourceSets.main
val runtimeClasspathConfiguration = configurations.runtimeClasspath

fun ShadowJar.configureCliArchive(classifier: String) {
    group = "distribution"
    from(mainSourceSet.map { it.output })
    configurations = listOf(runtimeClasspathConfiguration.get())
    archiveClassifier.set(classifier)
    manifest { attributes["Main-Class"] = cliMainClass }
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    exclude("META-INF/versions/*/module-info.class")
    transform<PreserveFirstFoundResourceTransformer> {
        include(
            "kotlin/annotation/annotation.kotlin_builtins",
            "kotlin/collections/collections.kotlin_builtins",
            "kotlin/concurrent/atomics/atomics.kotlin_builtins",
            "kotlin/coroutines/coroutines.kotlin_builtins",
            "kotlin/internal/internal.kotlin_builtins",
            "kotlin/kotlin.kotlin_builtins",
            "kotlin/ranges/ranges.kotlin_builtins",
            "kotlin/reflect/reflect.kotlin_builtins",
        )
    }
    failOnDuplicateEntries = true
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}

tasks.shadowJar { configureCliArchive("all") }

val shrunkCliJar by
    tasks.registering(ShadowJar::class) {
        description = "Build the R8-shrunk native-distribution CLI JAR"
        configureCliArchive("shrunk")
        minimize {
            r8 { keepRuleFiles.from(layout.projectDirectory.file("gradle/r8/shrunk-cli.pro")) }
        }
    }

val normalizedCliJarTimestampMillis = 1_700_000_000_000L

val normalizedCliJar by
    tasks.registering(NormalizedJar::class) {
        description = "Build the metadata-normalized application JAR used by native distributions"
        from(shrunkCliJar.flatMap(ShadowJar::getArchiveFile).map(::zipTree)) {
            exclude("META-INF/MANIFEST.MF")
        }
        archiveFileName.set("indexino-cli.jar")
        destinationDirectory.set(layout.buildDirectory.dir("native-distributions/application"))
        manifest { attributes["Main-Class"] = cliMainClass }
        isReproducibleFileOrder = true
        isPreserveFileTimestamps = false
        normalizedTimestampMillis.set(normalizedCliJarTimestampMillis)
        outputs.cacheIf("filesystem mtime is part of the AOT input contract") { false }
    }

val nativeDistributionPinsFile =
    layout.projectDirectory.file("gradle/native-distributions.properties")
val nativeDistributionPins =
    providers
        .fileContents(nativeDistributionPinsFile)
        .asText
        .map { contents -> Properties().apply { contents.reader().use(::load) } }
        .get()

fun nativeDistributionPin(name: String) =
    requireNotNull(nativeDistributionPins.getProperty(name)) {
        "Missing native distribution pin '$name'"
    }

construo {
    name.set("indexino")
    humanName.set("Indexino")
    mainClass.set(cliMainClass)
    jarTask.set(normalizedCliJar.map { it.name })
    zipFolder.set("indexino")
    packageFiles.put("licenses/indexino-LICENSE", layout.projectDirectory.file("LICENSE"))
    jlink {
        modules.addAll("jdk.compiler", "jdk.unsupported", "jdk.crypto.ec")
        guessModulesFromJar.set(true)
        includeDefaultCryptoModules.set(true)
    }
    roast {
        version.set(nativeDistributionPin("roast.version"))
        runOnFirstThread.set(true)
        useZgc.set(false)
        vmArgs.add("--enable-native-access=ALL-UNNAMED")
    }
    targets {
        create<Target.Linux>("linuxX64") {
            architecture.set(Target.Architecture.X86_64)
            jdkUrl.set(nativeDistributionPin("linuxX64.jdkUrl"))
            jdkSha256.set(nativeDistributionPin("linuxX64.jdkSha256"))
            roastSha256.set(nativeDistributionPin("linuxX64.roastSha256"))
            packagingToolJdk.set(Target.PackagingToolJdk.TARGET_JDK)
            archiveFile.set(
                layout.buildDirectory.file("distributions/indexino-$version-linux-x64.zip")
            )
        }
        create<Target.MacOs>("macArm64") {
            architecture.set(Target.Architecture.AARCH64)
            jdkUrl.set(nativeDistributionPin("macArm64.jdkUrl"))
            jdkSha256.set(nativeDistributionPin("macArm64.jdkSha256"))
            roastSha256.set(nativeDistributionPin("macArm64.roastSha256"))
            packagingToolJdk.set(Target.PackagingToolJdk.TARGET_JDK)
            archiveFile.set(
                layout.buildDirectory.file("distributions/indexino-$version-macos-arm64.zip")
            )
            appBundle.set(false)
        }
        create<Target.Windows>("windowsX64") {
            architecture.set(Target.Architecture.X86_64)
            jdkUrl.set(nativeDistributionPin("windowsX64.jdkUrl"))
            jdkSha256.set(nativeDistributionPin("windowsX64.jdkSha256"))
            roastSha256.set(nativeDistributionPin("windowsX64.roastSha256"))
            packagingToolJdk.set(Target.PackagingToolJdk.TARGET_JDK)
            archiveFile.set(
                layout.buildDirectory.file("distributions/indexino-$version-windows-x64.zip")
            )
            useConsole.set(true)
            useGpuHint.set(false)
        }
    }
}

val verifyNativeDistributionConfiguration by tasks.registering {
    group = "verification"
    description = "Verify the configured Tier 1 native distribution model"
    doLast {
        val extension = project.extensions.getByType(ConstruoPluginExtension::class.java)
        val expectedTargets = setOf("linuxX64", "macArm64", "windowsX64")
        check(extension.targets.names == expectedTargets) {
            "Expected Tier 1 targets $expectedTargets, got ${extension.targets.names}"
        }
        check(extension.jarTask.get() == normalizedCliJar.name) {
            "Native packaging must consume the normalized CLI JAR"
        }
        check(extension.zipFolder.get() == "indexino") {
            "Native archives must use the flat indexino/ installation root"
        }
        check(
            extension.jlink.modules
                .get()
                .containsAll(listOf("jdk.compiler", "jdk.unsupported", "jdk.crypto.ec"))
        )
        check(extension.roast.version.get() == nativeDistributionPin("roast.version"))
        check(extension.roast.runOnFirstThread.get()) { "Roast must run on the first thread" }
        check(!extension.roast.useZgc.get()) { "Short-lived CLI launchers must not enable ZGC" }
        check("--enable-native-access=ALL-UNNAMED" in extension.roast.vmArgs.get()) {
            "Production and future AOT training must share the native-access VM option"
        }

        val expectedSuffixes =
            mapOf(
                "linuxX64" to "linux-x64",
                "macArm64" to "macos-arm64",
                "windowsX64" to "windows-x64",
            )
        expectedSuffixes.forEach { (targetName, suffix) ->
            val target = extension.targets.named(targetName).get()
            check(target.jdkUrl.get() == nativeDistributionPin("$targetName.jdkUrl"))
            check(target.jdkSha256.get() == nativeDistributionPin("$targetName.jdkSha256"))
            check(target.roastSha256.get() == nativeDistributionPin("$targetName.roastSha256"))
            check(target.packagingToolJdk.get() == Target.PackagingToolJdk.TARGET_JDK)
            check(target.archiveFile.get().asFile.name == "indexino-$version-$suffix.zip")
            val capitalized = targetName.replaceFirstChar(Char::uppercase)
            val packageTask = tasks.named("package$capitalized").get() as PackageTask
            check(packageTask.archiveFile.get() == target.archiveFile.get())
            val roastTask = tasks.named("roast$capitalized").get() as RoastTask
            check(roastTask.jarFile.get() == normalizedCliJar.get().archiveFile.get())
        }

        val mac = extension.targets.named("macArm64").get() as Target.MacOs
        val windows = extension.targets.named("windowsX64").get() as Target.Windows
        check(!mac.appBundle.get()) { "macOS CLI distributions must use the flat raw layout" }
        check(windows.useConsole.get()) { "Windows CLI distributions require a console launcher" }
        check(!windows.useGpuHint.get()) { "The Windows CLI launcher must not request a GPU" }
    }
}

shadow { addShadowVariantIntoJavaComponent = false }

val testMavenRepository = layout.buildDirectory.dir("test-maven-repository")
val publicationArtifactId = providers.gradleProperty("POM_ARTIFACT_ID")
val publicationGroupId = group.toString()
val publicationVersion = version.toString()

mavenPublishing {
    publishToMavenCentral(automaticRelease = false)
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))

    val hasSigningKey = providers.gradleProperty("signingInMemoryKey").orNull?.isNotBlank() == true
    if (hasSigningKey) {
        signAllPublications()
    }
}

publishing {
    repositories {
        maven {
            name = "Test"
            url = uri(testMavenRepository)
        }
    }
}

val cleanTestMavenRepository by tasks.registering(Delete::class) { delete(testMavenRepository) }

tasks
    .matching { it.name.startsWith("publish") && it.name.endsWith("PublicationToTestRepository") }
    .configureEach { dependsOn(cleanTestMavenRepository) }

tasks.build { dependsOn(tasks.shadowJar) }

tasks.register<JavaExec>("smokeSelectionWalker") {
    group = "verification"
    description = "Run SelectionWalker against intellij-community (pass path as first arg)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.sebastiano.indexino.smoke.SelectionWalkerSmokeKt")
    systemProperty("idea.home.path", ideaHomeDir.absolutePath)
    systemProperty("idea.config.path", ideaHomeDir.resolve("config").absolutePath)
    systemProperty("idea.system.path", ideaHomeDir.resolve("system").absolutePath)
    systemProperty("idea.plugins.path", ideaHomeDir.resolve("plugins").absolutePath)
    if (project.hasProperty("intellijCommunityPath")) {
        args = listOf(project.property("intellijCommunityPath") as String)
    }
}

val ideaHomeDir =
    layout.buildDirectory.dir("idea-home").get().asFile.apply {
        resolve("config").mkdirs()
        resolve("system").mkdirs()
        resolve("plugins").mkdirs()
    }

tasks.test {
    useJUnitPlatform {
        val excludedTags =
            mutableListOf("construo-contract", "distribution", "native-distribution", "publication")
        if (!project.hasProperty("liveTests")) {
            excludedTags += "live"
        }
        excludeTags(*excludedTags.toTypedArray())
    }
    systemProperty("idea.home.path", ideaHomeDir.absolutePath)
    systemProperty("idea.config.path", ideaHomeDir.resolve("config").absolutePath)
    systemProperty("idea.system.path", ideaHomeDir.resolve("system").absolutePath)
    systemProperty("idea.plugins.path", ideaHomeDir.resolve("plugins").absolutePath)
}

val verifyShrunkCli by
    tasks.registering(Test::class) {
        group = "verification"
        description = "Exercise the complete CLI workload through the R8-shrunk JAR"
        dependsOn(shrunkCliJar, tasks.shadowJar)
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        inputs
            .file(shrunkCliJar.flatMap(ShadowJar::getArchiveFile))
            .withPropertyName("shrunkCliJar")
        inputs
            .file(tasks.shadowJar.flatMap(ShadowJar::getArchiveFile))
            .withPropertyName("unshrunkCliJar")
        useJUnitPlatform { includeTags("distribution") }
        systemProperty(
            "indexino.shrunkJar",
            shrunkCliJar.flatMap(ShadowJar::getArchiveFile).get().asFile.absolutePath,
        )
        systemProperty(
            "indexino.unshrunkJar",
            tasks.shadowJar.flatMap(ShadowJar::getArchiveFile).get().asFile.absolutePath,
        )
    }

val verifyConstruoContract by
    tasks.registering(Test::class) {
        group = "verification"
        description =
            "Verify the released Construo API and archive contract used by native distributions"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        dependsOn(normalizedCliJar, verifyNativeDistributionConfiguration)
        inputs.file(nativeDistributionPinsFile).withPropertyName("nativeDistributionPins")
        inputs
            .file(normalizedCliJar.flatMap(NormalizedJar::getArchiveFile))
            .withPropertyName("normalizedCliJar")
        useJUnitPlatform { includeTags("construo-contract") }
        systemProperty("indexino.construoVersion", libs.versions.construo.get())
        systemProperty(
            "indexino.nativeDistributionPins",
            nativeDistributionPinsFile.asFile.absolutePath,
        )
        systemProperty(
            "indexino.normalizedCliJar",
            normalizedCliJar.flatMap(NormalizedJar::getArchiveFile).get().asFile.absolutePath,
        )
    }

fun registerNativeDistributionVerification(
    targetName: String,
    taskSuffix: String,
    artifactSuffix: String,
) =
    tasks.register<Test>("verifyNativeDistribution$taskSuffix") {
        group = "verification"
        description = "Verify the $artifactSuffix native distribution"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        dependsOn("package$taskSuffix")
        val archive =
            layout.buildDirectory.file("distributions/indexino-$version-$artifactSuffix.zip")
        val targetJdkRoot = layout.buildDirectory.dir("construo/jdk/$targetName")
        val executableExtension = if (targetName == "windowsX64") ".exe" else ""
        inputs.file(archive).withPropertyName("nativeArchive")
        inputs
            .files(
                targetJdkRoot.map { it.file("bin/jlink$executableExtension") },
                targetJdkRoot.map { it.file("bin/jdeps$executableExtension") },
                targetJdkRoot.map { it.file("bin/javap$executableExtension") },
            )
            .withPropertyName("targetPackagingTools")
        useJUnitPlatform { includeTags("native-distribution") }
        systemProperty("indexino.nativeArchive", archive.get().asFile.absolutePath)
        systemProperty("indexino.nativeTarget", artifactSuffix)
        systemProperty("indexino.targetJdkRoot", targetJdkRoot.get().asFile.absolutePath)
        systemProperty("indexino.expectedJbrVersion", nativeDistributionPin("jbr.version"))
    }

val verifyNativeDistributionLinuxX64 =
    registerNativeDistributionVerification("linuxX64", "LinuxX64", "linux-x64")
val verifyNativeDistributionMacArm64 =
    registerNativeDistributionVerification("macArm64", "MacArm64", "macos-arm64")
val verifyNativeDistributionWindowsX64 =
    registerNativeDistributionVerification("windowsX64", "WindowsX64", "windows-x64")

val verifyMavenPublication by
    tasks.registering(Test::class) {
        group = "verification"
        description = "Verify the thin Maven publication and Central-required metadata"
        dependsOn("publishAllPublicationsToTestRepository")
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        inputs.dir(testMavenRepository).withPropertyName("testMavenRepository")
        useJUnitPlatform { includeTags("publication") }
        systemProperty(
            "indexino.publicationDirectory",
            testMavenRepository
                .get()
                .dir(
                    "${publicationGroupId.replace('.', '/')}/${publicationArtifactId.get()}/" +
                        publicationVersion
                )
                .asFile
                .absolutePath,
        )
        systemProperty("indexino.publicationGroup", publicationGroupId)
        systemProperty("indexino.publicationArtifact", publicationArtifactId.get())
        systemProperty("indexino.publicationVersion", publicationVersion)
    }

tasks.check {
    dependsOn("detektMain", "detektTest", "ktfmtCheckMain", "ktfmtCheckScripts", "ktfmtCheckTest")
}
