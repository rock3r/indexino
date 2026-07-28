import com.vanniktech.maven.publish.JavaPlatform

plugins {
    `java-platform`
    alias(libs.plugins.maven.publish)
}

group = providers.gradleProperty("GROUP").get()

version = providers.gradleProperty("VERSION_NAME").get()

javaPlatform { allowDependencies() }

dependencies {
    constraints {
        api("$group:indexino-model:$version")
        api("$group:indexino:$version")
        api("$group:indexino-plugin-api:$version")
        api("$group:indexino-selection-context:$version")
    }
}

mavenPublishing {
    coordinates(group.toString(), "indexino-bom", version.toString())
    publishToMavenCentral(automaticRelease = false)
    configure(JavaPlatform())

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
