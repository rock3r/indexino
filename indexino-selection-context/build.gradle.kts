import java.io.File

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    `java-library`
    `maven-publish`
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
    api(project(":indexino-plugin-api"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation(libs.kotlin.compiler.embeddable)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

val metalava by configurations.creating

dependencies { metalava(libs.metalava) }

val metalavaOutput = layout.buildDirectory.file("metalava/indexino-selection-context-current.txt")
val reviewedMetalavaSignature =
    rootProject.layout.projectDirectory.file("api/indexino-selection-context/current.txt")
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
            "Generated signature differs from api/indexino-selection-context/current.txt"
        }
    }
}

tasks.named("check") { dependsOn(metalavaCheckSignature) }

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "indexino-selection-context"
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "Test"
            url = uri(rootProject.layout.buildDirectory.dir("test-maven-repository"))
        }
    }
}
