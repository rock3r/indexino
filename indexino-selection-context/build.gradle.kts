plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktfmt)
    `java-library`
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
