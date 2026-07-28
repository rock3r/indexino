import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.toolchain.JavaLanguageVersion

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
    testImplementation(kotlin("test"))
}

val metalava by configurations.creating

dependencies { metalava(libs.metalava) }

val metalavaOutput = layout.buildDirectory.file("metalava/indexino-model-current.txt")
val reviewedMetalavaSignature =
    rootProject.layout.projectDirectory.file("api/indexino-model/current.txt")
val jdk25Launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }

val metalavaGenerateSignature by
    tasks.registering(JavaExec::class) {
        group = "verification"
        description = "Generate the indexino-model Metalava signature"
        dependsOn(tasks.named("classes"))
        classpath = metalava
        mainClass.set("com.android.tools.metalava.Driver")
        javaLauncher.set(jdk25Launcher)
        inputs.files(sourceSets.main.map { it.allSource })
        inputs.files(sourceSets.main.map { it.compileClasspath })
        inputs.files(sourceSets.main.map { it.output })
        outputs.file(metalavaOutput)

        doFirst {
            val jdkHome = jdk25Launcher.get().metadata.installationPath.asFile
            logger.lifecycle("[metalava] jdk-home=${jdkHome.absolutePath}")
            args =
                listOf(
                    "--source-path",
                    layout.projectDirectory.dir("src/main/kotlin").asFile.absolutePath,
                    "--classpath",
                    sourceSets.main.get().compileClasspath.asPath,
                    "--jdk-home",
                    jdkHome.absolutePath,
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
                    // Caller-supplied closed modes intentionally use enums; engine-produced values
                    // do not.
                    "--hide",
                    "Enum",
                )
        }
    }

val metalavaUpdateSignature by tasks.registering {
    group = "verification"
    description =
        "Copy the generated indexino-model signature into the reviewed api/indexino-model/current.txt"
    dependsOn(metalavaGenerateSignature)
    inputs.file(metalavaOutput)
    outputs.file(reviewedMetalavaSignature)

    doLast {
        val generated = metalavaOutput.get().asFile
        val reviewed = reviewedMetalavaSignature.asFile
        reviewed.parentFile.mkdirs()
        reviewed.writeText(generated.readText())
    }
}

val metalavaCheckSignature by tasks.registering {
    group = "verification"
    description = "Check the generated indexino-model signature against the reviewed signature"
    dependsOn(metalavaGenerateSignature)
    mustRunAfter(metalavaUpdateSignature)
    inputs.file(reviewedMetalavaSignature)
    inputs.files(metalavaGenerateSignature)

    doLast {
        val generated = metalavaOutput.get().asFile.readText()
        val reviewed = reviewedMetalavaSignature.asFile.readText()
        check(generated == reviewed) {
            "Generated indexino-model signature differs from api/indexino-model/current.txt. " +
                "Review the diff, then run :indexino-model:metalavaUpdateSignature if intentional."
        }
    }
}

tasks.named("check") { dependsOn(metalavaCheckSignature) }

mavenPublishing {
    coordinates(group.toString(), "indexino-model", version.toString())
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
