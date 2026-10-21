package dev.sebastiano.indexino.producer.xml

import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.producer.IndexBuildContext
import dev.sebastiano.indexino.producer.IndexedSource
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceMetadataTest {
    @Test
    fun `metadata lookup indexes source identities once and preserves origin namespaces`() {
        val root = createTempDirectory("resource-metadata-lookup-")
        try {
            val first = root.resolve("first").createDirectories()
            val second = root.resolve("second").createDirectories()
            first.resolve("build.gradle.kts").writeText("namespace = \"example.first\"")
            second.resolve("build.gradle.kts").writeText("namespace = \"example.second\"")
            val firstSource = IndexedSource("first", first, "src/main/kotlin/Use.kt")
            val secondSource = IndexedSource("second", second, "src/main/kotlin/Use.kt")
            val inventory =
                List(512) { IndexedSource("first", first, "src/main/kotlin/Unused$it.kt") } +
                    listOf(
                        firstSource,
                        secondSource,
                        IndexedSource("first", first, "build.gradle.kts"),
                        IndexedSource("second", second, "build.gradle.kts"),
                    )
            var visits = 0
            val counted =
                object : AbstractList<IndexedSource>() {
                    override val size: Int
                        get() = inventory.size

                    override fun get(index: Int): IndexedSource {
                        visits++
                        return inventory[index]
                    }
                }
            XodusCodeIndexStore.open(root.resolve("index")).use { store ->
                val context =
                    IndexBuildContext(
                        store = store,
                        commitHash = "metadata-lookup",
                        sourceFiles = inventory.map { it.path },
                        sources = counted,
                    )
                visits = 0
                repeat(100) {
                    assertEquals(
                        "example.first",
                        ResourceMetadata.resourcePackage(context, firstSource),
                    )
                    assertEquals(
                        "example.second",
                        ResourceMetadata.resourcePackage(context, secondSource),
                    )
                }
                assertTrue(
                    visits <= inventory.size * 2,
                    "Repeated metadata queries must not rescan the source inventory: $visits visits",
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
