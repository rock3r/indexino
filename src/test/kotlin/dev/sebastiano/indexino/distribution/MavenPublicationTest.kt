package dev.sebastiano.indexino.distribution

import java.io.File
import java.util.jar.JarFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element

@Tag("publication")
class MavenPublicationTest {
    @TempDir lateinit var tempDir: File

    @Test
    fun `publication remains thin and excludes distribution variants`() {
        val artifactDirectory = requiredProperty("indexino.publicationDirectory").let(::File)
        val groupId = requiredProperty("indexino.publicationGroup")
        val artifactId = requiredProperty("indexino.publicationArtifact")
        val publicationVersion = requiredProperty("indexino.publicationVersion")
        assertEquals("dev.sebastiano.indexino", groupId)
        assertEquals("indexino", artifactId)
        val publishedFiles = artifactDirectory.listFiles().orEmpty().filter(File::isFile)

        fun requireArtifact(label: String, name: String): File =
            assertNotNull(
                publishedFiles.singleOrNull { it.name == name },
                "Expected $label $name in $artifactDirectory; found ${publishedFiles.map(File::getName)}",
            )

        val mainJar =
            assertNotNull(
                publishedFiles.singleOrNull {
                    it.name.endsWith(".jar") &&
                        !it.name.endsWith("-sources.jar") &&
                        !it.name.endsWith("-javadoc.jar") &&
                        !it.name.endsWith("-all.jar") &&
                        !it.name.endsWith("-shrunk.jar")
                },
                "Expected one thin JAR in $artifactDirectory",
            )
        val artifactStem = mainJar.name.removeSuffix(".jar")
        assertCanonicalArtifactStem(artifactStem, artifactId, publicationVersion)
        val sourcesJar = requireArtifact("sources JAR", "$artifactStem-sources.jar")
        requireArtifact("javadoc JAR", "$artifactStem-javadoc.jar")
        val pomFile = requireArtifact("POM", "$artifactStem.pom")
        assertTrue(
            publishedFiles.none { it.name.endsWith(".module") },
            "Dogfood S1 publication must not emit Gradle module metadata",
        )
        assertEquals(
            setOf("$artifactStem.jar", "$artifactStem-sources.jar", "$artifactStem-javadoc.jar"),
            publishedFiles.filter { it.name.endsWith(".jar") }.map(File::getName).toSet(),
            "Unexpected published JAR set",
        )

        JarFile(mainJar).use { jar ->
            assertNotNull(jar.getEntry("dev/sebastiano/indexino/cli/MainCommandKt.class"))
            assertTrue(
                jar.getEntry("com/kotlincodeindex/cli/MainCommandKt.class") == null,
                "Legacy package leaked into the renamed artifact",
            )
            val forbiddenBundledEntries =
                listOf(
                    "com/github/ajalt/clikt/core/CliktCommand.class",
                    "jetbrains/exodus/Environment.class",
                    "kotlin/collections/CollectionsKt.class",
                )
            val bundledDependencies = forbiddenBundledEntries.filter { jar.getEntry(it) != null }
            assertTrue(
                bundledDependencies.isEmpty(),
                "The Maven JAR bundles dependencies: $bundledDependencies",
            )
        }

        JarFile(sourcesJar).use { jar ->
            assertNotNull(jar.getEntry("dev/sebastiano/indexino/cli/MainCommand.kt"))
        }

        val project =
            secureDocumentBuilderFactory().newDocumentBuilder().parse(pomFile).documentElement
        assertEquals(groupId, requireText(project, "groupId"))
        assertEquals(artifactId, requireText(project, "artifactId"))
        assertEquals(publicationVersion, requireText(project, "version"))
        requireText(project, "name")
        requireText(project, "description")
        requireText(project, "url")
        assertNotNull(directChild(project, "licenses"), "Published POM is missing <licenses>")
        assertNotNull(directChild(project, "scm"), "Published POM is missing <scm>")
        assertNotNull(directChild(project, "developers"), "Published POM is missing <developers>")
        assertNotNull(
            directChild(project, "dependencies"),
            "Published POM must declare the thin JAR's runtime dependencies",
        )

        val pomText = pomFile.readText()
        listOf(pomText).forEach { metadata ->
            assertFalse(
                metadata.contains("-shrunk.jar"),
                "Shrunk JAR leaked into publication metadata",
            )
            assertFalse(
                metadata.contains("shadowRuntimeElements"),
                "Shadow's optional runtime variant leaked into publication metadata",
            )
        }

        assertSiblingCentralDependenciesAreReleasable(pomText, groupId)
    }

    @Test
    fun `publication includes a bom that aligns every public artifact`() {
        val repository = requiredProperty("indexino.publicationRepository").let(::File)
        val groupId = requiredProperty("indexino.publicationGroup")
        val version = requiredProperty("indexino.publicationVersion")
        val bomDirectory =
            repository.resolve(groupId.replace('.', '/')).resolve("indexino-bom/$version")
        val bomPom =
            assertNotNull(
                bomDirectory.listFiles()?.singleOrNull { file ->
                    file.name.startsWith("indexino-bom-") && file.extension == "pom"
                },
                "Expected one published BOM POM in $bomDirectory",
            )

        assertTrue(bomPom.isFile, "Expected published BOM at $bomPom")
        val pomText = bomPom.readText()
        assertTrue(pomText.contains("<packaging>pom</packaging>"), pomText)
        listOf("indexino-model", "indexino", "indexino-plugin-api", "indexino-selection-context")
            .forEach { artifactId ->
                assertTrue(
                    pomText.contains("<artifactId>$artifactId</artifactId>"),
                    "BOM does not align $artifactId:\n$pomText",
                )
            }
        assertTrue(
            pomText.contains("<version>$version</version>"),
            "BOM does not pin the release-train version:\n$pomText",
        )
    }

    @Test
    fun `publication embeds generated host range and compiled plugin target ABI`() {
        val repository = requiredProperty("indexino.publicationRepository").let(::File)
        val groupId = requiredProperty("indexino.publicationGroup")
        val version = requiredProperty("indexino.publicationVersion")

        val hostJar = publishedMainJar(repository, groupId, "indexino", version)
        JarFile(hostJar).use { jar ->
            assertEquals(
                "1.0.0",
                jar.manifest.mainAttributes.getValue("Indexino-Plugin-ABI-Version"),
            )
            assertEquals(
                "1.0.0..1.0.0",
                jar.manifest.mainAttributes.getValue("Indexino-Plugin-ABI-Supported"),
            )
            assertNotNull(jar.getEntry("META-INF/indexino/host-plugin-abi.properties"))
        }

        val pluginJar = publishedMainJar(repository, groupId, "indexino-selection-context", version)
        JarFile(pluginJar).use { jar ->
            assertEquals(
                "1.0.0",
                jar.manifest.mainAttributes.getValue("Indexino-Plugin-ABI-Target"),
            )
        }
    }

    @Test
    fun `tag release cannot publish Central without every sibling POM dependency`() {
        val releaseWorkflow = File(".github/workflows/release.yml").readText()
        val modelBuild = File("indexino-model/build.gradle.kts").readText()
        val modelHasCentralPublisher =
            modelBuild.contains("com.vanniktech.maven.publish") ||
                modelBuild.contains("publishToMavenCentral")
        assertTrue(modelHasCentralPublisher, "S5 requires indexino-model Central publication")
        assertTrue(
            releaseWorkflow.contains("publishToMavenCentral"),
            "S5 release workflow must publish the complete Central release train",
        )
        assertFalse(
            releaseWorkflow.contains("blocked until S5"),
            "S5 release workflow must not retain the incomplete-coordinate block",
        )
    }

    @Test
    fun `ordinary JVM 25 consumer resolves coordinates and loads the public facade`() {
        val repository = requiredProperty("indexino.publicationRepository").let(::File)
        val groupId = requiredProperty("indexino.publicationGroup")
        val artifactId = requiredProperty("indexino.publicationArtifact")
        val version = requiredProperty("indexino.publicationVersion")
        val consumer = tempDir.resolve("consumer").apply(File::mkdirs)
        consumer.resolve("settings.gradle.kts").writeText("rootProject.name = \"consumer\"\n")
        consumer.resolve("src/main/java/consumer/Consumer.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package consumer;

                import dev.sebastiano.indexino.api.IndexScope;
                import dev.sebastiano.indexino.model.PluginId;
                import dev.sebastiano.indexino.plugin.api.IndexinoPluginProvider;
                import dev.sebastiano.indexino.plugin.selection.SelectionContextPlugin;

                public final class Consumer {
                    private Consumer() {}

                    public static void main(String[] args) {
                        IndexinoPluginProvider plugin = new SelectionContextPlugin();
                        System.out.println(IndexScope.gradle(":"));
                        System.out.println(PluginId.of("consumer"));
                        System.out.println(plugin.getClass().getSimpleName());
                    }
                }
                """
                    .trimIndent()
            )
        }
        consumer
            .resolve("build.gradle.kts")
            .writeText(
                """
            plugins { application }

            repositories {
                maven { url = uri("${repository.toURI()}") }
                mavenCentral()
            }

            dependencies {
                implementation(platform("$groupId:indexino-bom:$version"))
                implementation("$groupId:$artifactId")
                implementation("$groupId:indexino-model")
                implementation("$groupId:indexino-plugin-api")
                implementation("$groupId:indexino-selection-context")
            }

            java {
                toolchain.languageVersion.set(JavaLanguageVersion.of(25))
            }

            application {
                mainClass.set("consumer.Consumer")
            }
            """
                    .trimIndent()
            )

        val result =
            GradleRunner.create()
                .withProjectDir(consumer)
                .withArguments("--stacktrace", "run")
                .forwardOutput()
                .build()

        assertTrue(result.output.contains("GRADLE"), result.output)
        assertTrue(result.output.contains("PluginId(value=consumer)"), result.output)
        assertTrue(result.output.contains("SelectionContextPlugin"), result.output)
    }

    private fun assertSiblingCentralDependenciesAreReleasable(pomText: String, groupId: String) {
        val siblingArtifacts =
            Regex(
                    """<groupId>\s*${Regex.escape(groupId)}\s*</groupId>\s*<artifactId>\s*([^<]+)\s*</artifactId>"""
                )
                .findAll(pomText)
                .map { it.groupValues[1].trim() }
                .filter { it != "indexino" }
                .toSet()
        if (siblingArtifacts.isEmpty()) return

        val releaseWorkflow = File(".github/workflows/release.yml").readText()
        val modelBuild = File("indexino-model/build.gradle.kts").readText()
        val modelHasCentralPublisher =
            modelBuild.contains("com.vanniktech.maven.publish") ||
                modelBuild.contains("publishToMavenCentral")
        if ("indexino-model" in siblingArtifacts) {
            assertTrue(
                modelHasCentralPublisher,
                "Facade sibling dependencies must be published to Maven Central",
            )
            assertTrue(
                releaseWorkflow.contains("publishToMavenCentral"),
                "Release workflow must publish the complete sibling coordinate set",
            )
        }
    }

    private fun publishedMainJar(
        repository: File,
        groupId: String,
        artifactId: String,
        version: String,
    ): File {
        val directory =
            repository.resolve(groupId.replace('.', '/')).resolve("$artifactId/$version")
        return assertNotNull(
            directory.listFiles()?.singleOrNull { file ->
                file.extension == "jar" &&
                    !file.name.endsWith("-sources.jar") &&
                    !file.name.endsWith("-javadoc.jar")
            },
            "Expected one main $artifactId JAR in $directory",
        )
    }

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "Missing $name" }

    private fun assertCanonicalArtifactStem(stem: String, artifactId: String, version: String) {
        if (version.endsWith("-SNAPSHOT")) {
            val baseVersion = version.removeSuffix("-SNAPSHOT")
            val pattern =
                Regex(
                    "${Regex.escape(artifactId)}-${Regex.escape(baseVersion)}-\\d{8}\\.\\d{6}-\\d+"
                )
            assertTrue(pattern.matches(stem), "Non-canonical snapshot artifact name: $stem")
        } else {
            assertEquals("$artifactId-$version", stem, "Non-canonical release artifact name")
        }
    }

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }

    private fun directChild(parent: Element, name: String): Element? {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element && child.tagName == name) return child
        }
        return null
    }

    private fun requireText(parent: Element, name: String): String {
        val value = directChild(parent, name)?.textContent?.trim().orEmpty()
        assertTrue(value.isNotEmpty(), "Published POM is missing <$name>")
        return value
    }
}
