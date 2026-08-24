plugins {
    kotlin("jvm") version "2.0.21"
}

group = "org.jetbrains.skiko"
version = "0.8.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
}

kotlin {
    jvmToolchain(17)
}
