plugins {
    kotlin("jvm") version "__KOTLIN_VERSION__"
    application
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("dev.sebastiano.indexino:indexino:__INDEXINO_VERSION__")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    mainClass = "consumer.ConsumerProbeKt"
}
