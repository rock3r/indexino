plugins {
    kotlin("jvm") version "2.0.21"
}

group = "org.jetbrains.skia"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
}

kotlin {
    jvmToolchain(17)
}
