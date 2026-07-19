// SPDX-License-Identifier: UEL-1.0

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.DetektCreateBaselineTask
import java.util.jar.JarFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.tasks.Delete
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.shadow)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.maven.publish)
}

group = providers.gradleProperty("GROUP").get()

version = providers.gradleProperty("VERSION_NAME").get()

kotlin { jvmToolchain(21) }

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

repositories { mavenCentral() }

dependencies {
    implementation(libs.kotlin.compiler.embeddable)
    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xodus.environment)
    implementation(libs.slf4j.nop)

    testImplementation(kotlin("test"))
}

application { mainClass.set("com.kotlincodeindex.cli.MainCommandKt") }

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
}

shadow { addShadowVariantIntoJavaComponent = false }

val testMavenRepository = layout.buildDirectory.dir("test-maven-repository")
val publicationArtifactId = providers.gradleProperty("POM_ARTIFACT_ID")

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
    mainClass.set("com.kotlincodeindex.smoke.SelectionWalkerSmokeKt")
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
        if (!project.hasProperty("liveTests")) {
            excludeTags("live")
        }
    }
    systemProperty("idea.home.path", ideaHomeDir.absolutePath)
    systemProperty("idea.config.path", ideaHomeDir.resolve("config").absolutePath)
    systemProperty("idea.system.path", ideaHomeDir.resolve("system").absolutePath)
    systemProperty("idea.plugins.path", ideaHomeDir.resolve("plugins").absolutePath)
}

tasks.check {
    dependsOn("detektMain", "detektTest", "ktfmtCheckMain", "ktfmtCheckScripts", "ktfmtCheckTest")
}

val verifyMavenPublication by tasks.registering {
    group = "verification"
    description = "Verify the thin Maven publication and Central-required metadata"
    dependsOn("publishAllPublicationsToTestRepository")

    outputs.upToDateWhen { false }

    doLast {
        val groupId = project.group.toString()
        val artifactId = publicationArtifactId.get()
        val publicationVersion = project.version.toString()
        val artifactDirectory =
            testMavenRepository
                .get()
                .asFile
                .resolve(groupId.replace('.', '/'))
                .resolve(artifactId)
                .resolve(publicationVersion)
        val publishedFiles = artifactDirectory.listFiles().orEmpty().filter { it.isFile }

        fun requireSingleArtifact(label: String, predicate: (String) -> Boolean) =
            publishedFiles.filter { predicate(it.name) }.singleOrNull()
                ?: error("Expected one $label in $artifactDirectory")

        val mainJar =
            requireSingleArtifact("thin JAR") {
                it.endsWith(".jar") &&
                    !it.endsWith("-sources.jar") &&
                    !it.endsWith("-javadoc.jar") &&
                    !it.endsWith("-all.jar")
            }
        val sourcesJar = requireSingleArtifact("sources JAR") { it.endsWith("-sources.jar") }
        requireSingleArtifact("javadoc JAR") { it.endsWith("-javadoc.jar") }
        val pomFile = requireSingleArtifact("POM") { it.endsWith(".pom") }
        requireSingleArtifact("Gradle module metadata") { it.endsWith(".module") }

        check(publishedFiles.none { it.name.endsWith("-all.jar") }) {
            "The fat Shadow JAR must not be part of the Maven publication"
        }

        JarFile(mainJar).use { jar ->
            check(jar.getEntry("com/kotlincodeindex/cli/MainCommandKt.class") != null) {
                "The thin JAR is missing the kotlin-code-index CLI"
            }
            val forbiddenBundledEntries =
                listOf(
                    "com/github/ajalt/clikt/core/CliktCommand.class",
                    "jetbrains/exodus/Environment.class",
                    "kotlin/collections/CollectionsKt.class",
                )
            val bundledDependencies = forbiddenBundledEntries.filter { jar.getEntry(it) != null }
            check(bundledDependencies.isEmpty()) {
                "The Maven JAR bundles dependencies and is not thin: ${bundledDependencies.joinToString()}"
            }
        }

        JarFile(sourcesJar).use { jar ->
            check(jar.getEntry("com/kotlincodeindex/cli/MainCommand.kt") != null) {
                "The sources JAR is missing kotlin-code-index sources"
            }
        }

        val documentBuilderFactory = DocumentBuilderFactory.newInstance()
        documentBuilderFactory.setFeature(
            "http://apache.org/xml/features/disallow-doctype-decl",
            true,
        )
        documentBuilderFactory.setFeature(
            "http://xml.org/sax/features/external-general-entities",
            false,
        )
        documentBuilderFactory.setFeature(
            "http://xml.org/sax/features/external-parameter-entities",
            false,
        )
        val project = documentBuilderFactory.newDocumentBuilder().parse(pomFile).documentElement

        fun directChild(parent: Element, name: String): Element? {
            val children = parent.childNodes
            for (index in 0 until children.length) {
                val child = children.item(index)
                if (child is Element && child.tagName == name) return child
            }
            return null
        }

        fun requireText(parent: Element, name: String): String {
            val value = directChild(parent, name)?.textContent?.trim().orEmpty()
            check(value.isNotEmpty()) { "Published POM is missing <$name>" }
            return value
        }

        check(requireText(project, "groupId") == groupId)
        check(requireText(project, "artifactId") == artifactId)
        check(requireText(project, "version") == publicationVersion)
        requireText(project, "name")
        requireText(project, "description")
        requireText(project, "url")
        check(directChild(project, "licenses") != null) { "Published POM is missing <licenses>" }
        check(directChild(project, "scm") != null) { "Published POM is missing <scm>" }
        check(directChild(project, "developers") != null) {
            "Published POM is missing <developers>"
        }
        check(directChild(project, "dependencies") != null) {
            "Published POM must declare the thin JAR's runtime dependencies"
        }
    }
}
