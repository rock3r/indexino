// SPDX-License-Identifier: UEL-1.0

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.jengelman.gradle.plugins.shadow.transformers.PreserveFirstFoundResourceTransformer
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.DetektCreateBaselineTask
import dev.sebastiano.indexino.buildlogic.AotTrainingTask
import dev.sebastiano.indexino.buildlogic.MacDittoArchive
import dev.sebastiano.indexino.buildlogic.NormalizedJar
import io.github.fourlastor.construo.Target
import io.github.fourlastor.construo.task.PackageTask
import io.github.fourlastor.construo.task.jvm.CreateRuntimeImageTask
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Sync
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
    implementation(libs.jna)

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
        inputJar.set(shrunkCliJar.flatMap(ShadowJar::getArchiveFile))
        archiveFileName.set("indexino-cli.jar")
        destinationDirectory.set(layout.buildDirectory.dir("native-distributions/application"))
        normalizedTimestampMillis.set(normalizedCliJarTimestampMillis)
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

val nativeVmArgs = listOf("--enable-native-access=ALL-UNNAMED")
val aotTrainingFixture = layout.projectDirectory.dir("gradle/aot-training/fixture")
val aotTrainingArguments =
    listOf(
        "index",
        "--project",
        "training-workspace",
        "--build-system",
        "gradle",
        "--gradle-module",
        ":app",
        "--applications",
        "selection-context",
    )

fun registerAotTraining(
    taskSuffix: String,
    targetOs: String,
    targetArchitecture: String,
    jbrDigest: String,
    javaExecutableName: String,
) =
    tasks.register<AotTrainingTask>("trainAot$taskSuffix") {
        group = "distribution"
        description = "Train the $targetOs-$targetArchitecture application AOT cache"
        val runtimeTask = tasks.named<CreateRuntimeImageTask>("createRuntimeImage$taskSuffix")
        runtimeImage.set(runtimeTask.flatMap { it.output })
        targetJdkRoot.set(runtimeTask.flatMap { it.jdkRoot })
        applicationJar.set(normalizedCliJar.flatMap(NormalizedJar::getArchiveFile))
        trainingFixture.set(aotTrainingFixture)
        aotCache.set(
            layout.buildDirectory.file(
                "native-distributions/aot/${targetOs}-${targetArchitecture}/classes.jsa"
            )
        )
        this.targetOs.set(targetOs)
        this.targetArchitecture.set(targetArchitecture)
        this.jbrDigest.set(jbrDigest)
        normalizedJarTimestampMillis.set(normalizedCliJarTimestampMillis)
        modules.set(runtimeTask.flatMap { it.modules })
        mainClassName.set(cliMainClass)
        classPath.set(
            normalizedCliJar.flatMap(NormalizedJar::getArchiveFile).map { it.asFile.name }
        )
        roastWorkingDirectory.set(".")
        fixtureVersion.set("1")
        vmArgs.set(nativeVmArgs)
        trainingArguments.set(aotTrainingArguments)
        minimumHeap.set("128m")
        maximumHeap.set("1024m")
        this.javaExecutableName.set(javaExecutableName)
        gitExecutable.set("git")
        environmentVariables.put("PATH", providers.environmentVariable("PATH").orElse(""))
        environmentVariables.put(
            "SystemRoot",
            providers.environmentVariable("SystemRoot").orElse(""),
        )
        environmentVariables.put("WINDIR", providers.environmentVariable("WINDIR").orElse(""))
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
        vmArgs.addAll(nativeVmArgs)
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
            val aotTraining =
                registerAotTraining(
                    taskSuffix = "LinuxX64",
                    targetOs = "linux",
                    targetArchitecture = "x64",
                    jbrDigest = nativeDistributionPin("linuxX64.jdkSha256"),
                    javaExecutableName = "java",
                )
            packageFiles.put(
                "runtime/lib/server/classes.jsa",
                aotTraining.flatMap(AotTrainingTask::getAotCache),
            )
        }
        create<Target.MacOs>("macArm64") {
            architecture.set(Target.Architecture.AARCH64)
            jdkUrl.set(nativeDistributionPin("macArm64.jdkUrl"))
            jdkSha256.set(nativeDistributionPin("macArm64.jdkSha256"))
            roastSha256.set(nativeDistributionPin("macArm64.roastSha256"))
            packagingToolJdk.set(Target.PackagingToolJdk.TARGET_JDK)
            archiveFile.set(
                layout.buildDirectory.file(
                    "native-distributions/raw/indexino-$version-macos-arm64.zip"
                )
            )
            appBundle.set(false)
            val aotTraining =
                registerAotTraining(
                    taskSuffix = "MacArm64",
                    targetOs = "macos",
                    targetArchitecture = "arm64",
                    jbrDigest = nativeDistributionPin("macArm64.jdkSha256"),
                    javaExecutableName = "java",
                )
            packageFiles.put(
                "runtime/lib/server/classes.jsa",
                aotTraining.flatMap(AotTrainingTask::getAotCache),
            )
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
            val aotTraining =
                registerAotTraining(
                    taskSuffix = "WindowsX64",
                    targetOs = "windows",
                    targetArchitecture = "x64",
                    jbrDigest = nativeDistributionPin("windowsX64.jdkSha256"),
                    javaExecutableName = "java.exe",
                )
            packageFiles.put(
                "runtime/bin/server/classes.jsa",
                aotTraining.flatMap(AotTrainingTask::getAotCache),
            )
        }
    }
}

val finalizedMacArm64Archive by
    tasks.registering(MacDittoArchive::class) {
        group = "distribution"
        description = "Finalize the macOS arm64 ZIP with ditto-compatible JAR metadata"
        inputArchive.set(tasks.named<PackageTask>("packageMacArm64").flatMap { it.archiveFile })
        normalizedJar.set(normalizedCliJar.flatMap(NormalizedJar::getArchiveFile))
        aotCache.set(tasks.named<AotTrainingTask>("trainAotMacArm64").flatMap { it.aotCache })
        dittoExecutable.set("/usr/bin/ditto")
        outputArchive.set(
            layout.buildDirectory.file("distributions/indexino-$version-macos-arm64.zip")
        )
    }

tasks.named<PackageTask>("packageMacArm64") { finalizedBy(finalizedMacArm64Archive) }

val restrictedMacAotCacheDirectory =
    layout.buildDirectory.dir("tmp/restrictedMacAotCacheForVerification")
val prepareRestrictedMacAotCacheForVerification by
    tasks.registering(Sync::class) {
        from(tasks.named<AotTrainingTask>("trainAotMacArm64").flatMap { it.aotCache })
        into(restrictedMacAotCacheDirectory)
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("Verification requires a restrictive source mode") { true }
        doLast {
            Files.setPosixFilePermissions(
                restrictedMacAotCacheDirectory.get().file("classes.jsa").asFile.toPath(),
                PosixFilePermissions.fromString("rw-------"),
            )
        }
    }

val restrictedMacArchiveForVerification by
    tasks.registering(MacDittoArchive::class) {
        group = "verification"
        description = "Verify macOS archive modes from a restrictively permissioned AOT cache"
        inputArchive.set(tasks.named<PackageTask>("packageMacArm64").flatMap { it.archiveFile })
        normalizedJar.set(normalizedCliJar.flatMap(NormalizedJar::getArchiveFile))
        aotCache.set(
            prepareRestrictedMacAotCacheForVerification.map {
                restrictedMacAotCacheDirectory.get().file("classes.jsa")
            }
        )
        dittoExecutable.set("/usr/bin/ditto")
        outputArchive.set(
            layout.buildDirectory.file(
                "tmp/restrictedMacArchiveForVerification/indexino-macos-arm64.zip"
            )
        )
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
            mutableListOf(
                "aot-training-contract",
                "construo-contract",
                "distribution",
                "native-distribution",
                "publication",
            )
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
        dependsOn(normalizedCliJar)
        val normalizedJarSource =
            layout.projectDirectory.file(
                "buildSrc/src/main/java/dev/sebastiano/indexino/buildlogic/NormalizedJar.java"
            )
        val macPackageFinalizers =
            tasks.named<PackageTask>("packageMacArm64").map { packageTask ->
                packageTask.finalizedBy
                    .getDependencies(packageTask)
                    .map { it.name }
                    .sorted()
                    .joinToString(",")
            }
        inputs.file(nativeDistributionPinsFile).withPropertyName("nativeDistributionPins")
        inputs.file(normalizedJarSource).withPropertyName("normalizedJarSource")
        inputs
            .file(normalizedCliJar.flatMap(NormalizedJar::getArchiveFile))
            .withPropertyName("normalizedCliJar")
        inputs
            .file(shrunkCliJar.flatMap(ShadowJar::getArchiveFile))
            .withPropertyName("shrunkCliJar")
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
        systemProperty(
            "indexino.shrunkCliJar",
            shrunkCliJar.flatMap(ShadowJar::getArchiveFile).get().asFile.absolutePath,
        )
        systemProperty("indexino.normalizedJarSource", normalizedJarSource.asFile.absolutePath)
        systemProperty("indexino.projectDirectory", layout.projectDirectory.asFile.absolutePath)
        systemProperty("indexino.gradleUserHome", gradle.gradleUserHomeDir.absolutePath)
        systemProperty("indexino.macPackageFinalizers", macPackageFinalizers.get())
    }

val verifyAotTrainingContract by
    tasks.registering(Test::class) {
        group = "verification"
        description = "Verify target AOT training lifecycle and isolation contracts"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        val taskSource =
            layout.projectDirectory.file(
                "buildSrc/src/main/java/dev/sebastiano/indexino/buildlogic/AotTrainingTask.java"
            )
        inputs.file(taskSource).withPropertyName("aotTrainingTaskSource")
        useJUnitPlatform { includeTags("aot-training-contract") }
        systemProperty("indexino.aotTrainingTaskSource", taskSource.asFile.absolutePath)
    }

fun registerNativeDistributionVerification(
    taskSuffix: String,
    artifactSuffix: String,
): TaskProvider<Test> {
    val verificationReportDirectory =
        layout.buildDirectory.dir("reports/native-distributions/$artifactSuffix")
    val cleanVerificationReports =
        tasks.register<Delete>("cleanNativeDistributionReports$taskSuffix") {
            delete(verificationReportDirectory)
            setFollowSymlinks(false)
        }
    return tasks.register<Test>("verifyNativeDistribution$taskSuffix") {
        group = "verification"
        description = "Verify the $artifactSuffix native distribution"
        dependsOn(cleanVerificationReports)
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        val archive =
            if (taskSuffix == "MacArm64") {
                finalizedMacArm64Archive.flatMap(MacDittoArchive::getOutputArchive)
            } else {
                tasks.named<PackageTask>("package$taskSuffix").flatMap { it.archiveFile }
            }
        val targetJdkRoot =
            tasks.named<CreateRuntimeImageTask>("createRuntimeImage$taskSuffix").flatMap {
                it.jdkRoot
            }
        val targetRuntimeImage =
            tasks.named<CreateRuntimeImageTask>("createRuntimeImage$taskSuffix").flatMap {
                it.output
            }
        val normalizedApplicationJar = normalizedCliJar.flatMap(NormalizedJar::getArchiveFile)
        val aotCache = tasks.named<AotTrainingTask>("trainAot$taskSuffix").flatMap { it.aotCache }
        val thinApplicationJar = tasks.jar.flatMap { it.archiveFile }
        val unshrunkApplicationJar = tasks.shadowJar.flatMap { it.archiveFile }
        val r8ApplicationJar = shrunkCliJar.flatMap { it.archiveFile }
        val thinRuntimeClasspath = files(thinApplicationJar, runtimeClasspathConfiguration)
        val executableExtension = if (artifactSuffix == "windows-x64") ".exe" else ""
        inputs.file(archive).withPropertyName("nativeArchive")
        inputs.file(normalizedApplicationJar).withPropertyName("normalizedApplicationJar")
        inputs.file(aotCache).withPropertyName("aotCache")
        inputs.file(thinApplicationJar).withPropertyName("thinApplicationJar")
        inputs.files(runtimeClasspathConfiguration).withPropertyName("thinRuntimeDependencies")
        inputs.file(unshrunkApplicationJar).withPropertyName("unshrunkApplicationJar")
        inputs.file(r8ApplicationJar).withPropertyName("r8ApplicationJar")
        outputs.dir(verificationReportDirectory).withPropertyName("verificationReports")
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("Verification depends on matching-host native behavior") { true }
        doFirst { systemProperty("indexino.thinRuntimeClasspath", thinRuntimeClasspath.asPath) }
        if (taskSuffix == "MacArm64") {
            val restrictedArchive =
                restrictedMacArchiveForVerification.flatMap(MacDittoArchive::getOutputArchive)
            inputs.file(restrictedArchive).withPropertyName("restrictedMacArchive")
            systemProperty(
                "indexino.restrictedMacArchive",
                restrictedArchive.get().asFile.absolutePath,
            )
        }
        inputs.dir(targetRuntimeImage).withPropertyName("targetRuntimeImage")
        inputs.file(layout.projectDirectory.file("LICENSE")).withPropertyName("applicationLicense")
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
        systemProperty("indexino.targetRuntimeImage", targetRuntimeImage.get().asFile.absolutePath)
        systemProperty(
            "indexino.normalizedApplicationJar",
            normalizedApplicationJar.get().asFile.absolutePath,
        )
        systemProperty("indexino.aotCache", aotCache.get().asFile.absolutePath)
        systemProperty("indexino.unshrunkJar", unshrunkApplicationJar.get().asFile.absolutePath)
        systemProperty("indexino.r8Jar", r8ApplicationJar.get().asFile.absolutePath)
        systemProperty("indexino.version", version.toString())
        systemProperty(
            "indexino.verificationReportDirectory",
            verificationReportDirectory.get().asFile.absolutePath,
        )
        systemProperty("indexino.expectedJbrVersion", nativeDistributionPin("jbr.version"))
        systemProperty(
            "indexino.macFinalizerStaging",
            layout.buildDirectory
                .dir("tmp/finalizedMacArm64Archive/staging")
                .get()
                .asFile
                .absolutePath,
        )
        systemProperty(
            "indexino.applicationLicense",
            layout.projectDirectory.file("LICENSE").asFile.absolutePath,
        )
    }
}

registerNativeDistributionVerification("LinuxX64", "linux-x64")

registerNativeDistributionVerification("MacArm64", "macos-arm64")

registerNativeDistributionVerification("WindowsX64", "windows-x64")

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
