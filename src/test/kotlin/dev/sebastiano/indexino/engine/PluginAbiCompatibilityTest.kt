package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.plugin.api.PluginDescriptor
import java.io.ByteArrayInputStream
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import org.junit.jupiter.api.io.TempDir

class PluginAbiCompatibilityTest {
    @TempDir lateinit var root: Path

    @Test
    fun `older compatible minor is accepted`() {
        val support = PluginAbiSupport.of("1.1.0", "1.3.0")

        support.requireCompatible("example.plugin", "1.2.0")
    }

    @Test
    fun `target newer than host is rejected with actionable diagnostic`() {
        val failure =
            assertFailsWith<PluginAbiCompatibilityException> {
                PluginAbiSupport.of("1.1.0", "1.3.0").requireCompatible("example.plugin", "1.4.0")
            }

        assertDiagnostic(failure, target = "1.4.0")
    }

    @Test
    fun `major mismatch is rejected with actionable diagnostic`() {
        val failure =
            assertFailsWith<PluginAbiCompatibilityException> {
                PluginAbiSupport.of("1.1.0", "1.3.0").requireCompatible("example.plugin", "2.0.0")
            }

        assertDiagnostic(failure, target = "2.0.0")
    }

    @Test
    fun `malformed target is a typed compatibility diagnostic`() {
        val failure =
            assertFailsWith<PluginAbiCompatibilityException> {
                PluginAbiSupport.of("1.1.0", "1.3.0").requireCompatible("example.plugin", "latest")
            }

        assertDiagnostic(failure, target = "latest")
    }

    @Test
    fun `host support loads only host-owned ABI metadata`() {
        val requested = mutableListOf<String>()
        val loader =
            object : ClassLoader(null) {
                override fun getResourceAsStream(name: String): java.io.InputStream? {
                    requested.add(name)
                    return when (name) {
                        "META-INF/indexino/host-plugin-abi.properties" ->
                            ByteArrayInputStream("minimum=1.1.0\ncurrent=1.3.0\n".toByteArray())
                        "META-INF/indexino/plugin-abi.properties" ->
                            ByteArrayInputStream("minimum=9.0.0\ncurrent=9.9.0\n".toByteArray())
                        else -> null
                    }
                }
            }

        assertEquals(PluginAbiSupport.of("1.1.0", "1.3.0"), PluginAbiSupport.load(loader))
        assertEquals(listOf("META-INF/indexino/host-plugin-abi.properties"), requested)
    }

    @Test
    fun `incompatible manifest is rejected before provider class loading`() {
        val pluginJar = pluginJar("2.0.0", "example.Provider")
        val parent =
            object : ClassLoader(javaClass.classLoader) {
                var providerLoadAttempted = false

                override fun loadClass(name: String): Class<*> {
                    if (name == "example.Provider") providerLoadAttempted = true
                    return super.loadClass(name)
                }
            }

        assertFailsWith<PluginAbiCompatibilityException> {
            PluginRegistry.load(listOf(pluginJar), parent, PluginAbiSupport.of("1.0.0", "1.3.0"))
        }
        assertTrue(!parent.providerLoadAttempted, "Provider class was loaded before ABI rejection")
    }

    @Test
    fun `each compatible plugin dependency closure receives an isolated classloader`() {
        val first = pluginJar("1.0.0", "example.FirstProvider", "first.jar")
        val second = pluginJar("1.1.0", "example.SecondProvider", "second.jar")
        val createdFor = mutableListOf<Path>()

        PluginRegistry.load(
            listOf(first, second),
            javaClass.classLoader,
            PluginAbiSupport.of("1.0.0", "1.3.0"),
        ) { pluginJar, _ ->
            createdFor.add(pluginJar)
            object : ClassLoader(null) {}
        }

        assertEquals(listOf(first, second), createdFor)
    }

    @Test
    fun `plugin dependencies load child first while shared APIs use the parent`() {
        val dependencyName = DependencyMarker::class.java.name
        val kotlinxDependencyName = Job::class.java.name
        val pluginJar =
            root.resolve("dependency.jar").also { path ->
                JarOutputStream(path.outputStream()).use { jar ->
                    jar.putNextEntry(JarEntry(dependencyName.replace('.', '/') + ".class"))
                    jar.write(classBytes(dependencyName))
                    jar.closeEntry()
                    jar.putNextEntry(JarEntry(kotlinxDependencyName.replace('.', '/') + ".class"))
                    jar.write(classBytes(kotlinxDependencyName))
                    jar.closeEntry()
                }
            }

        IsolatedPluginClassLoader(pluginJar, javaClass.classLoader).use { loader ->
            assertNotSame(DependencyMarker::class.java, loader.loadClass(dependencyName))
            assertNotSame(Job::class.java, loader.loadClass(kotlinxDependencyName))
            assertSame(
                PluginDescriptor::class.java,
                loader.loadClass(PluginDescriptor::class.java.name),
            )
        }
    }

    @Test
    fun `plugin resources load child first while retaining parent resources`() {
        val resourceName = "example/dependency.properties"
        val hostJar = resourceJar("host.jar", resourceName, "source=host")
        val pluginJar = resourceJar("plugin-resources.jar", resourceName, "source=plugin")

        URLClassLoader(arrayOf(hostJar.toUri().toURL()), javaClass.classLoader).use { parent ->
            IsolatedPluginClassLoader(pluginJar, parent).use { loader ->
                assertEquals("source=plugin", loader.getResource(resourceName)?.readText())
                assertEquals(
                    listOf("source=plugin", "source=host"),
                    loader.getResources(resourceName).toList().map { it.readText() },
                )
            }
        }
    }

    private fun assertDiagnostic(failure: PluginAbiCompatibilityException, target: String) {
        assertEquals("1.3.0", failure.hostAbi)
        assertEquals(target, failure.targetAbi)
        assertEquals("[1.1.0, 1.3.0]", failure.supportedRange)
        val message = failure.message.orEmpty()
        assertTrue(message.contains("host ABI 1.3.0"), message)
        assertTrue(message.contains("target ABI $target"), message)
        assertTrue(message.contains("supported range [1.1.0, 1.3.0]"), message)
        assertTrue(message.contains("Rebuild the plugin"), message)
    }

    private fun pluginJar(
        targetAbi: String,
        providerClass: String,
        fileName: String = "plugin.jar",
    ): Path {
        val manifest =
            Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes.putValue("Indexino-Plugin-ABI-Target", targetAbi)
            }
        return root.resolve(fileName).also { path ->
            JarOutputStream(path.outputStream(), manifest).use { jar ->
                jar.putNextEntry(
                    JarEntry(
                        "META-INF/services/" +
                            "dev.sebastiano.indexino.plugin.api.IndexinoPluginProvider"
                    )
                )
                jar.write(providerClass.toByteArray())
                jar.closeEntry()
            }
        }
    }

    private fun classBytes(className: String): ByteArray =
        checkNotNull(
                javaClass.classLoader.getResourceAsStream(className.replace('.', '/') + ".class")
            ) {
                "Missing test dependency bytecode for $className"
            }
            .use { it.readBytes() }

    private fun resourceJar(fileName: String, resourceName: String, contents: String): Path =
        root.resolve(fileName).also { path ->
            JarOutputStream(path.outputStream()).use { jar ->
                jar.putNextEntry(JarEntry(resourceName))
                jar.write(contents.toByteArray())
                jar.closeEntry()
            }
        }

    private class DependencyMarker
}
