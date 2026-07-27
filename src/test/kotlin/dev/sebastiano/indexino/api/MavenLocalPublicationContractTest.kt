package dev.sebastiano.indexino.api

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Tag

@Tag("publication")
class MavenLocalPublicationContractTest {
    @Test
    fun `model publishes its own Maven Local coordinates`() {
        val projectDirectory = File(System.getProperty("user.dir"))
        val version = projectVersion(projectDirectory)
        val localRepository = Files.createTempDirectory("indexino-maven-local").toFile()
        val build = runCatching {
            GradleRunner.create()
                .withProjectDir(projectDirectory)
                .withArguments(
                    ":indexino-model:publishToMavenLocal",
                    "-Dmaven.repo.local=${localRepository.absolutePath}",
                    "--stacktrace",
                )
                .withEnvironment(mapOf("JAVA_HOME" to System.getProperty("java.home")))
                .build()
        }

        assertTrue(
            build.isSuccess,
            build.exceptionOrNull()?.message ?: "Maven Local publish failed",
        )
        val moduleDirectory =
            localRepository.resolve("dev/sebastiano/indexino/indexino-model/$version")
        assertTrue(
            moduleDirectory.resolve("indexino-model-$version.pom").isFile,
            "Model POM was not published under indexino-model coordinates",
        )
        assertTrue(
            moduleDirectory.resolve("indexino-model-$version.jar").isFile,
            "Model JAR was not published under indexino-model coordinates",
        )
    }

    @Test
    fun `facade publication declares the model dependency`() {
        val projectDirectory = File(System.getProperty("user.dir"))
        val version = projectVersion(projectDirectory)
        val localRepository = Files.createTempDirectory("indexino-facade-maven-local").toFile()
        val build = runCatching {
            GradleRunner.create()
                .withProjectDir(projectDirectory)
                .withArguments(
                    ":indexino-model:publishToMavenLocal",
                    ":publishToMavenLocal",
                    "-Dmaven.repo.local=${localRepository.absolutePath}",
                    "--stacktrace",
                )
                .withEnvironment(mapOf("JAVA_HOME" to System.getProperty("java.home")))
                .build()
        }

        assertTrue(
            build.isSuccess,
            build.exceptionOrNull()?.message ?: "Facade Maven Local publish failed",
        )
        val facadeDirectory = localRepository.resolve("dev/sebastiano/indexino/indexino/$version")
        val pom = facadeDirectory.resolve("indexino-$version.pom")
        assertTrue(pom.isFile, "Facade POM was not published")
        assertTrue(facadeDirectory.resolve("indexino-$version.jar").isFile)
        assertTrue(
            facadeDirectory.listFiles().orEmpty().none { it.name.endsWith(".module") },
            "Dogfood publication must not emit Gradle Module Metadata until the S5 split",
        )
        val pomText = pom.readText()
        assertTrue(pomText.contains("<artifactId>indexino-model</artifactId>"))
        assertTrue(pomText.contains("<version>$version</version>"))
        assertTrue(!pomText.contains("<artifactId>clikt-jvm</artifactId>"))
        assertTrue(!pomText.contains("<artifactId>jna</artifactId>"))
        assertTrue(!pomText.contains("<artifactId>slf4j-nop</artifactId>"))
    }

    @Test
    fun `published facade connects refreshes and queries from a consumer project`() {
        val projectDirectory = File(System.getProperty("user.dir"))
        val localRepository = Files.createTempDirectory("indexino-s1-consumer-repository").toFile()
        val publication =
            GradleRunner.create()
                .withProjectDir(projectDirectory)
                .withArguments(
                    ":indexino-model:publishToMavenLocal",
                    ":indexino-plugin-api:publishToMavenLocal",
                    ":indexino-selection-context:publishToMavenLocal",
                    ":publishToMavenLocal",
                    "-Dmaven.repo.local=${localRepository.absolutePath}",
                    "--stacktrace",
                )
                .withEnvironment(mapOf("JAVA_HOME" to System.getProperty("java.home")))
                .build()
        assertTrue(publication.output.contains("BUILD SUCCESSFUL"), publication.output)

        val fixture = Files.createTempDirectory("indexino-s1-consumer").toFile()
        val cacheDirectory = Files.createTempDirectory("indexino-s1-consumer-cache").toFile()
        copyFixture(projectDirectory.resolve("src/test/resources/consumer-fixtures/s1"), fixture)
        injectFixtureVersions(projectDirectory, fixture)
        git(fixture, "init")
        git(fixture, "config", "user.email", "consumer@example.invalid")
        git(fixture, "config", "user.name", "Indexino Consumer")
        git(fixture, "add", ".")
        git(fixture, "-c", "commit.gpgsign=false", "commit", "-m", "consumer fixture")

        val consumer =
            GradleRunner.create()
                .withProjectDir(fixture)
                .withArguments(
                    "run",
                    "--args=${fixture.absolutePath}",
                    "-Dmaven.repo.local=${localRepository.absolutePath}",
                    "--stacktrace",
                )
                .withEnvironment(
                    mapOf(
                        "JAVA_HOME" to System.getProperty("java.home"),
                        "INDEXINO_CACHE_DIR" to cacheDirectory.absolutePath,
                    )
                )
                .build()

        assertContains(consumer.output, "INDEXINO_S1_CONSUMER_OK")
    }

    private fun copyFixture(source: File, destination: File) {
        Files.walk(source.toPath()).use { paths ->
            paths.forEach { path ->
                val target = destination.toPath().resolve(source.toPath().relativize(path))
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun injectFixtureVersions(projectDirectory: File, fixture: File) {
        val indexinoVersion = projectVersion(projectDirectory)
        val versionCatalog = projectDirectory.resolve("gradle/libs.versions.toml").readText()
        val kotlinVersion =
            checkNotNull(Regex("""(?m)^kotlin\s*=\s*"([^"]+)"""").find(versionCatalog))
                .groupValues[1]
        val buildFile = fixture.resolve("build.gradle.kts")
        buildFile.writeText(
            buildFile
                .readText()
                .replace("__INDEXINO_VERSION__", indexinoVersion)
                .replace("__KOTLIN_VERSION__", kotlinVersion)
        )
    }

    private fun projectVersion(projectDirectory: File): String =
        Properties()
            .apply { projectDirectory.resolve("gradle.properties").inputStream().use(::load) }
            .getProperty("VERSION_NAME")
            .let(::checkNotNull)

    private fun git(directory: File, vararg arguments: String) {
        val process =
            ProcessBuilder("git", "-C", directory.absolutePath, *arguments)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
    }
}
