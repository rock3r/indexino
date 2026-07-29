import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar
import java.io.File
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
    api(project(":"))
    api(project(":indexino-model"))
    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.jvm.host)
    implementation(libs.kotlin.scripting.compiler.embeddable)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

mavenPublishing {
    coordinates(group.toString(), "indexino-script-host", version.toString())
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

val metalava by configurations.creating

dependencies { metalava(libs.metalava) }

val metalavaOutput = layout.buildDirectory.file("metalava/indexino-script-host-current.txt")
val reviewedMetalavaSignature =
    rootProject.layout.projectDirectory.file("api/indexino-script-host/current.txt")
val jdk25Launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }

val metalavaGenerateSignature by
    tasks.registering(JavaExec::class) {
        dependsOn(tasks.named("classes"))
        classpath = metalava
        mainClass.set("com.android.tools.metalava.Driver")
        javaLauncher.set(jdk25Launcher)
        inputs.files(sourceSets.main.map { it.allSource })
        inputs.files(sourceSets.main.map { it.compileClasspath })
        inputs.files(sourceSets.main.map { it.output })
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
        doLast {
            val output = metalavaOutput.get().asFile
            output.writeText(output.readText().trimEnd() + "\n")
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
            "Generated signature differs from api/indexino-script-host/current.txt"
        }
    }
}

tasks.named("check") { dependsOn(metalavaCheckSignature) }
