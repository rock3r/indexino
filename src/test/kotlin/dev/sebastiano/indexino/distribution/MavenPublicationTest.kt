package dev.sebastiano.indexino.distribution

import java.io.File
import java.util.jar.JarFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.w3c.dom.Element

@Tag("publication")
class MavenPublicationTest {
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
        val moduleFile = requireArtifact("Gradle module metadata", "$artifactStem.module")
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
        val moduleText = moduleFile.readText()
        listOf(pomText, moduleText).forEach { metadata ->
            assertFalse(
                metadata.contains("-shrunk.jar"),
                "Shrunk JAR leaked into publication metadata",
            )
            assertFalse(
                metadata.contains("shadowRuntimeElements"),
                "Shadow's optional runtime variant leaked into publication metadata",
            )
        }
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
