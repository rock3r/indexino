plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktfmt)
}

kotlin {
    // Detekt loads this build-only plugin into the Gradle daemon. Target Gradle's minimum
    // supported runtime so contributors need not run the daemon itself on JDK 25.
    jvmToolchain(17)
    explicitApi()
}

repositories { mavenCentral() }

ktfmt { kotlinLangStyle() }

dependencies {
    compileOnly(libs.detekt.api)
    testImplementation(libs.detekt.api)
    testImplementation(libs.detekt.test)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
