// SPDX-License-Identifier: UEL-1.0

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.shadow)
}

group = "com.kotlincodeindex"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

sourceSets.main {
    resources.srcDir("config")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlin.compiler.embeddable)
    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xodus.environment)

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.kotlincodeindex.cli.MainKt")
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

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

val ideaHomeDir = layout.buildDirectory.dir("idea-home").get().asFile.apply {
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
