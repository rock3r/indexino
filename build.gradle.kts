// SPDX-License-Identifier: UEL-1.0

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.jengelman.gradle.plugins.shadow.transformers.PreserveFirstFoundResourceTransformer
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.DetektCreateBaselineTask
import dev.sebastiano.indexino.buildlogic.NormalizedJar
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

construo { jarTask.set(normalizedCliJar.map { it.name }) }

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
        val excludedTags = mutableListOf("construo-contract", "distribution", "publication")
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

val nativeDistributionPinsFile =
    layout.projectDirectory.file("gradle/native-distributions.properties")

val verifyConstruoContract by
    tasks.registering(Test::class) {
        group = "verification"
        description =
            "Verify the released Construo API and archive contract used by native distributions"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        dependsOn(normalizedCliJar)
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
